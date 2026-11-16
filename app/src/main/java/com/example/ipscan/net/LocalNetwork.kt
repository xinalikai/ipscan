package com.example.ipscan.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Параметры сети, в которой находится телефон: свой адрес, подсеть, шлюз.
 */
data class LocalNetworkInfo(
    val ip: String,
    val subnet: Ipv4Subnet,
    val gateway: String?,
    val transport: String,
)

object LocalNetwork {

    /**
     * Берёт активную сеть Wi-Fi/Ethernet и достаёт из неё IPv4-адрес и длину маски.
     *
     * Через [android.net.LinkProperties] это работает не только на Wi-Fi, но и на
     * Ethernet и в режиме точки доступа, и не требует устаревшего WifiManager.
     */
    @Suppress("DEPRECATION") // allNetworks — единственный способ найти Wi-Fi под активным VPN
    fun detect(context: Context): LocalNetworkInfo? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = manager.activeNetwork
        val networks = try {
            manager.allNetworks.toList()
        } catch (_: Exception) {
            listOfNotNull(active)
        }
        for (network in networks.sortedByDescending { it == active }) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> continue
            }
            val link = manager.getLinkProperties(network) ?: continue
            val linkAddress = link.linkAddresses.firstOrNull { address ->
                address.address is Inet4Address &&
                    !address.address.isLoopbackAddress &&
                    !address.address.isLinkLocalAddress
            } ?: continue
            val ip = linkAddress.address.hostAddress ?: continue
            val prefix = if (linkAddress.prefixLength in 8..30) linkAddress.prefixLength else 24
            val subnet = Ipv4Subnet.of(ip, prefix) ?: continue
            val gateway = link.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
            return LocalNetworkInfo(ip, subnet, gateway, transport)
        }
        return null
    }
}
