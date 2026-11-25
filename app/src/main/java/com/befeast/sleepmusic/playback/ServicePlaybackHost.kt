package com.befeast.sleepmusic.playback

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * [PlaybackHost] over [PlaybackService]: started with a session and again on every resume (a
 * start of a running service only delivers another `onStartCommand`), stopped at its end.
 */
class ServicePlaybackHost(private val context: Context) : PlaybackHost {
    private fun intent() = Intent(context, PlaybackService::class.java)

    override fun sessionStarted() = start()

    override fun sessionResumed() = start()

    private fun start() {
        // A launcher start, and a resume from the remote, run while the activity is in front, so a
        // plain start is allowed. Media3 moves the service to the foreground once the player is
        // ready and playing.
        try {
            context.startService(intent())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "playback service not started: ${e.message}")
        }
    }

    override fun sessionEnded() {
        context.stopService(intent())
    }

    private companion object {
        const val TAG = "SleepMusic"
    }
}
