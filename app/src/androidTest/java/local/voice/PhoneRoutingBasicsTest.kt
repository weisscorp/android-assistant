package local.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Read-only app inventory and text sessions. No contact insertion, message, call or music playback. */
@RunWith(AndroidJUnit4::class)
class PhoneRoutingBasicsTest {
    @Test fun explicitHermesWithoutEndpointDoesNotPretendToConnectOrRunLocalInference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val endpoint = prefs.getString("backend", null)
        val mode = prefs.getString("brain_mode", null)
        val replies = LinkedBlockingQueue<String>()
        val stats = LocalBrain.lastStats
        var conversation: VoiceConversation? = null
        try {
            prefs.edit().remove("backend").putString("brain_mode", "AUTO").commit()
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }).also { it.start(); it.respond("свяжи с гермесом") } }
            assertTrue(replies.poll(2, TimeUnit.SECONDS)!!.contains("ещё не настроен"))
            instrumentation.runOnMainSync { conversation!!.respond("Расскажи о космосе") }
            assertTrue(replies.poll(2, TimeUnit.SECONDS)!!.contains("ещё не настроен"))
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            assertTrue(replies.poll(2, TimeUnit.SECONDS)!!.startsWith("Сейчас"))
            assertEquals(stats, LocalBrain.lastStats)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertEquals(0L, conversation!!.speechFrames)
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val restore = prefs.edit()
            if (endpoint == null) restore.remove("backend") else restore.putString("backend", endpoint)
            if (mode == null) restore.remove("brain_mode") else restore.putString("brain_mode", mode)
            restore.commit()
        }
    }
    @Test fun catalogUsesPhoneAppModulesAndDoesNotExportAddressBookOrTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = CapabilityCatalog.json(context)
        val exportedNames = (0 until capabilities.length()).map { capabilities.getJSONObject(it).getString("name") }
        assertEquals(setOf("time", "clock", "battery", "open", "call", "telegram", "navigate", "music", "media", "weather"), exportedNames.toSet())
        assertEquals(exportedNames.distinct().size, exportedNames.size)
        val serialized = capabilities.toString()
        assertFalse(serialized.contains("token_iv"))
        assertFalse(serialized.contains("lookup"))
        assertTrue(serialized.contains("navigate"))
        val names = MobileApps.navigators(context)
        assertFalse(names.any { it.pkg == "ru.yandex.taxi" })
        assertTrue(names.distinctBy { it.pkg }.size == names.size)
        if (MobileApps.spotifyInstalled(context)) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage("com.spotify.music")
            assertNotNull("Installed Spotify must resolve the advertised playback entry", intent.resolveActivity(context.packageManager))
        }
    }
}
