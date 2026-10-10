package local.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** OTA credentials are separate from the conversation server and never bundled in an APK. */
internal object OtaClient {
    private fun prefs(context: Context) = context.getSharedPreferences("dev_ota", 0)
    fun endpoint(context: Context) = prefs(context).getString("endpoint", "").orEmpty()
    fun fingerprint(context: Context) = prefs(context).getString("certificate", "").orEmpty()
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false) && endpoint(context).isNotBlank()
    /** Optional one-time owner provisioning through app-private storage, never an exported intent. */
    fun consumeBootstrap(context: Context) {
        val file = java.io.File(context.filesDir, "dev-ota-setup.json")
        if (!file.isFile) return
        try {
            require(file.length() <= 4096)
            val json = org.json.JSONObject(file.readText())
            save(context, json.getString("endpoint"), json.getString("token"), json.optString("certificateSha256"), json.optBoolean("automatic", false))
        } catch (_: Exception) {
            prefs(context).edit().putString("status", "Не удалось импортировать dev OTA. Проверьте настройки сервера.").apply()
        } finally { file.delete() }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("assistant-ota", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assistant-ota", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(context: Context, endpoint: String, token: String, fingerprint: String, enabled: Boolean) {
        val value = endpoint.trim().trimEnd('/')
        val pin = if (value.isBlank()) "" else BackendTls.validate(value, fingerprint)
        val prefs = prefs(context)
        require(value.isBlank() || (value == endpoint(context) && prefs.contains("token")) || token.isNotBlank()) { "Введите ключ dev OTA." }
        require(!token.contains('\r') && !token.contains('\n')) { "Ключ должен занимать одну строку." }
        require(!enabled || value.isNotBlank()) { "Сначала задайте адрес dev OTA." }
        AutoUpdates.cancel(context)
        val edit = prefs.edit().putString("endpoint", value).putString("certificate", pin)
            .putBoolean("enabled", enabled).putLong("revision", prefs.getLong("revision", 0) + 1)
        if (token.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            edit.putString("token", Base64.encodeToString(cipher.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        if (value.isBlank()) edit.remove("token").remove("iv")
        check(edit.commit())
        AutoUpdates.schedule(context, immediate = enabled)
    }
    fun source(context: Context): String = if (endpoint(context).isBlank()) {
        // Binding a downloaded candidate to its saved server also protects manual OTA.
        "home:" + context.getSharedPreferences("settings", 0).all.let {
            MessageDigest.getInstance("SHA-256").digest(listOf(it["backend"], it["backend_certificate"], it["token"], it["token_iv"])
                .joinToString("|").toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }
    } else "dev:${prefs(context).getLong("revision", 0)}"

    fun connections(context: Context): (String) -> HttpsURLConnection {
        if (endpoint(context).isBlank()) return BackendClient.connections(context)
        val snapshot = prefs(context).all
        val endpoint = snapshot["endpoint"] as String
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(snapshot["iv"] as String, Base64.NO_WRAP)))
        }
        val token = cipher.doFinal(Base64.decode(snapshot["token"] as String, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return { path ->
            require(path == "/updates/manifest" || path.matches(Regex("/updates/[a-zA-Z0-9._-]+\\.apk")))
            (URL(endpoint + path).openConnection() as HttpsURLConnection).apply {
                BackendTls.configure(this, snapshot["certificate"] as String)
                connectTimeout = 8_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }
}
