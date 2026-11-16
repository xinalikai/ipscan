package com.example.ipscan.model

/** Открытый TCP-порт и понятное имя службы. */
data class OpenPort(val port: Int, val service: String)

/** Приблизительный тип устройства, определённый по портам, именам и mDNS/SSDP. */
enum class DeviceType(val label: String, val icon: String) {
    ROUTER("Роутер / шлюз", "🌐"),
    COMPUTER("Компьютер", "💻"),
    PHONE("Телефон / планшет", "📱"),
    PRINTER("Принтер", "🖨"),
    CAMERA("IP-камера", "📷"),
    TV("ТВ / медиа", "📺"),
    NAS("NAS / сервер", "🗄"),
    IOT("Умное устройство", "💡"),
    UNKNOWN("Не определён", "❔"),
}

/** Одно найденное устройство в локальной сети. */
data class ScanDevice(
    val ip: String,
    val name: String? = null,
    val type: DeviceType = DeviceType.UNKNOWN,
    val ports: List<OpenPort> = emptyList(),
    val services: List<String> = emptyList(),
)
