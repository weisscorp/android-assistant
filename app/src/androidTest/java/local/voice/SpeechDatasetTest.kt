package local.voice

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SpeechDatasetTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun recordingCorrectionsAndDeletionRemainSeparateFromOrdinaryNotes() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val store=SpeechDataset(context);val old=store.mode();val before=AudioNotes(context).local().size
        val buffer=SpeechSampleBuffer();repeat(100){buffer.append(ShortArray(160){1000},ShortArray(160){500},false)}
        var id:String?=null
        try {
            store.setMode("off");assertNull(store.save(buffer,"Синтетическая фраза","vosk",store.session(),1,1))
            store.setMode("corrected");id=store.save(buffer,"кофе назад","vosk",store.session(),1,1)!!
            assertTrue(store.list().none {it.getString("id")==id})
            assertFalse(store.read(id).getBoolean("reviewed"))
            store.correct(id,"кофе на завтра")
            assertEquals("кофе на завтра",store.read(id).getString("corrected"));assertTrue(store.read(id).getBoolean("reviewed"))
            assertTrue(store.audio(id,false).exists());assertTrue(store.audio(id,true).exists())
            assertEquals(before,AudioNotes(context).local().size)
            store.delete(id);assertFalse(store.audio(id,false).exists());assertTrue(store.read(id).getBoolean("deleted"))
        } finally {
            store.setMode(old)
            id?.let {listOf(".json","-raw.wav","-clean.wav").forEach {suffix->File(context.filesDir,"speech-dataset/$it$suffix").delete();File(context.cacheDir,"speech-candidates/$it$suffix").delete()}}
        }
    }
    @Test fun longDictationSurvivesHiddenActivityAndSavesWithoutLocalRecognition() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val store=SpeechDataset(context);val before=store.list().map {it.getString("id")}.toSet()
        val activity=instrumentation.startActivitySync(Intent(context,SpeechDatasetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {activity.startForegroundService(Intent(context,SpeechDatasetRecorder::class.java))}
            val started=android.os.SystemClock.elapsedRealtime()+10000
            while(!SpeechDatasetRecorder.recording && android.os.SystemClock.elapsedRealtime()<started)Thread.sleep(100)
            assertTrue(SpeechDatasetRecorder.message,SpeechDatasetRecorder.recording)
            Thread.sleep(500);instrumentation.runOnMainSync {activity.finish()};Thread.sleep(1800)
            assertTrue("Recording stopped with UI hidden",SpeechDatasetRecorder.recording)
            context.startService(Intent(context,SpeechDatasetRecorder::class.java).setAction("stop"))
            val until=android.os.SystemClock.elapsedRealtime()+10000
            while(SpeechDatasetRecorder.recording && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            assertFalse(SpeechDatasetRecorder.recording)
            assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            val sample=store.list().single {it.getString("id") !in before}
            assertEquals("recording",sample.getString("engine"));assertEquals("",sample.getString("recognized"))
            assertTrue(sample.toString(),sample.getLong("durationMs")>1000);assertTrue(store.audio(sample.getString("id"),false).exists())
            assertEquals(sample.getString("rawSha256"),sample.getString("cleanSha256"))
        } finally {
            context.startService(Intent(context,SpeechDatasetRecorder::class.java).setAction("stop"))
            store.list().filter {it.getString("id") !in before}.forEach {n->val id=n.getString("id");store.delete(id);File(context.filesDir,"speech-dataset/$id.json").delete()}
            instrumentation.runOnMainSync {if(!activity.isDestroyed)activity.finish()}
        }
    }
}
