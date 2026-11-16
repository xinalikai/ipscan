package com.example.ipscan.net

/**
 * IPv4-подсеть: сетевой адрес (в виде Long) и длина маски.
 *
 * Адреса хранятся как числа 0..2^32-1, чтобы не путаться со знаковыми Int.
 */
data class Ipv4Subnet(val network: Long, val prefix: Int) {

    /** Всего адресов в подсети, включая сетевой и широковещательный. */
    val addressCount: Long get() = 1L shl (32 - prefix)

    val cidr: String get() = "${network.toIpv4String()}/$prefix"

    /**
     * Адреса устройств (без сетевого и широковещательного адресов).
     *
     * [limit] защищает от случайного запроса подсети на миллионы адресов.
     */
    fun hostAddresses(limit: Int = 2048): List<String> {
        if (prefix >= 31) return listOf(network.toIpv4String())
        val first = network + 1
        val last = minOf(network + addressCount - 2, first + limit - 1)
        if (last < first) return emptyList()
        val result = ArrayList<String>((last - first + 1).toInt())
        var value = first
        while (value <= last) {
            result += value.toIpv4String()
            value++
        }
        return result
    }

    companion object {
        fun of(address: String, prefix: Int): Ipv4Subnet? {
            val value = address.toIpv4OrNull() ?: return null
            if (prefix !in 0..32) return null
            return Ipv4Subnet(value and maskFor(prefix), prefix)
        }

        fun maskFor(prefix: Int): Long =
            if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    }
}

/**
 * Разбирает строку вида "192.168.1.0/24". Без маски считается /24.
 * Поддерживаются маски от /8 до /30 — более узкие и широкие подсети для
 * сканера не имеют практического смысла.
 */
fun parseCidr(input: String): Ipv4Subnet? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val slash = text.indexOf('/')
    val address = if (slash < 0) text else text.substring(0, slash)
    val prefix = if (slash < 0) 24 else text.substring(slash + 1).trim().toIntOrNull() ?: return null
    if (prefix !in 8..30) return null
    return Ipv4Subnet.of(address, prefix)
}

fun String.toIpv4OrNull(): Long? {
    val parts = trim().split('.')
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        if (part.isEmpty() || part.length > 3) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

fun Long.toIpv4String(): String =
    "${(this shr 24) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 8) and 0xFF}.${this and 0xFF}"
