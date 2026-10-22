package local.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Local metadata only: never includes utterances, contact names, screen text or tokens. */
class SessionDiagnostics(private val context: Context, requireHeadset: Boolean) {
    enum class Phase { STARTING, ROUTING, LOADING, LISTENING, THINKING, SERVER, CONTACTS, ACTION, SPEAKING, STOPPED }
    private val began = SystemClock.elapsedRealtime()
    private val events = JSONArray()
    private val app = context.packageManager.getPackageInfo(context.packageName, 0)
    private val report = JSONObject().put("android", Build.VERSION.RELEASE)
        .put("appVersionName", app.versionName).put("appVersionCode", app.longVersionCode)
        .put("startedAtEpochMs", System.currentTimeMillis())
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("headsetRequired", requireHeadset)
        .put("speechEngine", SpeechEngines.selected(context).id)
    var phase = Phase.STARTING
        private set
    fun phase(value: Phase) {
        phase = value
        if (events.length() >= 32) events.remove(0)
        events.put(JSONObject().put("ms", SystemClock.elapsedRealtime() - began).put("phase", value.name))
    }
    private var rawPeak = 0
    fun tool(name: String, operation: String, state: String) {
        if (events.length() >= 32) events.remove(0)
        events.put(JSONObject().put("ms", SystemClock.elapsedRealtime() - began)
            .put("tool", name).put("operation", operation).put("state", state))
    }
    private var cleanPeak = 0
    private var silenced = false
    fun capture(source: Int, inputType: Int, samples: Long = 0, nonzero: Long = 0) {
        report.put("captureSource", source).put("inputDeviceType", inputType)
            .put("rawInputSamples", samples).put("nonzeroInputSamples", nonzero)
    }
    fun audio(raw: Int, clean: Int, muted: Boolean) {
        rawPeak = maxOf(rawPeak, raw); cleanPeak = maxOf(cleanPeak, clean); silenced = silenced || muted
    }
    fun finish(reason: String) {
        report.put("microphonePeakRms", rawPeak).put("recognizerPeakRms", cleanPeak).put("androidSilencedInput", silenced)
        phase(Phase.STOPPED)
        report.put("durationMs", SystemClock.elapsedRealtime() - began).put("reason", reason)
            .put("events", events).put("communicationType", context.getSystemService(AudioManager::class.java).communicationDevice?.type ?: -1)
        context.getSharedPreferences("diagnostics", 0).edit().putString("last_session", report.toString(2)).apply()
    }
    companion object {
        fun last(context: Context): String = context.getSharedPreferences("diagnostics", 0).getString("last_session", null)
            ?: "Завершённых голосовых сессий ещё нет."
    }
}
