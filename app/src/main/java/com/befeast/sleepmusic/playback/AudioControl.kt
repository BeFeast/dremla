package com.befeast.sleepmusic.playback

/**
 * The system music stream (STREAM_MUSIC), read only. The app never changes the stream volume or
 * its mute state (#34): the household level stays exactly as the remote left it, and the session
 * fades only the player's own gain within it.
 */
interface AudioControl {
    val maxIndex: Int
    val isFixed: Boolean
    fun index(): Int
    fun isMuted(): Boolean
}

/** The stream as the session found it: the sound-level evidence of PRD item 2 (#4 logs it). */
data class StartVolumeResult(
    val max: Int,
    val before: Int,
    val wasMuted: Boolean,
    val after: Int,
    val mutedAfter: Boolean,
    val fixed: Boolean,
)

/**
 * Run at every session start, never on pause, resume or skip: read STREAM_MUSIC before and after
 * the start step. Nothing is written, so a muted stream or index 0 stays silent until the listener
 * turns the volume up with the remote.
 */
object StartVolumeSequence {
    fun observe(audio: AudioControl): StartVolumeResult {
        val before = audio.index()
        val wasMuted = audio.isMuted()
        return StartVolumeResult(
            max = audio.maxIndex,
            before = before,
            wasMuted = wasMuted,
            after = audio.index(),
            mutedAfter = audio.isMuted(),
            fixed = audio.isFixed,
        )
    }
}
