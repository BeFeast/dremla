package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.playback.AudioControl

/** STREAM_MUSIC stand-in with the target box's max index 25 by default. */
class FakeAudioControl(
    private val log: MutableList<String>,
    override val maxIndex: Int = 25,
    var current: Int = 4,
    var muted: Boolean = false,
    override val isFixed: Boolean = false,
) : AudioControl {
    var denyChanges = false

    override fun index(): Int = if (muted) 0 else current
    override fun isMuted(): Boolean = muted

    override fun unmute() {
        log += "audio.unmute"
        if (denyChanges) throw SecurityException("not allowed in do not disturb")
        muted = false
    }

    override fun setIndex(index: Int) {
        log += "audio.setIndex($index)"
        if (denyChanges) throw SecurityException("not allowed in do not disturb")
        current = index
    }

    /** The listener presses volume or mute on the remote: the system changes the stream, not the app. */
    fun systemChange(index: Int, muted: Boolean = false) {
        current = index
        this.muted = muted
    }
}
