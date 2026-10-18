package com.befeast.sleepmusic.playback

import android.os.SystemClock

/** Monotonic milliseconds. Production uses elapsed realtime; tests use virtual time. */
fun interface Clock {
    fun nowMs(): Long
}

/** Elapsed realtime keeps counting while the CPU sleeps, so the sleep timer ends on time. */
object ElapsedRealtimeClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
