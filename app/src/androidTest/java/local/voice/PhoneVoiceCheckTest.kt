package local.voice

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhoneVoiceCheckTest {
    @Test fun defaultSessionUsesPhoneWhenNoHeadsetAndCanAnswer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1000)
        val audio = context.getSystemService(AudioManager::class.java)
        assertTrue("This test requires an emulator without a headset", AudioRoute.headsets(context).isEmpty())
        val preferences = context.getSharedPreferences("settings", 0)
        val hadSetting = preferences.contains("headset_only")
        val previous = preferences.getBoolean("headset_only", false)
        preferences.edit().putBoolean("headset_only", true).commit()
        val beforeMode = audio.mode
        var conversation: VoiceConversation? = null
        val listening = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val levels = CountDownLatch(1)
        val releasedScreen = java.util.concurrent.atomic.AtomicInteger()
        var count = 0
        var status = ""
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { text ->
                status = text
                if (text == "Слушаю…") { if (++count == 1) listening.countDown() else resumed.countDown() }
            }, {}, microphoneLevel = { _, _ -> levels.countDown() }, releaseScreen = { releasedScreen.incrementAndGet() }).also { it.start() }
        }
        try {
            assertTrue("Microphone did not start: $status", listening.await(30, TimeUnit.SECONDS))
            assertTrue("No microphone level callback", levels.await(5, TimeUnit.SECONDS))
            val recording = audio.activeRecordingConfigurations.single()
            assertEquals(MediaRecorder.AudioSource.MIC, recording.clientAudioSource)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, recording.audioDevice!!.type)
            assertEquals("Phone check must not alter the headset call channel", beforeMode, audio.mode)
            assertFalse(recording.isClientSilenced)
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            val until = System.currentTimeMillis() + 30000
            while (conversation!!.speechFrames == 0L && System.currentTimeMillis() < until) Thread.sleep(50)
            assertEquals("Answer must play on the built-in speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, conversation!!.speechOutputDeviceType)
            assertTrue("Speech did not finish and resume listening: $status", resumed.await(30, TimeUnit.SECONDS))
            assertEquals("Ordinary voice conversation must keep the status screen visible", 0, releasedScreen.get())
            instrumentation.runOnMainSync { conversation!!.respond("прочитай экран") }
            assertEquals("Screen tools must release the assistant overlay", 1, releasedScreen.get())
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val restore = preferences.edit()
            if (hadSetting) restore.putBoolean("headset_only", previous) else restore.remove("headset_only")
            restore.commit()
        }
        Thread.sleep(500)
        assertTrue("Microphone was not released", audio.activeRecordingConfigurations.isEmpty())
        assertEquals(beforeMode, audio.mode)
    }
}
