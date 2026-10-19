package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.playback.AudioControl

/**
 * STREAM_MUSIC stand-in with the target box's max index 25 by default. [AudioControl] has no write
 * methods (#34), so only the system (the remote) changes it, through [systemChange].
 */
class FakeAudioControl(
    @Suppress("UNUSED_PARAMETER") log: MutableList<String>,
    override val maxIndex: Int = 25,
    var current: Int = 4,
    var muted: Boolean = false,
    override val isFixed: Boolean = false,
) : AudioControl {
    var reads = 0
        private set

    override fun index(): Int {
        reads++
        return if (muted) 0 else current
    }

    override fun isMuted(): Boolean = muted

    /** The listener presses volume or mute on the remote: the system changes the stream, not the app. */
    fun systemChange(index: Int, muted: Boolean = false) {
        current = index
        this.muted = muted
    }
}
