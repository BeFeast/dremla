package com.befeast.sleepmusic

import kotlin.math.min
import kotlin.math.pow

/**
 * Pure volume-envelope math. Every gain is a linear amplitude factor in [0, 1]
 * (ExoPlayer.volume is linear). The final player volume is the product of all factors.
 */
object Fades {
    const val SESSION_FADE_IN_MS = 30_000L
    const val TRACK_EDGE_IN_MS = 4_000L
    const val TRACK_EDGE_OUT_MS = 6_000L
    const val DEFAULT_SLEEP_FADE_MS = 10 * 60_000L

    /** Loudness target for attenuation only (we never boost above unity). */
    const val TARGET_LUFS = -24.0

    /**
     * Fade curve: cubic. Roughly dB-linear in the audible range (0.5 -> -18 dB,
     * 0.75 -> -7.5 dB) but, unlike a pure dB ramp, it reaches true zero without a step.
     */
    fun curve(p: Double): Double {
        val c = p.coerceIn(0.0, 1.0)
        return c * c * c
    }

    /** Rising edge: 0 at elapsed 0, 1 at elapsed >= duration. */
    fun fadeIn(elapsedMs: Long, durationMs: Long): Double =
        if (durationMs <= 0) 1.0 else curve(elapsedMs.toDouble() / durationMs)

    /** Sleep timer: unity until the last [fadeMs], then down to exactly 0 at remaining 0. */
    fun sleepGain(remainingMs: Long, fadeMs: Long): Double = when {
        remainingMs <= 0 -> 0.0
        fadeMs <= 0 || remainingMs >= fadeMs -> 1.0
        else -> curve(remainingMs.toDouble() / fadeMs)
    }

    /** Soft edges of every track so starts, skips and endings never click in. */
    fun trackEdgeGain(positionMs: Long, durationMs: Long): Double {
        val inGain = fadeIn(positionMs, TRACK_EDGE_IN_MS)
        val outGain = if (durationMs <= 0) 1.0 else fadeIn(durationMs - positionMs, TRACK_EDGE_OUT_MS)
        return min(inGain, outGain)
    }

    /** Attenuate tracks louder than [TARGET_LUFS]; quieter ones stay at unity. */
    fun loudnessGain(integratedLufs: Double?): Double {
        if (integratedLufs == null || integratedLufs.isNaN()) return 1.0
        return min(1.0, 10.0.pow((TARGET_LUFS - integratedLufs) / 20.0))
    }

    fun combined(vararg gains: Double): Float {
        var g = 1.0
        for (x in gains) g *= x.coerceIn(0.0, 1.0)
        return g.toFloat()
    }
}
