/*
 * Generic tween/easing mechanics adapted from Coucou's BotEngine.swift.
 * Copyright (c) 2026 Louis Raillé. MIT; see assets/licenses/Coucou-LICENSE.txt.
 * Source: v0.2.3, 5cb2a27af352cf7319569920aac899efd42b54bc.
 * This file contains no Mochi character design, poses, artwork or sounds.
 */
package local.voice

import kotlin.math.pow

/** Keyframe interpolation independent of Android, character artwork and wall-clock time. */
internal class MascotTween {
    enum class Channel(val initial: Float) {
        SCALE_X(1f), SCALE_Y(1f), LIFT(0f), TILT(0f), EYES(1f), WOBBLE(0f)
    }
    enum class Ease {
        LINEAR, OUT, IN_OUT, BACK;
        fun at(progress: Float): Float {
            val t = progress.coerceIn(0f, 1f)
            return when (this) {
                LINEAR -> t
                OUT -> 1f - (1f - t).pow(3)
                IN_OUT -> if (t < .5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
                BACK -> 1f + 2.7f * (t - 1f).pow(3) + 1.7f * (t - 1f).pow(2)
            }
        }
    }
    data class Key(val target: Float, val durationMs: Float, val ease: Ease = Ease.IN_OUT) {
        init { require(target.isFinite() && durationMs.isFinite() && durationMs >= 0f) }
    }
    private class Track(val keys: Array<out Key>, var from: Float) {
        var index = 0
        var elapsed = 0f
    }
    private val values = FloatArray(Channel.entries.size) { Channel.entries[it].initial }
    private val tracks = arrayOfNulls<Track>(values.size)
    operator fun get(channel: Channel): Float = values[channel.ordinal]
    val running: Boolean get() = tracks.any { it != null }

    /** Interrupting a track starts at its current interpolated value, without a jump. */
    fun animate(channel: Channel, vararg keys: Key) {
        require(keys.isNotEmpty())
        tracks[channel.ordinal] = Track(keys, this[channel])
    }
    fun set(channel: Channel, value: Float) {
        require(value.isFinite())
        tracks[channel.ordinal] = null
        values[channel.ordinal] = value
    }
    fun reset() {
        Channel.entries.forEach { set(it, it.initial) }
    }
    /** Used for Android's disabled-animation setting: finish all transitions immediately. */
    fun settle() {
        tracks.indices.forEach { i ->
            tracks[i]?.let { values[i] = it.keys.last().target }
            tracks[i] = null
        }
    }
    fun advance(deltaMs: Float) {
        require(deltaMs.isFinite() && deltaMs >= 0f)
        for (i in tracks.indices) {
            val track = tracks[i] ?: continue
            var remaining = deltaMs
            // Carry excess time across keys after a slow frame instead of slowing the pose down.
            while (true) {
                val key = track.keys[track.index]
                val consumed = remaining.coerceAtMost((key.durationMs - track.elapsed).coerceAtLeast(0f))
                track.elapsed += consumed
                remaining -= consumed
                val progress = if (key.durationMs == 0f) 1f else (track.elapsed / key.durationMs).coerceIn(0f, 1f)
                values[i] = track.from + (key.target - track.from) * key.ease.at(progress)
                if (progress < 1f) break
                values[i] = key.target
                track.from = key.target
                track.elapsed = 0f
                track.index++
                if (track.index == track.keys.size) {
                    tracks[i] = null
                    break
                }
                if (remaining == 0f && track.keys[track.index].durationMs > 0f) break
            }
        }
    }
}
