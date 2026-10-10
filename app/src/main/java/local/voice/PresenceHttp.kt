package local.voice

import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/** Bounded user-requested reads, no cookies, authentication guessing or JavaScript execution. */
internal class PresenceHttp {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled = true; connection?.disconnect() }
    companion object {
        fun validUrl(value: String) = runCatching {
            val uri = URI(value)
            value.length <= 2000 && value.none { it.isISOControl() } && uri.scheme == "https" &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.port in -1..65535
        }.getOrDefault(false)
    }
    fun get(value: String): String {
        require(validUrl(value)) { "Для источника нужен HTTPS-адрес." }
        val origin = URI(value)
        var next = value
        repeat(3) {
            check(!cancelled) { "Запрос отменён." }
            val current = URL(next).openConnection() as HttpURLConnection
            connection = current
            current.connectTimeout = 5000; current.readTimeout = 5000; current.instanceFollowRedirects = false
            current.useCaches=false
            current.setRequestProperty("User-Agent", "LocalVoiceAssistant/0.30.7 Android saved-location-reader")
            current.setRequestProperty("Accept", "application/json,text/html;q=0.8")
            current.setRequestProperty("Cache-Control", "no-store")
            try {
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val uri = URI(next).resolve(current.getHeaderField("Location") ?: error("Нет адреса перенаправления."))
                    require(validUrl(uri.toString()) && uri.host == origin.host && uri.port == origin.port) { "Страница перенаправляет на другой сервер; сохраните её конечный адрес." }
                    next = uri.toString()
                } else {
                    check(code in 200..299) { if (code in setOf(401,403)) "Источник требует авторизацию; чтение через этот адрес недоступно." else "Источник недоступен (HTTP$code)." }
                    require(current.contentLengthLong <= 1048576) { "Источник слишком большой." }
                    val bytes = ByteArrayOutputStream()
                    current.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            check(!cancelled) { "Запрос отменён." }
                            val count = input.read(buffer); if (count < 0) break
                            require(bytes.size() + count <= 1048576) { "Источник слишком большой." }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return bytes.toString("UTF-8")
                }
            } finally { current.disconnect(); if (connection === current) connection = null }
        }
        error("Слишком много перенаправлений.")
    }
}
