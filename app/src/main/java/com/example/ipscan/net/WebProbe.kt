package com.example.ipscan.net

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что удалось узнать из веб-интерфейса устройства. */
data class WebInfo(val title: String?, val server: String?)

/**
 * Чтение заголовка страницы локального устройства.
 *
 * Роутеры, камеры, NAS и принтеры почти всегда отдают веб-интерфейс. Заголовок
 * (`<title>`) и HTTP-заголовок `Server` дают имя и подсказку о типе устройства
 * даже без UPnP.
 */
object WebProbe {

    private const val MAX_BYTES = 24 * 1024
    private const val CONNECT_TIMEOUT_MS = 700
    private const val READ_TIMEOUT_MS = 900
    private const val MAX_TITLE_LENGTH = 80

    private val TITLE_REGEX = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val WHITESPACE = Regex("\\s+")

    /** Заголовки, которые ничего не говорят об устройстве. */
    private val GENERIC_TITLES = listOf(
        "welcome to nginx",
        "apache2 default page",
        "apache2 ubuntu default page",
        "iis windows server",
        "index of /",
        "login",
        "log in",
        "sign in",
        "router",
        "web interface",
        "webui",
        "administration",
        "admin",
        "home",
        "home page",
        "document",
        "document moved",
        "opening",
        "loading",
        "please wait",
        "redirecting",
        "untitled",
        "default",
        "index",
    )

    /**
     * Заголовок бесполезен как имя устройства: «Opening…», «Login», адрес и т.п.
     * Такие заголовки всё ещё помогают угадать тип, но не подписывают карточку.
     */
    fun isGenericTitle(title: String): Boolean {
        val value = title.trim().lowercase().trim(' ', '.', ',', '-', ':', '/', '|', '…', '!', '?')
        if (value.isEmpty()) return true
        if (value.toIpv4OrNull() != null) return true
        return GENERIC_TITLES.any { value == it || value.startsWith("$it ") }
    }

    suspend fun probe(ip: String, port: Int, secure: Boolean): WebInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${if (secure) "https" else "http"}://$ip:$port/")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) IPScan/1.1")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Connection", "close")
            if (secure) {
                (connection as HttpsURLConnection).apply {
                    sslSocketFactory = trustAllFactory()
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            }
            val code = connection.responseCode
            val server = connection.getHeaderField("Server")?.trim()?.takeIf { it.isNotEmpty() }
            if (code !in 200..399) return@withContext server?.let { WebInfo(null, it) }
            val body = connection.inputStream.use { readLimited(it) }
            WebInfo(title = parseTitle(body), server = server)
        } catch (_: Exception) {
            null
        }
    }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < MAX_BYTES) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseTitle(body: String): String? {
        val raw = TITLE_REGEX.find(body)?.groupValues?.get(1) ?: return null
        val text = unescape(raw).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        return text.take(MAX_TITLE_LENGTH)
    }

    private fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * Локальные устройства часто используют самоподписанные сертификаты,
     * поэтому проверка цепочки и имени хоста здесь намеренно отключена.
     * Соединение идёт только с адресом из локальной сети.
     */
    @SuppressLint("TrustAllX509TrustManager", "BadHostnameVerifier", "CustomX509TrustManager")
    private fun trustAllFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        return context.socketFactory
    }
}
