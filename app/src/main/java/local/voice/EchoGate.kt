package local.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Learn the acoustic delay from raw echo; do not match near speech against arbitrary past vowels. */
class EchoGate(private val reference: PlaybackReference) {
    private val history = ShortArray(3200)
    private val microphone = ShortArray(3200)
    private var used = 0
    private var lastCheck = 0L
    private var echoOnly = false
    private var acousticDelay: Int? = null
    private val past = ShortArray(3200 + 8000)
    private val fit = Array(17) { DoubleArray(18) }
    private val coefficients = DoubleArray(17)
    /** A short echo-path fit must explain essentially all raw energy before muting cleaned speech. */
    private fun rawEchoExplained(delay: Int): Boolean {
        val first = used - minOf(1600, used)
        val low = maxOf(0, delay - 64)
        val high = minOf(8000, delay + 64)
        val taps = (high - low) / 8 + 1
        for (row in 0 until taps) fit[row].fill(0.0)
        var energy = 0.0
        for (sample in first until used step 8) {
            val y = microphone[sample].toDouble()
            energy += y * y
            for (row in 0 until taps) {
                val x = past[8000 - low - row * 8 + sample].toDouble()
                fit[row][taps] += x * y
                for (column in 0 until taps) fit[row][column] += x * past[8000 - low - column * 8 + sample]
            }
        }
        if (energy < ((used - first) / 8) * 60.0 * 60.0) return false
        var trace = 0.0
        for (row in 0 until taps) trace += fit[row][row]
        if (trace < 1e5) return false
        val ridge = trace / taps * 1e-8
        for (row in 0 until taps) fit[row][row] += ridge
        for (column in 0 until taps) {
            var pivot = column
            for (row in column + 1 until taps) if (abs(fit[row][column]) > abs(fit[pivot][column])) pivot = row
            if (abs(fit[pivot][column]) < 1e-10) return false
            val swap = fit[column]; fit[column] = fit[pivot]; fit[pivot] = swap
            for (row in column + 1 until taps) {
                val scale = fit[row][column] / fit[column][column]
                for (entry in column until taps + 1) fit[row][entry] -= scale * fit[column][entry]
            }
        }
        for (row in taps - 1 downTo 0) {
            var value = fit[row][taps]
            for (column in row + 1 until taps) value -= fit[row][column] * coefficients[column]
            coefficients[row] = value / fit[row][row]
        }
        var residual = 0.0
        for (sample in first until used step 8) {
            var predicted = 0.0
            for (tap in 0 until taps) predicted += coefficients[tap] * past[8000 - low - tap * 8 + sample]
            val error = microphone[sample] - predicted
            residual += error * error
        }
        return residual.isFinite() && residual / energy < .0005
    }
    fun process(frame: ShortArray, nanos: Long, raw: ShortArray = frame): ShortArray {
        require(frame.size == raw.size && frame.size <= history.size)
        val keep = minOf(used, history.size - frame.size)
        if (keep > 0) {
            history.copyInto(history, 0, used - keep, used)
            microphone.copyInto(microphone, 0, used - keep, used)
        }
        frame.copyInto(history, keep); raw.copyInto(microphone, keep); used = keep + frame.size
        if (nanos - lastCheck >= 20_000_000 || lastCheck == 0L) {
            lastCheck = nanos
            val begin = nanos - (used - frame.size) * 1_000_000_000L / 16000
            reference.frame(begin - 500_000_000, past)
            fun correlation(values: ShortArray, span: Int, delays: IntProgression): Pair<Double, Int> {
                val first = used - minOf(span, used)
                var energy = 0.0
                for (i in first until used step 8) energy += values[i].toDouble() * values[i]
                var best = 0.0; var bestDelay = 0
                if (energy > ((used - first) / 8) * 60.0 * 60.0) {
                    for (delay in delays) {
                        var cross = 0.0; var power = 0.0
                        for (i in first until used step 8) {
                            val other = past[8000 - delay + i].toDouble()
                            cross += values[i] * other; power += other * other
                        }
                        if (power > 1e5) {
                            val score = abs(cross) / sqrt(energy * power)
                            if (score > best) { best = score; bestDelay = delay }
                        }
                    }
                }
                return best to bestDelay
            }
            if (acousticDelay == null && used >= 1600) {
                val estimate = correlation(microphone, 3200, 0..8000 step 16)
                if (estimate.first > .95) acousticDelay = estimate.second
            }
            val delay = acousticDelay
            echoOnly = if (delay == null) false else {
                val range = maxOf(0, delay - 64)..minOf(8000, delay + 64) step 16
                val processedScore = correlation(history, 1600, range).first
                val rawScore = correlation(microphone, 1600, range).first
                // Raw correlation alone erases quiet near speech under louder outgoing playback.
                processedScore > .90 || (rawScore > .95 && rawEchoExplained(delay))
            }
        }
        return if (echoOnly) ShortArray(frame.size) else frame
    }
}
