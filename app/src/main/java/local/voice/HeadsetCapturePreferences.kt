package local.voice

import android.content.Context
import java.security.MessageDigest

/** Calibration applies only to the exact headset, never to another input or the phone. */
internal object HeadsetCapturePreferences {
    private fun key(headsetKey: String) = MessageDigest.getInstance("SHA-256")
        .digest(headsetKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun source(context: Context, headsetKey: String): Int? {
        val prefs = context.getSharedPreferences("audio_routes", 0)
        val source = prefs.getInt(key(headsetKey), -1)
        return source.takeIf { it in CaptureSourcePolicy.probeSources }
    }
    fun save(context: Context, headsetKey: String, measurements: List<CaptureSourcePolicy.Measurement>): Int? {
        val source = CaptureSourcePolicy.recommend(measurements)
        val editor = context.getSharedPreferences("audio_routes", 0).edit()
        if (source == null) editor.remove(key(headsetKey)) else editor.putInt(key(headsetKey), source)
        editor.apply()
        return source
    }
}
