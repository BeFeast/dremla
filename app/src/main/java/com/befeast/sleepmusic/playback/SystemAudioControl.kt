package com.befeast.sleepmusic.playback

import android.media.AudioManager

/** [AudioControl] over [AudioManager.STREAM_MUSIC]. Flags 0: no volume panel. */
class SystemAudioControl(private val audioManager: AudioManager) : AudioControl {
    override val maxIndex: Int get() = audioManager.getStreamMaxVolume(STREAM)
    override val isFixed: Boolean get() = audioManager.isVolumeFixed
    override fun index(): Int = audioManager.getStreamVolume(STREAM)
    override fun isMuted(): Boolean = audioManager.isStreamMute(STREAM)
    override fun unmute() = audioManager.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
    override fun setIndex(index: Int) = audioManager.setStreamVolume(STREAM, index, 0)

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
