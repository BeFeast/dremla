package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.StartVolume

/** The system music stream (STREAM_MUSIC) and nothing else. */
interface AudioControl {
    val maxIndex: Int
    val isFixed: Boolean
    fun index(): Int
    fun isMuted(): Boolean
    fun unmute()
    fun setIndex(index: Int)
}

/** What the start-volume step saw and did: the sound-level evidence of PRD item 2 (#4 logs it). */
data class StartVolumeResult(
    val max: Int,
    val before: Int,
    val wasMuted: Boolean,
    val target: Int,
    val after: Int,
    val mutedAfter: Boolean,
    val fixed: Boolean,
    val error: String?,
)

object StartVolumeLevel {
    const val MIN = 1
    const val MAX = StartVolume.REFERENCE_MAX

    fun sanitize(level: Int): Int = if (level in MIN..MAX) level else StartVolume.DEFAULT_LEVEL
}

/**
 * Run at every session start, never on pause, resume or skip: unmute STREAM_MUSIC if it is muted,
 * then set it exactly to the start volume ([StartVolume.index] of the stored level). Unmute only
 * when muted: on a full-volume HDMI-CEC device the system turns every mute adjustment into a CEC
 * mute toggle. A [SecurityException] (do-not-disturb policy) is recorded and the session still starts.
 */
object StartVolumeSequence {
    fun apply(audio: AudioControl, level: Int): StartVolumeResult {
        val max = audio.maxIndex
        val before = audio.index()
        val wasMuted = audio.isMuted()
        val target = StartVolume.index(StartVolumeLevel.sanitize(level), max)
        val errors = mutableListOf<String>()
        if (wasMuted) {
            try {
                audio.unmute()
            } catch (e: SecurityException) {
                errors += "unmute: ${e.message}"
            }
        }
        try {
            audio.setIndex(target)
        } catch (e: SecurityException) {
            errors += "set index: ${e.message}"
        }
        return StartVolumeResult(
            max = max,
            before = before,
            wasMuted = wasMuted,
            target = target,
            after = audio.index(),
            mutedAfter = audio.isMuted(),
            fixed = audio.isFixed,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }
}
