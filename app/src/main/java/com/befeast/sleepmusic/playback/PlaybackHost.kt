package com.befeast.sleepmusic.playback

/** Starts and stops the Android host of a session ([ServicePlaybackHost] in production). */
interface PlaybackHost {
    /** A session started. */
    fun sessionStarted()

    /**
     * A paused session plays again. Idempotent: while paused the session is not in the foreground
     * (Media3 leaves it on pause), so the system may have stopped the host; it is started again.
     */
    fun sessionResumed()

    /** The session ended. */
    fun sessionEnded()
}
