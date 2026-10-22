package local.voice

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AudioNotesSyncTest {
    @Test fun offlineNoteUploadsOriginalAndTranscriptAndReturnsInHubLibrary() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("audio_notes_endpoint") ?: error("Provide disposable TLS Hub endpoint")
        val pin=args.getString("audio_notes_pin")!!
        val prefs=context.getSharedPreferences("settings",0);val original=prefs.all.toMap()
        HomeLink.detach();prefs.edit().putBoolean("home_link_enabled",false).commit();HomeLink.kick(context)
        val store=AudioNotes(context);val note=store.create();val id=note.getString("id")
        try {
            store.audio(id).writeBytes(AudioNotes.wavHeader(32000)+ByteArray(32000));store.finalize(id)
            store.update(id) { it.put("text","Синтетическая заметка о поездке").put("status","ready") }
            assertTrue(store.local().any { it.getString("id")==id })
            BackendClient.save(context,endpoint,"audio-notes-fixture-token-20261014-tests",pin)
            prefs.edit().putBoolean("home_link_enabled",true).commit()
            HomeLink.checkNow(context).get(30,TimeUnit.SECONDS)
            val device=HomeLink.device(context)
            fun page()=BackendClient.link(context,"GET","/link/$device/audio-notes?offset=0",null,BackendClient.Request()).second
            assertTrue(page().getJSONArray("items").toString().contains("Синтетическая заметка"))
            assertTrue(store.all().any { it.getString("id")==id })
            val sha=store.local().first { it.getString("id")==id }.getString("audioSha256")
            // Read original through TLS without decoding it as JSON.
            BackendClient.open(context,"/link/$device/audio/$sha").run {
                try { assertArrayEquals(store.audio(id).readBytes(),inputStream.use { it.readBytes() }) } finally {disconnect()}
            }
            val owner=store.owner()
            // Changing a token rotates the link identity, but not the original recording owner.
            BackendClient.save(context,endpoint,"audio-notes-fixture-token-20261014-tests",pin)
            assertNotEquals(device,HomeLink.device(context));assertEquals(owner,store.owner())
            HomeLink.checkNow(context).get(30,TimeUnit.SECONDS)
            assertEquals(1,page().getJSONArray("items").length())
            File(context.cacheDir,"audio-notes-sync.json").writeText(JSONObject().put("uploaded",true).put("originalVerified",true).put("deduplicated",true).toString())
        } finally {
            prefs.edit().putBoolean("home_link_enabled",false).commit();HomeLink.kick(context);HomeLink.detach()
            val edit=prefs.edit().clear()
            original.forEach { (key,value) -> when(value) { is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value) } }
            edit.commit();store.audio(id).delete();File(context.filesDir,"audio-notes/$id.json").delete()
        }
    }
}
