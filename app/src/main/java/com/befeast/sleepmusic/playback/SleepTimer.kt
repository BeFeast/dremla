package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.Fades
import kotlin.math.min

/**
 * Countdown of one night on the injected clock. It keeps running while paused, so the night ends
 * on time. The sleep fade is the last `min(10 min, timer)` and follows the ported cubic curve.
 */
class SleepTimer private constructor(endAtMs: Long, private var fadeMs: Long) {

    /** Clock time at which the night ends. */
    var endAtMs: Long = endAtMs
        private set

    fun remainingMs(nowMs: Long): Long = endAtMs - nowMs

    fun inFade(nowMs: Long): Boolean = remainingMs(nowMs) <= fadeMs

    /** Target sleep gain now: 1 before the fade, the cubic curve inside it, exactly 0 at the end. */
    fun sleepGain(nowMs: Long): Double = Fades.sleepGain(remainingMs(nowMs), fadeMs)

    /** A new timer choice during a session counts from now. */
    fun rearm(nowMs: Long, minutes: Int) = rearmMs(nowMs, minutes * MINUTE_MS)

    /** As [rearm], for a duration a Clock night may have capped at its window end (#77). */
    fun rearmMs(nowMs: Long, durationMs: Long) {
        endAtMs = nowMs + durationMs
        fadeMs = fadeMsFor(durationMs)
    }

    companion object {
        const val DEFAULT_MINUTES = 120
        const val MINUTE_MS = 60_000L

        /** The menu's choices in minutes (#6 renders them). */
        val CHOICES: List<Int> = listOf(30, 45, 60, 90, 120, 180)

        /** A stored or requested value outside [CHOICES] reads as the default. */
        fun sanitize(minutes: Int): Int = if (minutes in CHOICES) minutes else DEFAULT_MINUTES

        fun fadeMsFor(timerMs: Long): Long = min(Fades.DEFAULT_SLEEP_FADE_MS, timerMs)

        fun start(nowMs: Long, minutes: Int): SleepTimer = startMs(nowMs, sanitize(minutes) * MINUTE_MS)

        /** A night of [durationMs]: a Clock night capped at its window end (#77); the fade is its last min(10 min, duration). */
        fun startMs(nowMs: Long, durationMs: Long): SleepTimer = SleepTimer(nowMs + durationMs, fadeMsFor(durationMs))
    }
}
