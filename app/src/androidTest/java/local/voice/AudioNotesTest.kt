package local.voice

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AudioNotesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun independentNotesSurviveReopenRecoveryAndServerCacheIsolation() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val store = AudioNotes(context)
        val first = store.create(); val second = store.create()
        try {
            for (note in listOf(first,second)) store.audio(note.getString("id")).writeBytes(AudioNotes.wavHeader(0)+ByteArray(32000))
            store.finalize(first.getString("id")); store.recover()
            store.update(first.getString("id")) { it.put("text","Синтетическая заметка про поезд").put("status","ready") }
            assertEquals(1000L,AudioNotes(context).local().first { it.getString("id")==first.getString("id") }.getLong("durationMs"))
            assertTrue(store.summary("поезд").contains("Синтетическая заметка"))
            assertEquals("pending",store.local().first { it.getString("id")==second.getString("id") }.getString("status"))
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            for (note in listOf(first,second)) { val id=note.getString("id");store.audio(id).delete();File(context.filesDir,"audio-notes/$id.json").delete() }
        }
    }
    @Test fun explicitRecordingSavesOriginalAndReleasesMicrophone() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val store=AudioNotes(context);val before=store.local().map { it.getString("id") }.toSet()
        val listening=java.util.concurrent.CountDownLatch(1);val finished=java.util.concurrent.CountDownLatch(1)
        lateinit var capture: AudioNoteCapture
        instrumentation.runOnMainSync {
            capture=AudioNoteCapture(context) { if(it.startsWith("Запись идёт"))listening.countDown() }
            capture.finished={finished.countDown()};capture.start()
        }
        try {
            assertTrue(listening.await(15,java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(1300)
            instrumentation.runOnMainSync { capture.stop() }
            assertTrue(finished.await(15,java.util.concurrent.TimeUnit.SECONDS))
            val saved=store.local().first { it.getString("id") !in before }
            assertTrue(saved.toString(),saved.getLong("durationMs")>=1000)
            assertTrue(store.audio(saved.getString("id")).length()>32044)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { capture.stop() }
            val deadline=android.os.SystemClock.elapsedRealtime()+30000
            while(store.local().any { it.getString("id") !in before && it.getString("status")=="pending" } && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(200)
            store.local().filter { it.getString("id") !in before }.forEach { val id=it.getString("id");store.audio(id).delete();File(context.filesDir,"audio-notes/$id.json").delete() }
        }
    }
    @Test fun libraryOpensWithoutCapturingMicrophoneAndLocalAsrTranscribesSavedAudio() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val activity=instrumentation.startActivitySync(Intent(context,AudioNotesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        val store=AudioNotes(context);val note=store.create();val id=note.getString("id")
        try {
            // Generate synthetic speech without playback, then feed the actual durable WAV to ASR.
            val voice=SileroVoice(context,"baya")
            val chunks=mutableListOf<FloatArray>()
            try { voice.generate("Купить молоко завтра.",{true}) { samples -> chunks.add(samples.copyOf());true } } finally {voice.close()}
            val pcm=chunks.flatMap { it.toList() }
            // Silero outputs 48kHz; resample to the notes' 16kHz PCM format.
            val bytes=ByteArray((pcm.size/3)*2)
            for(i in 0 until bytes.size/2) {
                val sample=(pcm[minOf(pcm.lastIndex,i*3)]*32767).toInt().coerceIn(-32768,32767)
                bytes[i*2]=sample.toByte();bytes[i*2+1]=(sample shr 8).toByte()
            }
            store.audio(id).writeBytes(AudioNotes.wavHeader(bytes.size)+bytes);store.finalize(id)
            AudioNoteTranscriber.enqueue(context,id)
            val until=android.os.SystemClock.elapsedRealtime()+90000
            var saved=note
            do { Thread.sleep(200);saved=store.local().first { it.getString("id")==id } } while(saved.getString("status")=="pending" && android.os.SystemClock.elapsedRealtime()<until)
            assertEquals(saved.toString(),"ready",saved.getString("status"))
            assertTrue(saved.toString(),saved.getString("text").contains("молоко"))
            val settings=context.getSharedPreferences("settings",0);val oldTone=settings.getBoolean("tone_asr",false)
            try {
                settings.edit().putBoolean("tone_asr",true).commit()
                store.update(id) { it.put("status","pending") }
                AudioNoteTranscriber.enqueue(context,id)
                val toneUntil=android.os.SystemClock.elapsedRealtime()+90000
                do { Thread.sleep(200);saved=store.local().first { it.getString("id")==id } } while(saved.getString("status")=="pending" && android.os.SystemClock.elapsedRealtime()<toneUntil)
                assertEquals(saved.toString(),"ready",saved.getString("status"))
                assertTrue(saved.toString(),saved.getString("text").contains("молоко"))
            } finally { settings.edit().putBoolean("tone_asr",oldTone).commit() }
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            File(context.cacheDir,"audio-notes-asr.json").writeText(saved.toString(2))
        } finally { instrumentation.runOnMainSync {activity.finish()};store.audio(id).delete();File(context.filesDir,"audio-notes/$id.json").delete() }
    }
}
