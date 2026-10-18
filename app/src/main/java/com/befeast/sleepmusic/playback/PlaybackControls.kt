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
    val state: StateFlow<SessionState>

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

    fun setTimerMinutes(minutes: Int)

    /** Persists the start volume level (1..25 on the reference scale); applies from the next session. */
    fun setStartVolumeLevel(level: Int)
}
