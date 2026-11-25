package com.befeast.sleepmusic

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.test.runner.AndroidJUnitRunner
import com.befeast.sleepmusic.playback.CommandSource
import com.befeast.sleepmusic.playback.LaunchVerdict
import com.befeast.sleepmusic.playback.PlaybackControls
import com.befeast.sleepmusic.playback.PlaybackGraph
import com.befeast.sleepmusic.playback.PlaybackProvider
import com.befeast.sleepmusic.playback.SessionCallback
import com.befeast.sleepmusic.playback.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-only device checks: the app process gets [DeviceUiTestApp], whose graph has recording
 * controls and no player, so nothing can play. Playback itself is never exercised here.
 */
class DeviceUiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, DeviceUiTestApp::class.java.name, context)
}

class DeviceUiTestApp : Application(), PlaybackProvider {
    override val playback: PlaybackGraph get() = graph

    companion object {
        val controls = DeviceRecordingControls()
        val graph = object : PlaybackGraph {
            override val controls: PlaybackControls get() = DeviceUiTestApp.controls
            override fun sessionPlayer(): Player = error("UI-only device test: no player")
            override fun sessionCallback(): MediaSession.Callback = SessionCallback()
        }
    }
}

class DeviceRecordingControls : PlaybackControls {
    val mutableState = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = mutableState
    val calls = mutableListOf<String>()

    fun reset(initial: SessionState) {
        calls.clear()
        mutableState.value = initial
    }

    override fun onUiEntry(verdict: LaunchVerdict) {}
    override fun togglePause(source: CommandSource) { calls += "togglePause" }
    override fun play(source: CommandSource) { calls += "play" }
    override fun pause(source: CommandSource) { calls += "pause" }
    override fun skip(source: CommandSource) { calls += "skip" }
    override fun stop(source: CommandSource) { calls += "stop" }
    override fun toggleLike() { calls += "toggleLike" }
    override fun neverAgain() { calls += "neverAgain" }
    override fun setTimerMinutes(minutes: Int) { calls += "setTimerMinutes" }
    override fun setOnline(enabled: Boolean) { calls += "setOnline" }
}
