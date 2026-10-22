package local.voice

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the real installed-label resolver and Android launch, with synthetic app UI. */
class SpotifyOpeningTest {
    @Test fun russianCommandsLaunchAnInstalledSpotifyLabelWithoutLlmInEveryBrainMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pm = context.packageManager
        val launcher = ComponentName("local.voice.fixture", "local.voice.fixture.SpotifyLauncher")
        val field = ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")
        val oldLauncher = pm.getComponentEnabledSetting(launcher)
        val oldField = pm.getComponentEnabledSetting(field)
        val prefs = context.getSharedPreferences("settings", 0)
        val oldMode = prefs.getString("brain_mode", null)
        val times = org.json.JSONArray()
        fun configure(enabled: Boolean) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.LauncherControlActivity"))
                .putExtra("mode", if (enabled) "spotify" else "restore_spotify")
                .putExtra("field_state", oldField).putExtra("spotify_state", oldLauncher)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val expected = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else oldLauncher
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until && pm.getComponentEnabledSetting(launcher) != expected) Thread.sleep(50)
            assertEquals("Fixture launcher configuration", expected, pm.getComponentEnabledSetting(launcher))
            val catalog = AppKnowledge(context)
            val catalogUntil = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < catalogUntil && (catalog.get("local.voice.fixture")?.label == "Spotify") != enabled) Thread.sleep(50)
            assertEquals("Package-change event updated the catalog", enabled, catalog.get("local.voice.fixture")?.label == "Spotify")
            instrumentation.waitForIdleSync()
        }
        fun foregroundIsFixture(): Boolean {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            return try { root.packageName == "local.voice.fixture" && root.findAccessibilityNodeInfosByText("Spotify launcher fixture").isNotEmpty() }
            finally { root.recycle() }
        }
        try {
            configure(true)
            for (mode in listOf("LOCAL", "AUTO", "HERMES")) {
                prefs.edit().putString("brain_mode", mode).commit()
                for (phrase in listOf("открой спотифай", "запусти приложение Spotify", "Открой Спотифай, пожалуйста!")) {
                    instrumentation.runOnMainSync {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    instrumentation.uiAutomation.waitForIdle(200, 5000)
                    val replies = LinkedBlockingQueue<String>()
                    val stats = LocalBrain.lastStats
                    var conversation: VoiceConversation? = null
                    val began = android.os.SystemClock.elapsedRealtime()
                    try {
                        instrumentation.runOnMainSync {
                            conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) })
                                .also { it.start(); it.respond(phrase) }
                        }
                        assertEquals("$mode / $phrase", "Открываю ${AppSpeechNames.opening(AppKnowledge(context).get("local.voice.fixture")!!.russian)}.", replies.poll(2, TimeUnit.SECONDS))
                        val until = System.currentTimeMillis() + 5000
                        while (System.currentTimeMillis() < until && !foregroundIsFixture()) Thread.sleep(50)
                        assertTrue("$mode / $phrase did not actually launch the fixture", foregroundIsFixture())
                        assertSame("Opening called LLM", stats, LocalBrain.lastStats)
                        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                        assertEquals(0L, conversation!!.speechFrames)
                        times.put(org.json.JSONObject().put("mode", mode).put("phrase", phrase)
                            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began))
                    } finally { instrumentation.runOnMainSync { conversation?.close() } }
                }
            }
            // The reported ASR corruption must ask, keep its panel, and launch only
            // after confirmation. Exercise the installed-app catalog, not a fake package.
            prefs.edit().putString("brain_mode", "LOCAL").commit()
            val answers = LinkedBlockingQueue<String>()
            var released = 0
            var uncertain: VoiceConversation? = null
            try {
                instrumentation.runOnMainSync {
                    uncertain = VoiceConversation(context, {}, {}, textOnly = true,
                        releaseScreen = { released++ }, replyObserver = { answers.offer(it) }).also { it.start() }
                    uncertain!!.respond("открой хшцщжхшцщж")
                }
                assertTrue(answers.poll(2, TimeUnit.SECONDS).orEmpty().startsWith("Не нашёл приложение"))
                assertEquals("Missing app closed the voice panel", 0, released)
                // Actual GigaAM v3 output for synthetic "Открой Спотифай".
                instrumentation.runOnMainSync { uncertain!!.respond("открой с патифой") }
                val gigaSuggestion = answers.poll(2, TimeUnit.SECONDS).orEmpty()
                assertTrue(gigaSuggestion, gigaSuggestion.contains("Spotify") && gigaSuggestion.contains("Не уверен"))
                assertEquals(0, released)
                instrumentation.runOnMainSync { uncertain!!.respond("отмена") }
                assertEquals("Отменено.", answers.poll(2, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { uncertain!!.respond("открой патифон") }
                val suggestion = answers.poll(2, TimeUnit.SECONDS).orEmpty()
                assertTrue(suggestion, suggestion.contains("Spotify"))
                assertTrue(suggestion, suggestion.contains("Не уверен"))
                assertEquals("A guessed app name closed the voice panel", 0, released)
                assertTrue(uncertain!!.listening)
                instrumentation.runOnMainSync { uncertain!!.respond("отмена") }
                assertEquals("Отменено.", answers.poll(2, TimeUnit.SECONDS))
                assertEquals("Cancellation closed the voice panel", 0, released)
                instrumentation.runOnMainSync { uncertain!!.respond("открой патифон") }
                answers.poll(2, TimeUnit.SECONDS)
                instrumentation.runOnMainSync { uncertain!!.respond("первый") }
                assertTrue(answers.poll(2, TimeUnit.SECONDS).orEmpty().startsWith("Открываю"))
                assertEquals("Confirmed app did not release panel", 1, released)
                val until = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until && !foregroundIsFixture()) Thread.sleep(50)
                assertTrue("Confirmed suggestion did not launch fixture", foregroundIsFixture())
            } finally { instrumentation.runOnMainSync { uncertain?.close() } }
            configure(false)
            instrumentation.runOnMainSync {
                val reply = DeviceTools(context).execute(VoiceCommands.parse("открой спотифай"))!!
                assertTrue(reply.text, reply.text.startsWith("Не нашёл приложение"))
                assertNull(reply.afterSpeech)
            }
            java.io.File(context.cacheDir, "spotify-opening.json").writeText(times.toString(2))
        } finally {
            configure(false)
            prefs.edit().apply { if (oldMode == null) remove("brain_mode") else putString("brain_mode", oldMode) }.commit()
        }
    }
}
