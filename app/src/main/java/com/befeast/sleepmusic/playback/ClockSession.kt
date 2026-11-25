package com.befeast.sleepmusic.playback

/**
 * A sleep session requested by TX10 Clock: only the mode, the music duration and the end of the
 * sleep window cross the app boundary, never calendar data. The music timer is not the sleep
 * window: when the music ends the night closes and Clock keeps the screen protected until the
 * listener says they are awake there.
 */
data class ClockSessionRequest(
    val mode: String,
    val musicMinutes: Int,
    val windowEndMs: Long,
    val requestId: String,
)

object ClockSessions {
    const val ACTION_START = "com.befeast.sleepmusic.action.START_SESSION"
    const val ACTION_STOP = "com.befeast.sleepmusic.action.STOP_SESSION"
    const val EXTRA_MODE = "com.befeast.sleepmusic.extra.MODE"
    const val EXTRA_MUSIC_MINUTES = "com.befeast.sleepmusic.extra.MUSIC_MINUTES"
    const val EXTRA_WINDOW_END = "com.befeast.sleepmusic.extra.WINDOW_END"
    const val EXTRA_REQUEST_ID = "com.befeast.sleepmusic.extra.REQUEST_ID"
    const val EXTRA_REFUSAL = "com.befeast.sleepmusic.extra.REFUSAL"

    val MODES = setOf("night", "day")

    /** Clock's release and preview packages; any other caller is refused. */
    val CALLERS = setOf("com.befeast.tx10clock", "com.befeast.tx10clock.preview")

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Why [request] must not start a night, or null. A request id is honoured once. */
    fun refusal(request: ClockSessionRequest, nowMs: Long, lastRequestId: String?): String? = when {
        request.mode !in MODES -> "unknown mode"
        request.musicMinutes !in SleepTimer.CHOICES -> "invalid music duration"
        request.windowEndMs <= nowMs -> "sleep window already ended"
        request.windowEndMs > nowMs + DAY_MS -> "sleep window more than a day away"
        request.requestId.isBlank() -> "missing request id"
        request.requestId == lastRequestId -> "repeated request"
        else -> null
    }
}
