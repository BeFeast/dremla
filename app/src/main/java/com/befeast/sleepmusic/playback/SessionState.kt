package com.befeast.sleepmusic.playback

/**
 * What a night session is doing. `Fading` means playing inside the sleep fade (the last 10 min);
 * a paused session shows `Paused`, also inside the fade.
 */
enum class Phase { Idle, Playing, Paused, Fading, Finished }

/** Why a session reached [Phase.Finished]. */
enum class EndReason {
    /** The sleep timer ran out (#5 runs the sleep route on this one only). */
    TimerEnded,

    /** A stop command: the MediaSession stop or the task being removed. */
    Stopped,

    /** Nothing eligible is left to play (every track blocked, or the collection emptied). */
    NothingToPlay,

    /** Three player errors in a row. */
    PlaybackFailed,
}

data class NowPlaying(
    val id: String,
    val title: String,
    val artist: String,
    val category: String,
    val liked: Boolean,
)

/**
 * Observable session state for the night screen (#5) and the UI (#6). Remaining times are whole
 * seconds rounded up (`timerRemainingSec` renders through `remainingParts`), so the 20 Hz tick
 * emits only when something visible changes.
 */
data class SessionState(
    val phase: Phase = Phase.Idle,
    /** +1 at every session start; 0 before the first one in this process. */
    val sessionId: Int = 0,
    val track: NowPlaying? = null,
    val trackRemainingSec: Long = 0,
    val timerRemainingSec: Long = 0,
    val timerMinutes: Int = SleepTimer.DEFAULT_MINUTES,
    /** Set in [Phase.Finished] only. */
    val endReason: EndReason? = null,
    val collectionLoaded: Boolean = false,
    /** Eligible tracks (selection minus never again); 0 means nothing to play, the placeholder shows. */
    val playableCount: Int = 0,
    /**
     * Engine clock time (elapsed realtime, which counts deep sleep) at which the timer ends; set
     * during a session and kept in [Phase.Finished], 0 before the first session.
     */
    val timerEndAtMs: Long = 0,
    /** Engine clock time of the end; set in [Phase.Finished] only. */
    val endedAtMs: Long = 0,
) {
    val isActive: Boolean
        get() = phase == Phase.Playing || phase == Phase.Paused || phase == Phase.Fading

    /**
     * How late a [EndReason.TimerEnded] end came; 0 for every other state. Normally under one
     * tick. Much more means the box slept through the end: a paused player holds no wake lock, so
     * the tick loop can stop until the box wakes, possibly in the morning. #5 uses it to keep the
     * sleep route away from a late end.
     */
    val timerEndLateMs: Long
        get() = if (endReason == EndReason.TimerEnded) (endedAtMs - timerEndAtMs).coerceAtLeast(0L) else 0L
}
