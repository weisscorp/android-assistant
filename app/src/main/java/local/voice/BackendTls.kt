package local.voice

import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Owner-selected leaf certificate for a local IP. No automatic trust-on-first-use. */
object BackendTls {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun normalize(value: String): String {
        val result = value.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        require(result.isEmpty() || result.matches(Regex("[0-9a-f]{64}"))) {
            "Нужен SHA-256 отпечаток сертификата: 64 шестнадцатеричных знака."
        }
        return result
    }

    fun localIp(host: String): Boolean {
        val value = host.removeSurrounding("[", "]")
        if (value.matches(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) {
            val parts = value.split('.').map { it.toInt() }
            return parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 ||
                parts[0] == 172 && parts[1] in 16..31 || parts[0] == 192 && parts[1] == 168)
        }
        if (':' !in value || !value.matches(Regex("[0-9a-fA-F:]+"))) return false
        // Numeric IPv6 only: never resolve a hostname to decide whether it is local.
        val bytes = runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return false
        return bytes.size == 16 && ((bytes[0].toInt() and 254) == 252 ||
            bytes.dropLast(1).all { it.toInt() == 0 } && bytes.last().toInt() == 1)
    }

    fun validate(endpoint: String, certificateFingerprint: String): String {
        val url = URL(endpoint)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null &&
            url.query == null && url.ref == null && (url.port == -1 || url.port in 1..65535)) {
            "Нужен HTTPS-адрес без логина, query и fragment."
        }
        url.toURI() // Reject spaces and control characters before any connection.
        val pin = normalize(certificateFingerprint)
        require(pin.isEmpty() || localIp(url.host)) { "Отпечаток домашнего сертификата доступен только для локального IP." }
        return pin
    }

    fun trustManager(certificateFingerprint: String): X509TrustManager {
        val pin = normalize(certificateFingerprint)
        require(pin.isNotEmpty())
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not supported")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Server certificate missing")
                if (authType.isNullOrBlank()) throw CertificateException("Server authentication missing")
                leaf.checkValidity()
                if (fingerprint(leaf) != pin) throw CertificateException("Server certificate fingerprint changed")
            }
        }
    }

    fun configure(connection: HttpsURLConnection, certificateFingerprint: String) {
        val url = connection.url
        // Request query parameters (/link/.../events) are part of the protocol.
        // Validate the connection origin here, not the already validated route.
        val pin = validate("${url.protocol}://${url.authority}", certificateFingerprint)
        if (pin.isEmpty()) return // System trust remains the default.
        connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager(pin)), null)
        }.socketFactory
        // Keep HttpsURLConnection's hostname verifier. Certificate SAN must match the IP.
    }
}
