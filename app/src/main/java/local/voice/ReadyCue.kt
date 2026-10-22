package local.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.sin

/** Three notes marking the start or end of listening, pinned to the conversation output. */
internal object ReadyCue {
    private const val VOLUME_KEY = "cue_volume_percent"
    private const val DEFAULT_VOLUME_PERCENT = 10

    fun volumePercent(context: Context): Int =
        context.getSharedPreferences("settings", 0).getInt(VOLUME_KEY, DEFAULT_VOLUME_PERCENT).coerceIn(0, 100)

    fun setVolumePercent(context: Context, percent: Int) {
        context.getSharedPreferences("settings", 0).edit().putInt(VOLUME_KEY, percent.coerceIn(0, 100)).apply()
    }

    internal fun samples(descending: Boolean = false): ShortArray {
        val frequencies = if (descending) doubleArrayOf(1175.0, 880.0, 660.0) else doubleArrayOf(660.0, 880.0, 1175.0)
        return ShortArray(3520) { i ->
            val note = i / 1280
            val local = i % 1280
            if (local !in 0 until 960) 0 else {
                val envelope = minOf(1.0, local / 80.0, (959 - local) / 80.0)
                (5500 * envelope * sin(2 * Math.PI * frequencies[note] * local / 16000)).toInt().toShort()
            }
        }
    }

    fun play(context: Context, phoneSpeaker: Boolean, descending: Boolean = false, reference: PlaybackReference? = null, done: (String?) -> Unit = {}) {
        var track: AudioTrack? = null
        runCatching {
            val volume = volumePercent(context)
            val rate = 16000
            val pcm = samples(descending)
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phoneSpeaker) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = output
            check(output.state != AudioTrack.STATE_UNINITIALIZED)
            val manager = context.getSystemService(AudioManager::class.java)
            val device = if (phoneSpeaker) manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else manager.communicationDevice
            check(device != null && output.setPreferredDevice(device)) { "Readiness output unavailable" }
            check(output.write(pcm, 0, pcm.size) == pcm.size)
            check(output.state == AudioTrack.STATE_INITIALIZED)
            check(output.setVolume(volume / 100f) == AudioTrack.SUCCESS)
            reference?.begin(rate)
            reference?.append(FloatArray(pcm.size) { pcm[it] / 32768f * volume / 100f }, 0, pcm.size, 0, 0, System.nanoTime())
            output.play()
            val main = Handler(Looper.getMainLooper())
            val deadline = SystemClock.elapsedRealtime() + 2000
            val drain = object : Runnable {
                override fun run() {
                    val played = runCatching { output.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
                    val timestamp = android.media.AudioTimestamp()
                    if (output.getTimestamp(timestamp)) reference?.clock(timestamp.framePosition, timestamp.nanoTime)
                    else reference?.clock(played, System.nanoTime())
                    if (played >= pcm.size) {
                        Log.i("ReadyCue", "Played descending=$descending volumePercent=$volume frames=$played outputType=${output.routedDevice?.type}")
                        done(null)
                        // Keep the completed track alive through the Bluetooth transport tail.
                        main.postDelayed({ runCatching { output.release() } }, 200)
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        done("Сигнал не проигрался в выбранном звуковом канале.")
                        Log.w("ReadyCue", "Playback timed out; outputType=${output.routedDevice?.type}, frames=$played")
                        runCatching { output.release() }
                    } else main.postDelayed(this, 20)
                }
            }
            main.post(drain)
        }.onFailure {
            Log.w("ReadyCue", "Readiness playback failed", it)
            done("Не удалось проиграть сигнал готовности.")
            runCatching { track?.release() }
        }
    }
}
