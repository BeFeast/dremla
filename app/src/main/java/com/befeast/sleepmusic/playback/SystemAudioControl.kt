package com.befeast.sleepmusic.playback

import android.media.AudioManager

/** [AudioControl] over [AudioManager.STREAM_MUSIC]: reads only, never sets the volume or mute. */
class SystemAudioControl(private val audioManager: AudioManager) : AudioControl {
    override val maxIndex: Int get() = audioManager.getStreamMaxVolume(STREAM)
    override val isFixed: Boolean get() = audioManager.isVolumeFixed
    override fun index(): Int = audioManager.getStreamVolume(STREAM)
    override fun isMuted(): Boolean = audioManager.isStreamMute(STREAM)

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
