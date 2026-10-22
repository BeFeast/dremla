package com.befeast.sleepmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.befeast.sleepmusic.playback.ClockSessions
import com.befeast.sleepmusic.playback.PlaybackProvider

/**
 * Clock's "I'm awake": stops only the night Clock started with that id, or cancels that request while it
 * still waits for the collection on first run (#72).
 */
class SessionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClockSessions.ACTION_STOP) return
        val id = intent.getStringExtra(ClockSessions.EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return
        (context.applicationContext as PlaybackProvider).playback.controls.stopClockSession(id)
    }
}
