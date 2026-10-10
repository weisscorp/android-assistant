package local.voice

import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

class BackendTlsTest {
    @Test fun ownerPinRequiresCanonicalLocalIpAndHttps() {
        val pin = "a1".repeat(32)
        for (host in listOf("192.168.50.20", "10.0.2.2", "172.16.0.1", "172.31.255.255", "127.0.0.1", "[fd00::1]", "[::1]")) {
            assertEquals(host, pin, BackendTls.validate("https://$host:8765", pin))
        }
        for (host in listOf("public.example", "192.168.50.20.example", "localhost", "8.8.8.8", "172.15.0.1", "172.32.0.1",
            "169.254.1.1", "0.0.0.0", "224.0.0.1", "192.168.050.020", "[2001:db8::1]", "[fe80::1]", "[::ffff:127.0.0.1]")) {
            assertTrue(host, runCatching { BackendTls.validate("https://$host:8765", pin) }.isFailure)
        }
        for (url in listOf("http://192.168.50.20:8765", "https://user:secret@192.168.50.20", "https://192.168.50.20?token=secret",
            "https://192.168.50.20#fragment", "https://192.168.50.20:0", "https://192.168.50.20:65536", "https://192.168.50.20/a b")) {
            assertTrue(url, runCatching { BackendTls.validate(url, pin) }.isFailure)
        }
        assertEquals("", BackendTls.validate("https://ordinary.example/path", ""))
        assertEquals(pin, BackendTls.normalize(pin.chunked(2).joinToString(":").uppercase()))
        assertTrue(runCatching { BackendTls.normalize("a".repeat(63)) }.isFailure)
        assertTrue(runCatching { BackendTls.normalize("g".repeat(64)) }.isFailure)
    }

    private fun fixture(san: String, expired: Boolean = false, test: (String, X509Certificate, AtomicInteger) -> Unit) {
        val folder = Files.createTempDirectory("assistant-tls-test-").toFile()
        val password = "only-synthetic-fixture"
        val storeFile = folder.resolve("fixture.p12")
        var server: SSLServerSocket? = null
        var serving: Thread? = null
        try {
            val args = mutableListOf(requireNotNull(System.getProperty("java.home")) + "/bin/keytool", "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-keystore", storeFile.absolutePath, "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password, "-dname", "CN=Assistant synthetic fixture",
                "-validity", if (expired) "1" else "365", "-ext", "SAN=$san", "-ext", "EKU=serverAuth", "-noprompt")
            if (expired) args.addAll(listOf("-startdate", "-10d"))
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            assertTrue("keytool did not finish", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password.toCharArray()) } }
            val certificate = store.getCertificate("fixture") as X509Certificate
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password.toCharArray()) }
            val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            val received = AtomicInteger()
            val listener = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
            server = listener
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            serving = Thread {
                while (!listener.isClosed) {
                    try {
                        (listener.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 3000
                            socket.startHandshake()
                            val reader = socket.inputStream.bufferedReader()
                            val first = reader.readLine()
                            if (first != null) {
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) line = reader.readLine()
                                if (line != null) {
                                    received.incrementAndGet()
                                    socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nfixture reply".toByteArray())
                                    socket.outputStream.flush()
                                }
                            }
                        }
                    } catch (_: Exception) { /* Expected rejected TLS handshakes. */ }
                }
            }.apply { isDaemon = true; start() }
            test("https://127.0.0.1:${listener.localPort}", certificate, received)
        } finally {
            server?.close()
            serving?.join(3000)
            folder.deleteRecursively()
        }
    }

    private fun request(endpoint: String, pin: String): String {
        val connection = URL("$endpoint/link/events?after=0&wait=1").openConnection() as HttpsURLConnection
        val verifier = connection.hostnameVerifier
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
            BackendTls.configure(connection, pin)
            assertSame("Host verification must remain enabled", verifier, connection.hostnameVerifier)
            connection.setRequestProperty("Authorization", "Bearer synthetic-test-only")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    @Test fun exactCertificateWorksWithoutGlobalTrustAndRotationFailsBeforeHttp() = fixture("ip:127.0.0.1") { endpoint, certificate, received ->
        val globalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val pin = BackendTls.fingerprint(certificate)
        assertTrue("System trust must reject the unknown certificate", runCatching { request(endpoint, "") }.isFailure)
        assertEquals("fixture reply", request(endpoint, pin))
        val wrong = (if (pin.first() == '0') "1" else "0") + pin.drop(1)
        assertTrue("Another certificate must not receive HTTP credentials", runCatching { request(endpoint, wrong) }.isFailure)
        assertEquals(1, received.get())
        assertSame(globalTls, HttpsURLConnection.getDefaultSSLSocketFactory())
    }

    @Test fun correctPinDoesNotOverrideHostname() = fixture("dns:localhost") { endpoint, certificate, received ->
        assertTrue(runCatching { request(endpoint, BackendTls.fingerprint(certificate)) }.isFailure)
        assertEquals(0, received.get())
    }

    @Test fun correctPinDoesNotAcceptExpiredCertificate() = fixture("ip:127.0.0.1", true) { endpoint, certificate, received ->
        assertTrue(runCatching { request(endpoint, BackendTls.fingerprint(certificate)) }.isFailure)
        assertEquals(0, received.get())
    }
}
