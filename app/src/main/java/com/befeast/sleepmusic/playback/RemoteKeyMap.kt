package com.befeast.sleepmusic.playback

import android.view.KeyEvent

enum class RemoteCommand {
    TogglePause,
    Play,
    Pause,
    Skip,

    /** Consumed without a command. */
    Consume,

    /** OK went down: consume it and track the press; the command comes with the release. */
    TrackPress,
}

/**
 * Remote key to command, for keys the UI did not consume. Volume and mute keys map to nothing, so
 * the activity hands them to the system untouched. Media keys are always consumed while the
 * activity is in front, so they never fall through to another app's media session.
 *
 * OK toggles pause on its release, and only after a tracked press that was not cancelled: a long
 * press of OK cancels the release (the activity reserves it for #6's menu), and a release whose
 * press went elsewhere (a focused control consumed the down) does nothing here.
 */
object RemoteKeyMap {
    private val OK = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    private val MEDIA = mapOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to RemoteCommand.TogglePause,
        KeyEvent.KEYCODE_MEDIA_PLAY to RemoteCommand.Play,
        KeyEvent.KEYCODE_MEDIA_PAUSE to RemoteCommand.Pause,
        KeyEvent.KEYCODE_MEDIA_NEXT to RemoteCommand.Skip,
        KeyEvent.KEYCODE_MEDIA_STOP to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_REWIND to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_STEP_FORWARD to RemoteCommand.Consume,
        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD to RemoteCommand.Consume,
    )

    private val DOWN = MEDIA + OK.associateWith { RemoteCommand.TrackPress } + mapOf(
        KeyEvent.KEYCODE_DPAD_RIGHT to RemoteCommand.Skip,
    )

    /** Null: not ours, hand the key to `super`. Auto-repeat of a mapped key is consumed without a command. */
    fun onKeyDown(keyCode: Int, repeatCount: Int): RemoteCommand? {
        val command = DOWN[keyCode] ?: return null
        return if (repeatCount > 0) RemoteCommand.Consume else command
    }

    /**
     * Null: not ours, hand the key to `super`. OK toggles pause when its press was tracked and not
     * cancelled; media keys are consumed on key up too, so a lone up never reaches another session.
     */
    fun onKeyUp(keyCode: Int, tracked: Boolean, canceled: Boolean): RemoteCommand? = when {
        keyCode in OK -> if (tracked && !canceled) RemoteCommand.TogglePause else null
        keyCode in MEDIA -> RemoteCommand.Consume
        else -> null
    }

    /** OK held down: no command in #3; reserved for #6's menu. */
    fun consumesLongPress(keyCode: Int): Boolean = keyCode in OK
}
