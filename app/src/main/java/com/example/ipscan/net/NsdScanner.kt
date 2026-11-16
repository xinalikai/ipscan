package com.example.ipscan.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.ipscan.model.DeviceType
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Сервис, найденный через mDNS (DNS-SD). */
data class MdnsService(
    val ip: String,
    val name: String,
    val type: String,
    val label: String,
    val deviceType: DeviceType?,
)

/**
 * Поиск устройств через mDNS.
 *
 * Даёт имена и типы устройств (Apple TV, Chromecast, принтеры, NAS, HomeKit),
 * которые не отвечают на сканирование портов.
 */
class NsdScanner(
    context: Context,
    private val windowMs: Long = 4200,
) {

    private val manager: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION") // NsdServiceInfo.host: hostAddresses появился только в API 34
    suspend fun discover(): List<MdnsService> = coroutineScope {
        val nsd = manager ?: return@coroutineScope emptyList()
        val found = ConcurrentHashMap<String, MdnsService>()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        val worker = launch(Dispatchers.IO) {
            for (info in queue) {
                val resolved = resolve(nsd, info) ?: continue
                val ip = resolved.host?.hostAddress ?: continue
                if (ip.toIpv4OrNull() == null) continue
                val type = info.serviceType.orEmpty()
                val label = labelFor(type)
                val service = MdnsService(
                    ip = ip,
                    name = resolved.serviceName ?: info.serviceName.orEmpty(),
                    type = type,
                    label = label,
                    deviceType = deviceTypeFor(type),
                )
                found["${service.ip}|${service.type}|${service.name}"] = service
            }
        }

        try {
            for (type in SERVICE_TYPES) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        runCatching { nsd.stopServiceDiscovery(this) }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        queue.trySend(serviceInfo)
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                }
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onSuccess { listeners += listener }
            }
            delay(windowMs)
        } finally {
            listeners.forEach { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        }
        worker.cancel()
        queue.close()
        found.values.toList()
    }

    /**
     * Android разрешает только один одновременный resolve, поэтому запросы идут
     * по одному из фонового обработчика очереди, а этот метод лишь ждёт ответ
     * с таймаутом.
     */
    @Suppress("DEPRECATION") // resolveService — рабочий вариант для minSdk 24
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                }
                try {
                    nsd.resolveService(info, listener)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun labelFor(type: String): String = when {
        type.startsWith("_ipp") || type.startsWith("_printer") -> "Печать"
        type.startsWith("_airplay") || type.startsWith("_raop") -> "AirPlay"
        type.startsWith("_googlecast") -> "Google Cast"
        type.startsWith("_companion-link") -> "Apple"
        type.startsWith("_smb") || type.startsWith("_workstation") -> "SMB"
        type.startsWith("_plex") -> "Plex"
        type.startsWith("_ssh") -> "SSH"
        type.startsWith("_http") -> "Веб-интерфейс"
        else -> type.removePrefix("_").removeSuffix("._tcp").removeSuffix("._udp")
    }

    private fun deviceTypeFor(type: String): DeviceType? = when {
        type.startsWith("_ipp") || type.startsWith("_printer") -> DeviceType.PRINTER
        type.startsWith("_airplay") || type.startsWith("_googlecast") || type.startsWith("_raop") -> DeviceType.TV
        type.startsWith("_companion-link") -> DeviceType.PHONE
        type.startsWith("_smb") || type.startsWith("_workstation") || type.startsWith("_ssh") -> DeviceType.NAS
        type.startsWith("_plex") -> DeviceType.NAS
        type.startsWith("_hap") || type.startsWith("_matter") -> DeviceType.IOT
        else -> null
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 1600L

        val SERVICE_TYPES = listOf(
            "_http._tcp",
            "_https._tcp",
            "_ipp._tcp",
            "_printer._tcp",
            "_airplay._tcp",
            "_googlecast._tcp",
            "_raop._tcp",
            "_ssh._tcp",
            "_smb._tcp",
            "_workstation._tcp",
            "_companion-link._tcp",
            "_plexmediasvr._tcp",
        )
    }
}
