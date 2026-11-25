package com.befeast.sleepmusic.playback

import android.os.SystemClock

/** Milliseconds on one scale: elapsed realtime for timers ([ElapsedRealtimeClock]), epoch only in [WallClock]; tests use virtual time. */
fun interface Clock {
    fun nowMs(): Long
}

/** Elapsed realtime keeps counting while the CPU sleeps, so the sleep timer ends on time. */
/** Wall-clock epoch milliseconds: only for times another app sends (Clock's sleep window end). */
object WallClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

object ElapsedRealtimeClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
