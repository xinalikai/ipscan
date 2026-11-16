package com.example.ipscan.net

import com.example.ipscan.model.OpenPort
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Результат попытки TCP-подключения. */
enum class ConnectState { OPEN, REFUSED, TIMEOUT, ERROR }

object PortScanner {

    private const val CONNECT_TIMEOUT_MS = 450
    private const val PER_HOST_PARALLELISM = 16

    /** Порты, которые чаще всего встречаются в домашних и офисных сетях. */
    val COMMON_PORTS: List<Int> = listOf(
        21, 22, 23, 25, 53, 80, 81, 110, 139, 143, 443, 445, 515, 548, 554,
        631, 873, 993, 995, 1080, 1883, 2049, 3000, 3306, 3389, 5000, 5357,
        5432, 5555, 5900, 6379, 7000, 8000, 8009, 8080, 8081, 8088, 8443,
        8888, 9000, 9100, 9999, 10000, 32400, 49152, 62078,
    )

    private val serviceNames = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
        80 to "HTTP", 81 to "HTTP", 110 to "POP3", 139 to "NetBIOS", 143 to "IMAP",
        443 to "HTTPS", 445 to "SMB", 515 to "LPD", 548 to "AFP", 554 to "RTSP",
        631 to "IPP", 873 to "rsync", 993 to "IMAPS", 995 to "POP3S", 1080 to "SOCKS",
        1883 to "MQTT", 2049 to "NFS", 3000 to "HTTP", 3306 to "MySQL",
        3389 to "RDP", 5000 to "HTTP/UPnP", 5357 to "WSD", 5432 to "PostgreSQL",
        5555 to "ADB", 5900 to "VNC", 6379 to "Redis", 7000 to "AirPlay",
        8000 to "HTTP", 8009 to "Google Cast", 8080 to "HTTP-прокси", 8081 to "HTTP",
        8088 to "HTTP", 8443 to "HTTPS", 8888 to "HTTP", 9000 to "HTTP",
        9100 to "JetDirect", 9999 to "HTTP", 10000 to "Webmin", 32400 to "Plex",
        49152 to "UPnP", 62078 to "iPhone Lockdown",
    )

    fun serviceName(port: Int): String = serviceNames[port] ?: "TCP"

    /**
     * Параллельно проверяет список портов, но не больше [PER_HOST_PARALLELISM]
     * подключений одновременно, чтобы не переполнить таблицу сокетов.
     */
    suspend fun scan(
        ip: String,
        ports: List<Int> = COMMON_PORTS,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): List<OpenPort> = coroutineScope {
        val semaphore = Semaphore(PER_HOST_PARALLELISM)
        ports.map { port ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (connectState(ip, port, timeoutMs) == ConnectState.OPEN) {
                        OpenPort(port, serviceName(port))
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    /**
     * Пробует установить TCP-соединение и различает отказ от таймаута:
     * отказ (RST) означает, что хост жив и просто закрыл порт.
     */
    suspend fun connectState(ip: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): ConnectState =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                }
                ConnectState.OPEN
            } catch (_: SocketTimeoutException) {
                ConnectState.TIMEOUT
            } catch (_: ConnectException) {
                ConnectState.REFUSED
            } catch (_: Exception) {
                ConnectState.ERROR
            }
        }
}

/**
 * Определение «живости» адреса.
 *
 * Основной способ — TCP: живой хост отвечает либо SYN-ACK (порт открыт), либо RST
 * (порт закрыт), а на мёртвый адрес мы получаем таймаут. ICMP используется как
 * запасной вариант для устройств, которые молча отбрасывают TCP-пакеты.
 */
object HostProbe {

    private const val TCP_TIMEOUT_MS = 350
    private const val ICMP_TIMEOUT_MS = 500
    private val DISCOVERY_PORTS = listOf(443, 80, 44444)

    suspend fun isAlive(ip: String): Boolean {
        val answered = coroutineScope {
            DISCOVERY_PORTS.map { port ->
                async(Dispatchers.IO) {
                    val state = PortScanner.connectState(ip, port, TCP_TIMEOUT_MS)
                    state == ConnectState.OPEN || state == ConnectState.REFUSED
                }
            }.awaitAll().any { it }
        }
        if (answered) return true
        return withContext(Dispatchers.IO) {
            try {
                val address = java.net.InetAddress.getByName(ip)
                address.isReachable(ICMP_TIMEOUT_MS)
            } catch (_: Exception) {
                false
            }
        }
    }
}
