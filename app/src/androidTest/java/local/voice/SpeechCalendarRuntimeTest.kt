package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic PCM generation only. Never plays sound or opens a microphone. */
class SpeechCalendarRuntimeTest {
    @Test fun bothNativeVoicesReceiveCalendarWordsInsteadOfDecimalLiterals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = "Вы хотите установить будильник на 15.00 18 сентября?"
        val spoken = SpokenReply.text(raw)
        assertEquals("Вы хотите установить будильник на пятнадцать часов восемнадцатого сентября?",spoken)
        assertEquals(raw,SpokenReply.answer(raw))
        val silero = SileroVoice(context,"baya")
        try {
            assertEquals(spoken.lowercase(),silero.normalized(spoken))
            var samples = 0; var energy = 0.0
            silero.generate(spoken,{ true }) { frame ->
                assertTrue(frame.all { it.isFinite() }); samples += frame.size
                energy += frame.sumOf { it.toDouble()*it }; true
            }
            assertTrue(samples > 16000); assertTrue(energy > 1.0)
        } finally { silero.close() }
        val dir = AssetModels.unpack(context,"voice-ru.zip","voice-ru","piper-ruslan-int8-v1")
        val piper = OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(
            model=dir.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens=File(dir,"tokens.txt").absolutePath,dataDir=File(dir,"espeak-ng-data").absolutePath),numThreads=2)))
        try {
            val audio = piper.generate(spoken,sid=0,speed=1f)
            assertTrue(audio.samples.size > 16000)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.samples.sumOf { it.toDouble()*it } > 1.0)
        } finally { piper.release() }
    }
}
