package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.Fades
import kotlin.math.max
import kotlin.math.min

/**
 * Player gain per tick, the product of the ported [Fades] factors: the 30 s session fade-in from
 * the first moment audio plays, the track edges, the sleep fade, loudness attenuation and the
 * transport ramp (pause, resume, skip). Only the player volume moves; the stream volume is set
 * once per session and never touched here.
 *
 * The sleep factor follows its target downwards exactly and upwards slew-limited (full scale in
 * [SLEEP_RECOVER_MS]), so a longer timer chosen inside the fade climbs back instead of jumping.
 * The transport level moves linearly towards its target and is shaped by the same cubic curve.
 */
class VolumeEnvelope {
    private var firstPlayingAtMs: Long? = null
    private var lastMs: Long? = null
    private var sleepOut = 1.0
    private var transportTarget = 1.0
    private var transportRampMs = RESUME_RAMP_MS

    /** Linear transport level in [0, 1]; the gain factor is its cubic curve. */
    var transportLevel = 1.0
        private set

    /** The transport ramp reached silence and is staying there. */
    val transportSilent: Boolean get() = transportLevel <= 0.0 && transportTarget <= 0.0

    fun reset(nowMs: Long) {
        firstPlayingAtMs = null
        lastMs = nowMs
        sleepOut = 1.0
        transportLevel = 1.0
        transportTarget = 1.0
    }

    /** The session fade-in counts from the first time audio really plays. */
    fun markPlaying(nowMs: Long) {
        if (firstPlayingAtMs == null) firstPlayingAtMs = nowMs
    }

    /** Ramp the transport to [target]; [fullScaleMs] is the time a full 0..1 change takes. */
    fun rampTransport(target: Double, fullScaleMs: Long) {
        transportTarget = target.coerceIn(0.0, 1.0)
        transportRampMs = fullScaleMs
    }

    /** Jump the transport (the player is silent or a new track starts under its edge fade-in). */
    fun setTransport(level: Double) {
        transportLevel = level.coerceIn(0.0, 1.0)
        transportTarget = transportLevel
    }

    fun gain(nowMs: Long, positionMs: Long, durationMs: Long, sleepTarget: Double, integratedLufs: Double?): Float {
        val dt = (nowMs - (lastMs ?: nowMs)).coerceAtLeast(0L).toDouble()
        lastMs = nowMs
        if (transportLevel != transportTarget) {
            val step = dt / transportRampMs
            transportLevel = if (transportLevel < transportTarget) {
                min(transportTarget, transportLevel + step)
            } else {
                max(transportTarget, transportLevel - step)
            }
        }
        sleepOut = if (sleepTarget <= sleepOut) sleepTarget else min(sleepTarget, sleepOut + dt / SLEEP_RECOVER_MS)
        val sessionFadeIn = firstPlayingAtMs?.let { Fades.fadeIn(nowMs - it, Fades.SESSION_FADE_IN_MS) } ?: 0.0
        return Fades.combined(
            sessionFadeIn,
            Fades.trackEdgeGain(positionMs, durationMs),
            sleepOut,
            Fades.loudnessGain(integratedLufs),
            Fades.curve(transportLevel),
        )
    }

    companion object {
        const val PAUSE_RAMP_MS = 1_200L
        const val RESUME_RAMP_MS = 2_000L
        const val SKIP_RAMP_MS = 600L
        const val SLEEP_RECOVER_MS = 3_000L
    }
}
