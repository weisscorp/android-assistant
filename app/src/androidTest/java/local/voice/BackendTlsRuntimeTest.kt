package local.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact owned emulator, synthetic loopback bridge, real Android TLS/Keystore/preferences. */
@RunWith(AndroidJUnit4::class)
class BackendTlsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(test: (Context, String, String) -> Unit) {
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("backend_endpoint") ?: error("Use the guarded emulator verifier")
        val pin = args.getString("backend_fingerprint") ?: error("Fixture fingerprint required")
        val prefs = context.getSharedPreferences("settings", 0)
        val original = prefs.all.toMap()
        try {
            prefs.edit().putBoolean("home_link_enabled", false).commit()
            test(context, endpoint, pin)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertNull(VoiceConversation.activePhase)
        } finally {
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(restore.commit())
        }
    }

    private fun read(context: Context) = BackendClient.exchange(context, "GET", "/jobs/${UUID.randomUUID()}", null, BackendClient.Request())

    @Test fun localCertificateIsPersistedAndUsedWithoutTrustingOtherCertificates() = fixture { context, endpoint, pin ->
        BackendClient.save(context, endpoint, "helmet-test-token")
        assertTrue("Unknown fixture CA must fail with system trust", runCatching { read(context) }.isFailure)
        BackendClient.save(context, endpoint, "", pin.chunked(2).joinToString(":").uppercase())
        assertEquals(pin, BackendClient.certificateFingerprint(context))
        assertNotEquals("helmet-test-token", context.getSharedPreferences("settings", 0).getString("token", null))
        assertEquals(404, read(context).first)
        val device = UUID.randomUUID().toString()
        val knowledge = JSONObject().put("version", 1).put("revision", 1).put("sources", JSONArray())
            .put("people", JSONArray()).put("note", JSONObject.NULL)
        assertEquals(200, BackendClient.link(context, "POST", "/link/$device/knowledge",
            knowledge.toString().toByteArray(), BackendClient.Request()).first)
        assertEquals("Long-poll query must retain the same TLS validation", 200,
            BackendClient.link(context, "GET", "/link/$device/events?after=0&wait=0", null, BackendClient.Request()).first)
        val wrong = (if (pin.first() == '0') "1" else "0") + pin.drop(1)
        BackendClient.save(context, endpoint, "", wrong)
        assertTrue("Wrong certificate must stop the request", runCatching { read(context) }.isFailure)
        BackendClient.save(context, endpoint, "wrong-token", pin)
        assertEquals("Pin does not replace Bearer authentication", 401, read(context).first)
        BackendClient.save(context, "", "", pin)
        assertEquals("", BackendClient.certificateFingerprint(context))
        assertNull(context.getSharedPreferences("settings", 0).getString("token", null))
    }

    @Test fun correctFingerprintStillRequiresCertificateForThatIp() = fixture { context, _, _ ->
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("backend_wrong_host_endpoint") ?: error("Wrong-host fixture required")
        val pin = args.getString("backend_wrong_host_fingerprint") ?: error("Wrong-host fingerprint required")
        BackendClient.save(context, endpoint, "helmet-test-token", pin)
        assertTrue("Matching cert hash must not bypass hostname verification", runCatching { read(context) }.isFailure)
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun settingsFieldSavesAndShowsTheOwnerSelectedFingerprint() = fixture { context, endpoint, pin ->
        fun launch() = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsSection.SERVER.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.settings_endpoint).setText(endpoint)
                activity.findViewById<EditText>(R.id.settings_token).setText("helmet-test-token")
                activity.findViewById<EditText>(R.id.settings_certificate).setText(pin.chunked(2).joinToString(":"))
                val save = views(activity.window.decorView).single { it.isClickable && (it as? TextView)?.text?.toString() == "Сохранить подключение" }
                assertTrue(save.performClick())
                assertEquals(pin, activity.findViewById<EditText>(R.id.settings_certificate).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.settings_token).text.toString())
            }
            assertEquals(endpoint, BackendClient.endpoint(context))
            assertEquals(pin, BackendClient.certificateFingerprint(context))
            assertEquals(404, read(context).first)
            // NEW_TASK may deliver to the already open Activity instead of
            // creating one; finish it before requiring a fresh onCreate read.
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val reopened = launch()
            try {
                instrumentation.runOnMainSync {
                    assertEquals(pin, reopened.findViewById<EditText>(R.id.settings_certificate).text.toString())
                    assertEquals(endpoint, reopened.findViewById<EditText>(R.id.settings_endpoint).text.toString())
                }
            } finally { instrumentation.runOnMainSync { reopened.finish() } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
