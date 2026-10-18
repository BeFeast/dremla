package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 4 / AC-6 and PRD 15 / AC-13: a whole night on virtual time. The last 10 min fade the player
 * volume smoothly to exactly 0, then playback and the service stop.
 */
class TimerFadeEngineTest {
    private val minute = 60_000L

    private fun night(minutes: Int = 120): EngineHarness {
        val h = EngineHarness(tracks = listOf(Tracks.track("amb-001", durationSec = 4 * 3_600)))
        h.port.logVolumes = false
        h.engine.setTimerMinutes(minutes)
        h.launcherStart()
        return h
    }

    @Test
    fun twoHourNightFadesWithoutStepsAndStops() {
        val h = night()
        val start = h.now
        val end = start + 120 * minute
        h.advance(100 * minute)
        assertEquals("full volume before the fade", 1f, h.port.volume, 0f)
        assertEquals(Phase.Playing, h.state.phase)
        h.advance(10 * minute + 1_000)
        assertEquals(Phase.Fading, h.state.phase)
        h.advance(10 * minute)

        val fade = h.port.volumes.filter { (t, _) -> t >= end - 10 * minute }.map { it.second }
        assertTrue("the fade is sampled densely: ${fade.size}", fade.size > 2_000)
        var maxStep = 0f
        fade.zipWithNext().forEach { (a, b) ->
            assertTrue("non-increasing", b <= a)
            maxStep = maxOf(maxStep, a - b)
        }
        assertTrue("max step $maxStep per tick", maxStep <= 1e-3f)
        assertEquals("ends at exactly 0", 0f, fade.last(), 0f)

        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("port.stopAndClear" in h.log)
        assertEquals(1, h.host.ended)
        assertEquals(listOf(EndReason.TimerEnded), h.events.ends)
        assertEquals("stream volume only at the start", listOf("audio.setIndex(15)"), h.log.filter { it.startsWith("audio.") })
    }

    @Test
    fun theTickLoopEndsWithTheSession() {
        val h = night(30)
        h.advance(31 * minute)
        assertEquals(Phase.Finished, h.state.phase)
        val volumes = h.port.volumes.size
        val log = h.log.size
        h.advance(60 * minute)
        assertEquals(volumes, h.port.volumes.size)
        assertEquals(log, h.log.size)
    }

    @Test
    fun aPausedSessionStillEndsOnTime() {
        val h = night(30)
        h.advance(20 * minute)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(9 * minute)
        assertEquals(Phase.Paused, h.state.phase)
        h.advance(minute + 100)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertEquals(1, h.host.ended)
    }

    @Test
    fun theTimerChoiceIsPersistedAndUsedByTheNextSession() {
        val h = EngineHarness()
        h.engine.setTimerMinutes(45)
        assertEquals(45, h.store.timerMinutes)
        assertEquals(45, h.state.timerMinutes)
        h.launcherStart()
        assertEquals(45 * 60L, h.state.timerRemainingSec)
        h.engine.setTimerMinutes(77) // not a choice
        assertEquals(120, h.store.timerMinutes)
    }

    @Test
    fun aLongerTimerChosenInsideTheFadeClimbsBackSmoothly() {
        val h = night(30)
        h.advance(27 * minute)
        val low = h.port.volume
        assertTrue(low < 0.1f)
        val from = h.port.volumes.size
        h.engine.setTimerMinutes(60)
        h.advance(5_000)
        val climb = h.port.volumes.drop(from).map { it.second }
        climb.zipWithNext().forEach { (a, b) -> assertTrue(b >= a && b - a < 0.02f) }
        assertEquals(1f, h.port.volume, 0f)
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(60 * 60L - 5, h.state.timerRemainingSec)
    }

    @Test
    fun aShorterTimerChosenDuringTheNightEndsItEarlier() {
        val h = night(180)
        h.advance(10 * minute)
        h.engine.setTimerMinutes(30)
        h.advance(30 * minute + 100)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
    }
}
