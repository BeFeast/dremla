package com.befeast.sleepmusic.online

/** Host-testable port of contracts/check-catalog.py::Night; no timer scheduling or player IO. */
class OnlineNight(private val selection: List<String>,
    private val next: (() -> String)? = null, initial: String = selection.first()) {
    init { require(selection.isNotEmpty()) }
    var phase = "starting"; private set
    var timer = "running"; private set
    var budget = 3; private set
    private val failedTracks = mutableSetOf<String>()
    val streak: Int get() = failedTracks.size
    var resumePositionMs = 0L; private set
    var lastFailure: FailureReason? = null; private set
    private var index = 0
    private var actual = initial
    var eligibleSelection: Set<String> = selection.toSet()
    private var startRetried = false
    private var resumed = false
    val currentTrack: String get() = actual
    val sound: Boolean get() = phase == "playing"

    private fun nextTrack() {
        index = (index + 1) % selection.size
        actual = next?.invoke() ?: selection[index]
        startRetried = false
        resumed = false
        resumePositionMs = 0
    }
    private fun fail() {
        failedTracks += currentTrack
        if (failedTracks.containsAll(eligibleSelection)) {
            phase = "unavailable"
        } else {
            nextTrack()
            phase = "starting"
        }
    }

    fun failPlaying(reason: FailureReason) {
        check(phase == "playing")
        lastFailure = reason
        fail()
    }

    fun skip() {
        if (phase in setOf("finished", "unavailable")) return
        nextTrack(); phase = "starting"
    }

    fun resolveCurrent(resolver: StreamResolver, session: SessionProvider = NoSession) {
        check(phase == "starting")
        val track = currentTrack
        val result = resolver.resolve(track.removePrefix("yt-"), session)
        apply("resolve", track, if (result is Failure) result.reason.code else "ok")
    }

    fun apply(event: String, track: String? = null, result: String? = null, positionMs: Long = 0) {
        if (phase == "finished") return
        when (event) {
            "timerExpired" -> { phase = "finished"; timer = "expired" }
            "networkRecovered" -> Unit
            "resolve" -> {
                check(phase == "starting" && track == currentTrack)
                require(result == "ok" || FailureReason.entries.any { it.code == result })
                if (result == "ok") {
                    phase = "playing"
                } else {
                    lastFailure = FailureReason.entries.single { it.code == result }
                    if (result == "network" && !startRetried && budget > 0) {
                        startRetried = true
                        budget--
                    } else fail()
                }
            }
            "drop" -> {
                check(phase == "playing")
                require(positionMs >= 0)
                lastFailure = FailureReason.NETWORK
                if (!resumed && budget > 0) {
                    phase = "resuming"
                    resumed = true
                    resumePositionMs = positionMs
                    budget--
                } else fail()
            }
            "resume" -> {
                check(phase == "resuming")
                if (result == "ok") phase = "playing" else fail()
            }
            "trackEnd" -> {
                check(phase == "playing")
                failedTracks.clear()
                nextTrack()
                phase = "starting"
            }
            else -> error("Unknown night event")
        }
    }
}
