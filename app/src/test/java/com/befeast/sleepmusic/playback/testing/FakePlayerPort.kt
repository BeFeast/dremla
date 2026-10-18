package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.playback.Clock
import com.befeast.sleepmusic.playback.PlayerPort
import com.befeast.sleepmusic.playback.QueueItem

/**
 * Records every call into a shared event log; the position advances with the clock while playing.
 * Callbacks are synchronous, like ExoPlayer's on the application thread.
 */
class FakePlayerPort(private val clock: Clock, private val log: MutableList<String>) : PlayerPort {
    var listener: PlayerPort.Listener? = null
        private set
    var current: QueueItem? = null
        private set
    var upNext: QueueItem? = null
        private set
    var playing = false
        private set

    /** Report onPlaying as soon as play() runs; false simulates a slow first load. */
    var reportPlayingOnPlay = true

    /** Log every setVolume call (off for very long runs; [volumes] always records them). */
    var logVolumes = true

    /** -1: unknown, the engine falls back to the manifest duration. */
    var duration: Long = -1

    val volumes = mutableListOf<Pair<Long, Float>>()
    val volume: Float get() = volumes.lastOrNull()?.second ?: 1f

    /** Every track that became current, in order. */
    val started = mutableListOf<String>()

    private var positionBaseMs = 0L
    private var playingSinceMs: Long? = null

    override val positionMs: Long
        get() = positionBaseMs + (playingSinceMs?.let { clock.nowMs() - it } ?: 0L)

    override val durationMs: Long get() = duration

    override fun load(current: QueueItem, upNext: QueueItem?, play: Boolean) {
        log += if (play) "port.load(${current.trackId}, ${upNext?.trackId})" else "port.load(${current.trackId}, ${upNext?.trackId}, paused)"
        this.current = current
        this.upNext = upNext
        started += current.trackId
        playing = false
        resetPosition()
        if (play) startPlaying()
    }

    override fun setUpNext(upNext: QueueItem?) {
        log += "port.setUpNext(${upNext?.trackId})"
        this.upNext = upNext
    }

    override fun advance() {
        log += "port.advance"
        moveToQueued()
    }

    override fun play() {
        log += "port.play"
        startPlaying()
    }

    override fun pause() {
        log += "port.pause"
        if (!playing) return
        positionBaseMs = positionMs
        playingSinceMs = null
        playing = false
    }

    override fun stopAndClear() {
        log += "port.stopAndClear"
        current = null
        upNext = null
        playing = false
        resetPosition()
    }

    override fun setVolume(gain: Float) {
        volumes += clock.nowMs() to gain
        if (logVolumes) log += "port.setVolume($gain)"
    }

    override fun setListener(listener: PlayerPort.Listener?) {
        this.listener = listener
    }

    /** The current track plays to its end: the queued one starts, or the player ends. */
    fun completeCurrentTrack() {
        if (upNext != null) moveToQueued() else {
            playing = false
            playingSinceMs = null
            listener?.onEnded()
        }
    }

    /** The first load finished and audio is now audible (with [reportPlayingOnPlay] false). */
    fun becomeAudible() = listener?.onPlaying()

    fun fail(code: String = "ERROR_CODE_DECODING_FAILED") = listener?.onError(code)

    fun loseAudioFocus() {
        pause()
        listener?.onPausedBySystem()
    }

    private fun moveToQueued() {
        val next = checkNotNull(upNext) { "advance without a queued item" }
        current = next
        upNext = null
        started += next.trackId
        resetPosition()
        listener?.onAdvanced()
    }

    private fun startPlaying() {
        if (playing) return
        playing = true
        playingSinceMs = clock.nowMs()
        if (reportPlayingOnPlay) listener?.onPlaying()
    }

    private fun resetPosition() {
        positionBaseMs = 0L
        playingSinceMs = if (playing) clock.nowMs() else null
    }
}
