package local.voice

/** Source numbers are Android's public AudioSource constants, not device/model identifiers. */
internal object CaptureSourcePolicy {
    const val MIC = 1
    const val VOICE_RECOGNITION = 6
    const val VOICE_COMMUNICATION = 7
    val probeSources = listOf(VOICE_COMMUNICATION, MIC, VOICE_RECOGNITION)
    data class Measurement(val source: Int, val samples: Long, val nonzero: Long, val peakRms: Int, val silenced: Boolean)

    fun select(phone: Boolean, bluetooth: Boolean, calibrated: Int?): Int = when {
        phone -> MIC
        bluetooth && calibrated in probeSources -> calibrated!!
        else -> VOICE_COMMUNICATION
    }

    // The first second of routing transients is excluded by the probe. A transient click
    // or an Android-silenced stream must never become a saved microphone configuration.
    fun recommend(measurements: List<Measurement>): Int? = probeSources.firstOrNull { source ->
        measurements.any { it.source == source && !it.silenced && it.samples >= 16000 &&
            it.nonzero in 1..it.samples && it.nonzero.toDouble() / it.samples >= 0.05 && it.peakRms >= 8 }
    }
}

/** Detect digital-zero transport, not quiet speech or a pause after receiving sound. */
internal class ZeroInputWatchdog(private val startedMs: Long) {
    private var receivedSound = false
    fun failed(nowMs: Long, pcm: ShortArray, silenced: Boolean): Boolean {
        if (pcm.any { it.toInt() != 0 }) receivedSound = true
        return !receivedSound && !silenced && nowMs - startedMs >= 4000
    }
}
