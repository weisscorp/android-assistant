package local.voice

/** Bounded outgoing PCM indexed by AudioTrack's playback clock, never recorded to disk. */
class PlaybackReference {
    private var rate = 16000
    private var samples = FloatArray(rate * 4)
    private var end = 0L
    private var position = 0L
    private var clock = 0L
    @Synchronized fun begin(sampleRate: Int) {
        rate = sampleRate; samples = FloatArray(rate * 4); end = 0; position = 0; clock = 0
    }
    @Synchronized fun append(data: FloatArray, offset: Int, size: Int, frame: Long, played: Long, nanos: Long) {
        for (i in 0 until size) samples[((frame + i) % samples.size).toInt()] = data[offset + i]
        end = maxOf(end, frame + size); position = played; clock = nanos
    }
    @Synchronized fun clock(played: Long, nanos: Long) { position = played; clock = nanos }
    @Synchronized fun frame(nanos: Long, target: ShortArray) {
        val start = if (clock == 0L) Double.NEGATIVE_INFINITY else position + (nanos - clock).toDouble() * rate / 1e9
        for (i in target.indices) {
            val point = start + i.toDouble() * rate / 16000
            val left = point.toLong()
            target[i] = if (!point.isFinite() || point < maxOf(0L, end - samples.size) || point > end - 1) 0 else {
                val fraction = point - left
                val value = samples[(left % samples.size).toInt()] * (1 - fraction) + samples[(minOf(left + 1, end - 1) % samples.size).toInt()] * fraction
                (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
    /** Keep cancellation active through the short acoustic tail, but not during silent listening. */
    @Synchronized fun hasRecentAudio(nanos: Long): Boolean {
        if (clock == 0L || end == 0L) return false
        val point = position + (nanos - clock).toDouble() * rate / 1e9
        return point >= 0 && point <= end + rate / 2
    }
    @Synchronized fun clear() { samples.fill(0f); end = 0; clock = 0 }
}
