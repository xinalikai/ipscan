package com.example.ipscan.net

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Обратный DNS-запрос с жёстким таймаутом.
 *
 * [InetAddress.getCanonicalHostName] — блокирующий вызов, который нельзя прервать
 * отменой корутины, поэтому запросы идут в отдельный пул потоков, а результат
 * забирается через [java.util.concurrent.Future.get] с ограничением времени.
 */
object ReverseDns {

    private const val TIMEOUT_MS = 1200L

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "reverse-dns").apply { isDaemon = true }
    }

    suspend fun lookup(ip: String, timeoutMs: Long = TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        val value = ip.toIpv4OrNull() ?: return@withContext null
        val bytes = byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )
        val future = executor.submit(
            Callable {
                try {
                    InetAddress.getByAddress(bytes).canonicalHostName
                } catch (_: Exception) {
                    null
                }
            }
        )
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
                ?.trim()
                ?.trimEnd('.')
                ?.takeIf { it.isNotEmpty() && it != ip }
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }
}
