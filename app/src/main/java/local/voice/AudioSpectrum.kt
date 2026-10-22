package local.voice

import kotlin.math.*

/** 512-point FFT, mono 16kHz. Bounded signal data only; never opens or records a microphone. */
internal class AudioSpectrum {
    private val ring = ShortArray(SIZE)
    private var position = 0
    private var collected = 0
    private var at = 0L
    private var result = FloatArray(BANDS)
    @Synchronized fun push(pcm: ShortArray, silenced: Boolean, now: Long = System.nanoTime()) {
        if (silenced) { ring.fill(0); result.fill(0f); collected = 0; at = 0; return }
        for (value in pcm) { ring[position] = value; position = (position + 1) % SIZE; collected++ }
        if (collected >= SIZE && now - at >= 33_000_000L) {
            val ordered = ShortArray(SIZE) { ring[(position + it) % SIZE] }
            result = analyze(ordered); at = now; collected = 0
        }
    }
    @Synchronized fun read(now: Long = System.nanoTime()): FloatArray =
        if (at == 0L || now - at > 250_000_000L) FloatArray(BANDS) else result.copyOf()
    companion object {
        const val SIZE = 512
        const val BANDS = 32
        fun analyze(pcm: ShortArray): FloatArray {
            require(pcm.size == SIZE)
            val real = DoubleArray(SIZE); val imaginary = DoubleArray(SIZE)
            val mean = pcm.sumOf { it.toDouble() } / SIZE
            for (i in 0 until SIZE) real[i] = (pcm[i] - mean) / 32768.0 * (.5 - .5 * cos(2 * PI * i / (SIZE - 1)))
            var j = 0
            for (i in 1 until SIZE) {
                var bit = SIZE shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val tmp = real[i]; real[i] = real[j]; real[j] = tmp }
            }
            var size = 2
            while (size <= SIZE) {
                val angle = -2 * PI / size
                for (start in 0 until SIZE step size) for (k in 0 until size / 2) {
                    val left = start+k; val right = left+size/2
                    val c = cos(angle*k); val s = sin(angle*k)
                    val r = real[right]*c - imaginary[right]*s
                    val im = real[right]*s + imaginary[right]*c
                    real[right] = real[left]-r; imaginary[right] = imaginary[left]-im
                    real[left] += r; imaginary[left] += im
                }
                size *= 2
            }
            // Logarithmic frequency bins cover the audible voice range (62.5Hz–8kHz).
            return FloatArray(BANDS) { band ->
                val lo = (2.0 * 128.0.pow(band.toDouble()/BANDS)).toInt().coerceIn(2,255)
                val hi = max(lo+1,(2.0 * 128.0.pow((band+1.0)/BANDS)).toInt()).coerceAtMost(256)
                var magnitude = 0.0
                for (bin in lo until hi) magnitude = max(magnitude, hypot(real[bin],imaginary[bin]) * 4 / SIZE)
                if (magnitude < .0008) 0f else (ln(1 + magnitude * 25) / ln(26.0)).toFloat().coerceIn(0f,1f)
            }
        }
    }
}
