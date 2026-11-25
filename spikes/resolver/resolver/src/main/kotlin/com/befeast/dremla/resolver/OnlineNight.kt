package com.befeast.dremla.resolver

/** Host-testable port of contracts/check-catalog.py::Night; no timer scheduling or player IO. */
class OnlineNight(private val selection: List<String>) {
    init { require(selection.isNotEmpty()) }
    var phase = "starting"; private set
    var timer = "running"; private set
    var budget = 3; private set
    var streak = 0; private set
    var resumePositionMs = 0L; private set
    var lastFailure: FailureReason? = null; private set
    private var index = 0
    private var startRetried = false
    private var resumed = false
    val currentTrack: String get() = selection[index]
    val sound: Boolean get() = phase == "playing"

    private fun nextTrack() {
        index = (index + 1) % selection.size
        startRetried = false
        resumed = false
        resumePositionMs = 0
    }
    private fun fail() {
        streak++
        nextTrack()
        phase = if (streak >= selection.size) "unavailable" else "starting"
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
                streak = 0
                nextTrack()
                phase = "starting"
            }
            else -> error("Unknown night event")
        }
    }
}
