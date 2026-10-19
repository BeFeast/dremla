package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.playback.CommandSource
import com.befeast.sleepmusic.playback.EndReason
import com.befeast.sleepmusic.playback.LaunchVerdict
import com.befeast.sleepmusic.playback.PlaybackControls
import com.befeast.sleepmusic.playback.PlaybackHost
import com.befeast.sleepmusic.playback.SessionEvents
import com.befeast.sleepmusic.playback.SessionState
import com.befeast.sleepmusic.playback.StartVolumeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecordingHost(private val log: MutableList<String>) : PlaybackHost {
    var started = 0
    var resumed = 0
    var ended = 0

    override fun sessionStarted() {
        started++
        log += "host.sessionStarted"
    }

    override fun sessionResumed() {
        resumed++
        log += "host.sessionResumed"
    }

    override fun sessionEnded() {
        ended++
        log += "host.sessionEnded"
    }
}

class RecordingEvents : SessionEvents {
    val sleepFailures = mutableListOf<Int>()
    override fun sleepRouteFailed(sessionId: Int) { sleepFailures += sessionId }
    val verdicts = mutableListOf<LaunchVerdict>()
    val startVolumes = mutableListOf<StartVolumeResult>()
    val tracks = mutableListOf<String>()
    val ends = mutableListOf<EndReason>()
    val errors = mutableListOf<String>()

    override fun uiEntry(verdict: LaunchVerdict) {
        verdicts += verdict
    }

    override fun sessionStarted(sessionId: Int, timerMinutes: Int, startVolume: StartVolumeResult) {
        startVolumes += startVolume
    }

    override fun trackStarted(sessionId: Int, trackId: String) {
        tracks += trackId
    }

    override fun sessionEnded(sessionId: Int, reason: EndReason) {
        ends += reason
    }

    override fun playbackError(sessionId: Int, code: String) {
        errors += code
    }
}

/** Records commands without deciding anything; [state] is set by the test. */
class RecordingControls(initial: SessionState = SessionState()) : PlaybackControls {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<SessionState> = mutableState
    val verdicts = mutableListOf<LaunchVerdict>()
    val calls = mutableListOf<String>()

    override fun onUiEntry(verdict: LaunchVerdict) {
        verdicts += verdict
    }

    override fun togglePause(source: CommandSource) {
        calls += "togglePause($source)"
    }

    override fun play(source: CommandSource) {
        calls += "play($source)"
    }

    override fun pause(source: CommandSource) {
        calls += "pause($source)"
    }

    override fun skip(source: CommandSource) {
        calls += "skip($source)"
    }

    override fun stop(source: CommandSource) {
        calls += "stop($source)"
    }

    override fun toggleLike() {
        calls += "toggleLike"
    }

    override fun neverAgain() {
        calls += "neverAgain"
    }

    override fun setTimerMinutes(minutes: Int) {
        calls += "setTimerMinutes($minutes)"
    }
}
