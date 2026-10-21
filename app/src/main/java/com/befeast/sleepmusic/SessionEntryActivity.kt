package com.befeast.sleepmusic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.befeast.sleepmusic.playback.ClockSessionRequest
import com.befeast.sleepmusic.playback.ClockSessions
import com.befeast.sleepmusic.playback.LaunchClassifier
import com.befeast.sleepmusic.playback.PlaybackProvider

/**
 * The one entry TX10 Clock uses to start a night: an explicit, result-returning start carrying
 * only mode, music minutes and window end. The request is validated here and again in the engine;
 * a refusal is returned to Clock and nothing plays. An accepted night opens the normal night scene
 * passively (it is not a launcher start, so it never starts a second session).
 */
class SessionEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refusal = if (savedInstanceState != null) "restored" else start(intent)
        if (refusal != null) {
            setResult(RESULT_CANCELED, Intent().putExtra(ClockSessions.EXTRA_REFUSAL, refusal))
        } else {
            setResult(RESULT_OK)
            startActivity(Intent(this, MainActivity::class.java).setAction(LaunchClassifier.ACTION_SHOW))
        }
        finish()
    }

    private fun start(intent: Intent?): String? {
        if (intent?.action != ClockSessions.ACTION_START) return "unknown action"
        if (callingActivity?.packageName !in ClockSessions.CALLERS) return "unknown caller"
        val request = ClockSessionRequest(
            mode = intent.getStringExtra(ClockSessions.EXTRA_MODE).orEmpty(),
            musicMinutes = intent.getIntExtra(ClockSessions.EXTRA_MUSIC_MINUTES, -1),
            windowEndMs = intent.getLongExtra(ClockSessions.EXTRA_WINDOW_END, -1L),
            requestId = intent.getStringExtra(ClockSessions.EXTRA_REQUEST_ID).orEmpty(),
        )
        return (application as PlaybackProvider).playback.controls.startClockSession(request)
    }
}
