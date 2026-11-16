package com.example.ipscan.scan

import android.content.Context
import com.example.ipscan.model.DeviceType
import com.example.ipscan.model.ScanDevice
import com.example.ipscan.net.HostProbe
import com.example.ipscan.net.Ipv4Subnet
import com.example.ipscan.net.MdnsService
import com.example.ipscan.net.NsdScanner
import com.example.ipscan.net.PortScanner
import com.example.ipscan.net.ReverseDns
import com.example.ipscan.net.SsdpScanner
import com.example.ipscan.net.UpnpDevice
import com.example.ipscan.net.WebInfo
import com.example.ipscan.net.WebProbe
import com.example.ipscan.net.toIpv4OrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** События, которые сканер отдаёт в интерфейс по мере работы. */
sealed interface ScanEvent {
    data class Progress(val phase: String, val done: Int, val total: Int) : ScanEvent
    data class Found(val device: ScanDevice) : ScanEvent
    data class Finished(val count: Int) : ScanEvent
}

/**
 * Оркестратор сканирования.
 *
 * 1. Параллельно запускает пассивное обнаружение (SSDP и mDNS) — оно находит
 *    устройства, у которых нет открытых портов, и даёт их имена.
 * 2. Одновременно проверяет каждый адрес подсети TCP- и ICMP-запросами.
 * 3. Для найденных хостов сканирует порты, читает обратный DNS и заголовок
 *    веб-интерфейса, чтобы подписать устройство.
 *
 * Результаты отдаются потоком, поэтому список в интерфейсе наполняется сразу,
 * а не после завершения всего сканирования.
 */
class NetworkScanner(private val context: Context) {

    fun scan(subnet: Ipv4Subnet, gateway: String?, selfIp: String?): Flow<ScanEvent> = channelFlow {
        val hosts = subnet.hostAddresses().filter { it != selfIp }
        val hints = ConcurrentHashMap<String, DeviceHint>()
        val alive = ConcurrentHashMap.newKeySet<String>()

        val ssdpJob = launch {
            val found = runCatching { SsdpScanner().discover() }.getOrNull().orEmpty()
            found.forEach { device ->
                alive += device.ip
                hints.merge(device.ip, device.toHint()) { old, new -> old.merge(new) }
            }
        }
        val mdnsJob = launch {
            val found = runCatching { NsdScanner(context).discover() }.getOrNull().orEmpty()
            found.forEach { service ->
                alive += service.ip
                hints.merge(service.ip, service.toHint()) { old, new -> old.merge(new) }
            }
        }

        val checked = AtomicInteger()
        val hostGate = Semaphore(HOST_PARALLELISM)
        coroutineScope {
            hosts.map { ip ->
                async(Dispatchers.IO) {
                    hostGate.withPermit {
                        if (HostProbe.isAlive(ip)) alive += ip
                    }
                    val current = checked.incrementAndGet()
                    if (current % 8 == 0 || current == hosts.size) {
                        send(ScanEvent.Progress("Поиск устройств", current, hosts.size))
                    }
                }
            }.awaitAll()
        }

        ssdpJob.join()
        mdnsJob.join()

        val targets = alive
            .filter { it.toIpv4OrNull() != null }
            .sortedBy { it.toIpv4OrNull() ?: 0L }
        send(ScanEvent.Progress("Определение устройств", 0, targets.size))
        if (targets.isEmpty()) {
            send(ScanEvent.Finished(0))
            return@channelFlow
        }

        val enriched = AtomicInteger()
        val enrichGate = Semaphore(ENRICH_PARALLELISM)
        coroutineScope {
            targets.map { ip ->
                async(Dispatchers.IO) {
                    val device = enrichGate.withPermit { enrich(ip, hints[ip], gateway) }
                    send(ScanEvent.Found(device))
                    send(ScanEvent.Progress("Определение устройств", enriched.incrementAndGet(), targets.size))
                }
            }.awaitAll()
        }
        send(ScanEvent.Finished(targets.size))
    }

