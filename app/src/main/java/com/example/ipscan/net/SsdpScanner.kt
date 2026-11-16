package com.example.ipscan.net

import android.os.SystemClock
import com.example.ipscan.model.DeviceType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Устройство, ответившее на SSDP-запрос (UPnP). */
data class UpnpDevice(
    val ip: String,
    val name: String?,
    val type: DeviceType?,
    val services: List<String>,
)

/**
 * Поиск устройств по протоколу SSDP (UPnP).
 *
 * Многие роутеры, телевизоры, NAS и принтеры не держат открытых TCP-портов,
 * но всегда отвечают на M-SEARCH — это позволяет находить их там, где простое
 * сканирование портов бессильно.
 */
class SsdpScanner(private val windowMs: Long = 2600) {

    suspend fun discover(): List<UpnpDevice> = withContext(Dispatchers.IO) {
        val devices = LinkedHashMap<String, Raw>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val payload = REQUEST.toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            repeat(3) {
                if (!currentCoroutineContext().isActive) return@use
                runCatching { socket.send(DatagramPacket(payload, payload.size, group, 1900)) }
                delay(60)
            }
            val deadline = SystemClock.elapsedRealtime() + windowMs
            val buffer = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < deadline && currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val ip = packet.address?.hostAddress ?: continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = parseResponse(text) ?: continue
                merge(devices, ip, headers)
            }
        }

        val described = devices.values.filter { it.location != null }.take(MAX_DESCRIPTIONS)
        coroutineScope {
            described.map { device ->
                async(Dispatchers.IO) {
                    val location = device.location ?: return@async
                    device.description = fetchDescription(location)
                }
            }.awaitAll()
        }
        devices.values.map { it.toUpnpDevice() }
    }

    private fun merge(devices: MutableMap<String, Raw>, ip: String, headers: Map<String, String>) {
        val device = devices.getOrPut(ip) { Raw(ip) }
        headers["server"]?.takeIf { it.isNotBlank() }?.let { device.server = it }
        headers["st"]?.takeIf { it.isNotBlank() }?.let { device.types += it }
        headers["location"]?.takeIf { it.isNotBlank() }?.let { if (device.location == null) device.location = it }
    }

    private fun parseResponse(text: String): Map<String, String>? {
        val lines = text.trim().split("\r\n", "\n")
        if (lines.isEmpty() || !lines.first().startsWith("HTTP/", ignoreCase = true)) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return headers
    }

    private fun fetchDescription(location: String): Description? = try {
        val url = URL(location)
        if (url.protocol != "http") {
            null
        } else {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 900
            connection.readTimeout = 900
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("User-Agent", "IPScan/1.0")
            val body = connection.inputStream.use { readLimited(it) }
            descriptionFromXml(body)
        }
    } catch (_: Exception) {
        null
    }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < MAX_DESCRIPTION_BYTES) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun descriptionFromXml(xml: String): Description? {
        val friendly = tagValue(xml, "friendlyName")
        val manufacturer = tagValue(xml, "manufacturer")
        val model = tagValue(xml, "modelName")
        val type = tagValue(xml, "deviceType")
        if (friendly == null && manufacturer == null && model == null) return null
        return Description(friendly, manufacturer, model, type)
    }

    private fun tagValue(xml: String, tag: String): String? = Regex(
        "<$tag>(.*?)</$tag>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    ).find(xml)?.groupValues?.get(1)?.let(::unescapeXml)?.trim()?.takeIf { it.isNotEmpty() }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private class Raw(val ip: String) {
        var server: String? = null
        var location: String? = null
        var description: Description? = null
        val types = LinkedHashSet<String>()

        fun toUpnpDevice(): UpnpDevice {
            val text = buildString {
                types.forEach { append(it).append(' ') }
                description?.deviceType?.let { append(it).append(' ') }
                server?.let { append(it) }
            }.lowercase()
            val type = when {
                "internetgatewaydevice" in text || "wandevice" in text || "wapdevice" in text -> DeviceType.ROUTER
                "printer" in text || "multifunction" in text -> DeviceType.PRINTER
                "mediarenderer" in text || "mediaserver" in text -> DeviceType.TV
                "camera" in text -> DeviceType.CAMERA
                "basic" in text -> DeviceType.IOT
                else -> null
            }
            val services = types
                .mapNotNull { serviceLabel(it) }
                .distinct()
                .take(MAX_SERVICES)
            val name = description?.friendlyName
                ?: description?.modelName?.let { model -> listOfNotNull(description?.manufacturer, model).joinToString(" ") }
            return UpnpDevice(
                ip = ip,
                name = name,
                type = type,
                services = services,
            )
        }
    }

    private data class Description(
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?,
        val deviceType: String?,
    )

    private companion object {
        const val MAX_DESCRIPTIONS = 12
        const val MAX_SERVICES = 4
        const val MAX_DESCRIPTION_BYTES = 64 * 1024

        val REQUEST = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: ssdp:all\r\n")
            append("\r\n")
        }

        fun serviceLabel(st: String): String? {
            val device = st.substringAfter(":device:", "").substringBefore(":")
            val service = st.substringAfter(":service:", "").substringBefore(":")
            val value = device.ifEmpty { service }
            if (value.isEmpty()) return null
            return when {
                value.equals("InternetGatewayDevice", ignoreCase = true) -> "UPnP-шлюз"
                value.equals("MediaRenderer", ignoreCase = true) -> "DLNA-рендерер"
                value.equals("MediaServer", ignoreCase = true) -> "DLNA-сервер"
                value.equals("Basic", ignoreCase = true) -> null
                else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            }
        }
    }
}
