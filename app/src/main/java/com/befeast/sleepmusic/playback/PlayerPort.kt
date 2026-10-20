package com.befeast.sleepmusic.playback

import java.io.File

data class QueueItem(val trackId: String, private val localFile: File?, val title: String, val artist: String,
    val stream: com.befeast.sleepmusic.online.Resolved? = null, val startPositionMs: Long = 0) {
    init { require((localFile != null) != (stream != null)) }
    /** Offline compatibility accessor; stream inputs deliberately have no file. */
    val file: File get() = checkNotNull(localFile)
}

/**
 * The narrow player the engine drives: a playlist of at most two items, the current track and the
 * lookahead. Production: [ExoPlayerPort]; tests: a fake.
 */
interface PlayerPort {
    val positionMs: Long

    /** -1 when unknown. */
    val durationMs: Long

    /**
     * Replace the playlist with [current] and [upNext] and prepare; play [current] from 0, or leave
     * it paused at 0 when [play] is false.
     */
    fun load(current: QueueItem, upNext: QueueItem?, play: Boolean = true)

    /** Replace, add or drop the item after the current one. */
    fun setUpNext(upNext: QueueItem?)

    /** Move to the queued item now. */
    fun advance()

    fun play()
    fun pause()

    /** Stop and empty the playlist. */
    fun stopAndClear()

    /** Player volume, linear 0..1. */
    fun setVolume(gain: Float)

    fun setListener(listener: Listener?)

    interface Listener {
        /** Audio became audible (isPlaying true). */
        fun onPlaying()

        /** The queued item became current, at the end of a track or after [advance]. */
        fun onAdvanced()

        /** The current item ended with nothing queued. */
        fun onEnded()

        /** Playback paused for a reason other than a request of the engine (audio focus loss). */
        fun onPausedBySystem()

        fun onError(code: String)
    }
}