    private suspend fun enrich(ip: String, hint: DeviceHint?, gateway: String?): ScanDevice {
        val ports = runCatching { PortScanner.scan(ip) }.getOrDefault(emptyList())
        val numbers = ports.map { it.port }
        val hostname = if (hint?.name.isNullOrBlank()) ReverseDns.lookup(ip) else null
        // UPnP/mDNS уже знают имя и тип — лишний HTTP-запрос не нужен.
        val web = if (hint?.name.isNullOrBlank() || hint.type == null) webInfo(ip, numbers) else null
        val name = hint?.name?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: web?.title?.takeIf { !WebProbe.isGenericTitle(it) }
        val type = hint?.type ?: guessType(name, web, numbers, ip == gateway)
        return ScanDevice(
            ip = ip,
            name = name?.takeIf { it.isNotBlank() },
            type = type,
            ports = ports,
            services = hint?.services.orEmpty(),
        )
    }

    /** Пробует веб-интерфейс: сначала HTTP, при неудаче — HTTPS. */
    private suspend fun webInfo(ip: String, ports: List<Int>): WebInfo? {
        val httpPort = ports.firstOrNull { it in HTTP_PORTS }
        val httpsPort = ports.firstOrNull { it in HTTPS_PORTS }
        if (httpPort != null) {
            WebProbe.probe(ip, httpPort, secure = false)?.let { return it }
        }
        return httpsPort?.let { WebProbe.probe(ip, it, secure = true) }
    }

    private fun guessType(
        name: String?,
        web: WebInfo?,
        ports: List<Int>,
        isGateway: Boolean,
    ): DeviceType {
        if (isGateway) return DeviceType.ROUTER
        val text = listOfNotNull(name, web?.title, web?.server).joinToString(" ").lowercase()
        return when {
            text.containsAny("printer", "epson", "brother", "xerox", "kyocera", "hewlett", "hp-", "laserjet", "deskjet") ->
                DeviceType.PRINTER
            text.containsAny("iphone", "ipad", "android", "pixel", "redmi", "galaxy", "honor", "huawei", "oneplus", "xiaomi") ->
                DeviceType.PHONE
            text.containsAny("router", "gateway", "keenetic", "mikrotik", "tp-link", "tplink", "openwrt", "asus", "tenda", "zyxel", "d-link", "netgear", "ubiquiti", "archer") ->
                DeviceType.ROUTER
            text.containsAny("synology", "qnap", "diskstation", "openmediavault", "terramaster", "nas-", "plex") ->
                DeviceType.NAS
            text.containsAny("apple tv", "appletv", "chromecast", "bravia", "smart-tv", "smarttv", "webos", "roku", "shield", "android tv") ->
                DeviceType.TV
            text.containsAny("camera", "hikvision", "dahua", "reolink", "ipcam", "webcam", "nvr", "dvr") ->
                DeviceType.CAMERA
            text.containsAny("sonos", "philips hue", "ikea", "tasmota", "esphome", "tuy", "smart home", "home assistant") ->
                DeviceType.IOT
            9100 in ports || 631 in ports || 515 in ports -> DeviceType.PRINTER
            62078 in ports -> DeviceType.PHONE
            8009 in ports || 7000 in ports -> DeviceType.TV
            554 in ports || 8554 in ports -> DeviceType.CAMERA
            32400 in ports -> DeviceType.NAS
            445 in ports || 139 in ports || 3389 in ports -> DeviceType.COMPUTER
            53 in ports && 80 in ports -> DeviceType.ROUTER
            1883 in ports -> DeviceType.IOT
            22 in ports -> DeviceType.NAS
            else -> DeviceType.UNKNOWN
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it) }

    private data class DeviceHint(
        val name: String? = null,
        val type: DeviceType? = null,
        val services: List<String> = emptyList(),
    ) {
        fun merge(other: DeviceHint): DeviceHint = DeviceHint(
            name = name ?: other.name,
            type = type ?: other.type,
            services = (services + other.services).distinct(),
        )
    }

    private fun UpnpDevice.toHint(): DeviceHint = DeviceHint(
        name = name,
        type = type,
        services = services,
    )

    private fun MdnsService.toHint(): DeviceHint = DeviceHint(
        name = name,
        type = deviceType,
        services = listOf(label),
    )

    private companion object {
        const val HOST_PARALLELISM = 96
        const val ENRICH_PARALLELISM = 8

        val HTTP_PORTS = setOf(80, 3000, 5000, 8000, 8080, 8081, 8088, 8888, 9000, 10000)
        val HTTPS_PORTS = setOf(443, 8443)
    }
}
