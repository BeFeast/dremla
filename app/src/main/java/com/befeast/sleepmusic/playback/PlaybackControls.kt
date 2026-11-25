package com.befeast.sleepmusic.playback

import kotlinx.coroutines.flow.StateFlow

/** Where a command came from. Every origin reaches the same engine functions. */
enum class CommandSource { Remote, MediaSession, System, Menu }

/**
 * The only way to drive playback: the activity's key handler, the MediaSession (system
 * controllers, media buttons) and, in #6, the menu all call these. Commands other than
 * [onUiEntry] do nothing unless a session is active, whatever their source.
 */
interface PlaybackControls {
    /** Cancels a start waiting for the first collection: a launcher start or Clock's accepted request. */
    fun cancelPendingStart() {}
    val state: StateFlow<SessionState>
    fun setOnline(enabled: Boolean) {}
    fun onServiceDestroyed() {}

    /** The only way a session starts: a launcher start while no session is active. */
    fun onUiEntry(verdict: LaunchVerdict)

    fun togglePause(source: CommandSource)

    /** Resumes a paused session; never starts one. */
    fun play(source: CommandSource)

    fun pause(source: CommandSource)

    /** Right on the remote, media key NEXT and every MediaSession next. */
    fun skip(source: CommandSource)

    /** Ends the session for good. */
    fun stop(source: CommandSource)

    fun toggleLike()

    /** Blocks the current track for good and skips it. */
    fun neverAgain()

    fun setTvOff(enabled: Boolean) {}

    /** Re-read route availability; never invokes a sleep action. */
    fun refreshSleepRoute() {}

    fun setTimerMinutes(minutes: Int)

    /** Starts a night requested by Clock; returns why it was refused, or null when it started. */
    fun startClockSession(request: ClockSessionRequest): String? = "not supported"

    /**
     * Ends the night Clock started with [requestId], or cancels Clock's accepted request with that id
     * while it still waits for the collection (#72); any other session or request is left alone.
     */
    fun stopClockSession(requestId: String) {}
}
