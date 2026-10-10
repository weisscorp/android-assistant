package local.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BackendClient {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("assistant-backend", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assistant-backend", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(context: Context, endpoint: String, token: String, certificateFingerprint: String = "") {
        val value = endpoint.trim().trimEnd('/')
        val pin = if (value.isEmpty()) "" else BackendTls.validate(value, certificateFingerprint)
        val prefs = context.getSharedPreferences("settings", 0)
        val oldPin = prefs.getString("backend_certificate", "") ?: ""
        require(value.isBlank() || value == prefs.getString("backend", "") || token.isNotBlank()) { "При смене сервера введите его токен." }
        require(!token.contains('\r') && !token.contains('\n')) { "Токен должен занимать одну строку." }
        val edit = prefs.edit().putString("backend", value).putString("backend_certificate", pin)
        if (token.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            edit.putString("token", Base64.encodeToString(cipher.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
                .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        if (value.isBlank()) edit.remove("token").remove("token_iv").remove("backend_certificate")
        check(edit.commit())
        if (value != prefs.getString("home_previous_endpoint", "") || token.isNotBlank() || pin != oldPin) {
            prefs.edit().putString("home_previous_endpoint", value).commit()
            HomeLink.changedServer(context)
        } else HomeLink.kick(context)
    }
    fun endpoint(context: Context) = context.getSharedPreferences("settings", 0).getString("backend", "") ?: ""
    fun certificateFingerprint(context: Context) = context.getSharedPreferences("settings", 0).getString("backend_certificate", "") ?: ""
    fun open(context: Context, path: String): HttpsURLConnection {
        // One preferences snapshot binds endpoint, identity and credential to
        // the same saved configuration while settings change concurrently.
        val settings = context.getSharedPreferences("settings", 0).all
        val endpoint = settings["backend"] as? String ?: ""
        check(endpoint.isNotBlank()) { "Настройте домашний сервер." }
        val encrypted = settings["token"] as? String ?: error("Настройте токен сервера.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(settings["token_iv"] as? String ?: "", Base64.NO_WRAP))) }
        val token = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return (URL(endpoint + path).openConnection() as HttpsURLConnection).apply {
            BackendTls.configure(this, settings["backend_certificate"] as? String ?: "")
            connectTimeout = 8_000; readTimeout = 30_000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    }
    class Request {
        internal val trace = InteractionJournal.current
        internal var traceCall: InteractionJournal.Call? = null
        private val cancelled = AtomicBoolean()
        private val connection = AtomicReference<HttpsURLConnection?>()
        fun cancel() { traceCall?.event("cancelled"); cancelled.set(true); connection.getAndSet(null)?.disconnect() }
        internal fun attach(value: HttpsURLConnection) {
            connection.set(value)
            if (cancelled.get()) { connection.getAndSet(null)?.disconnect(); error("Cancelled") }
        }
        internal fun detach() { connection.set(null) }
        internal fun ensureActive() { check(!cancelled.get()) { "Запрос отменён." } }
    }
    fun exchange(context: Context, method: String, path: String, body: JSONObject?, request: Request): Pair<Int, JSONObject> {
        require(method in setOf("POST", "GET", "DELETE"))
        require(path in setOf("/jobs", "/conversation") || path.matches(Regex("/jobs/[0-9a-f-]{36}")))
        val trace = request.trace?.begin("server", InteractionJournal.data("method" to method, "path" to path, "body" to body))
        request.traceCall = trace
        val connection = try { open(context, path) } catch (error: Exception) { trace?.error(error); throw error }
        try {
            request.attach(connection)
            connection.readTimeout = if (path == "/conversation") 40_000 else 8_000
            connection.requestMethod = method
            if (path == "/conversation" && HomeLink.enabled(context)) connection.setRequestProperty("X-Assistant-Device", HomeLink.device(context))
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(32_769) } ?: ByteArray(0)
            trace?.event("response", InteractionJournal.data("http_status" to code, "body" to bytes.toString(Charsets.UTF_8)))
            check(bytes.size <= 32_768)
            val json = if (bytes.isEmpty()) JSONObject() else if (code in 200..299) JSONObject(bytes.toString(Charsets.UTF_8))
                else runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { JSONObject().put("error", "invalid_response") }
            trace?.result(InteractionJournal.data("http_status" to code, "body" to json))
            return code to json
        } catch (error: Exception) { trace?.error(error); throw error }
        finally { request.traceCall = null; request.detach(); connection.disconnect() }
    }
    internal fun link(context: Context, method: String, path: String, body: ByteArray?, request: Request): Pair<Int, JSONObject> {
        require(method in setOf("POST", "GET"))
        require(path.matches(Regex("/link/[0-9a-f-]{36}/(?:knowledge|ack|image/[0-9a-f]{64}|events(?:\\?after=[0-9]+&wait=[0-9]+)?)")))
        require(body == null || body.size <= if ("/image/" in path) 10_485_760 else 4_194_304)
        request.ensureActive()
        val connection = open(context, path)
        try {
            request.attach(connection); connection.requestMethod = method
            connection.setRequestProperty("X-Assistant-Version-Code", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString())
            connection.readTimeout = 30_000
            if (body != null) {
                connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", if ("/image/" in path) "application/octet-stream" else "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(196_609) } ?: ByteArray(0)
            check(bytes.size <= 196_608)
            request.ensureActive()
            return code to if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { request.detach(); connection.disconnect() }
    }

}
