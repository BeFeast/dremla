package com.befeast.sleepmusic.playback

import android.util.Log

/** Session milestones, persisted by NightLog in production. */
interface SessionEvents {
    fun uiEntry(verdict: LaunchVerdict) {}
    fun sessionStarted(sessionId: Int, timerMinutes: Int, startVolume: StartVolumeResult) {}
    fun trackStarted(sessionId: Int, trackId: String) {}
    fun sessionEnded(sessionId: Int, reason: EndReason) {}
    fun playbackError(sessionId: Int, code: String) {}

    companion object {
        val NONE: SessionEvents = object : SessionEvents {}
    }
}

class LogcatSessionEvents : SessionEvents {
    override fun uiEntry(verdict: LaunchVerdict) {
        Log.i(TAG, "ui entry: ${verdict.entry} ${verdict.reason}")
    }

    override fun sessionStarted(sessionId: Int, timerMinutes: Int, startVolume: StartVolumeResult) {
        Log.i(TAG, "session $sessionId start: timer ${timerMinutes}min volume $startVolume")
    }

    override fun trackStarted(sessionId: Int, trackId: String) {
        Log.i(TAG, "session $sessionId track: $trackId")
    }

    override fun sessionEnded(sessionId: Int, reason: EndReason) {
        Log.i(TAG, "session $sessionId end: $reason")
    }

    override fun playbackError(sessionId: Int, code: String) {
        Log.w(TAG, "session $sessionId player error: $code")
    }

    private companion object {
        const val TAG = "SleepMusic"
    }
}
