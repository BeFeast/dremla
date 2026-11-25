package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.Tracks
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 4, 8: the observable session state the night screen (#5) and the UI (#6) render. */
class SessionStateTest {
    private val minute = 60_000L

    @Test
    fun idleBeforeAnySession() {
        val h = EngineHarness()
        assertEquals(SessionState(timerMinutes = 120, collectionLoaded = true, playableCount = 6), h.state)
        assertFalse(h.state.isActive)
    }

    @Test
    fun phasesThroughANight() {
        val h = EngineHarness(tracks = listOf(Tracks.track("pno-001", durationSec = 4 * 3_600)))
        h.engine.setTimerMinutes(30)
        h.launcherStart()
        val playing = h.state
        assertEquals(Phase.Playing, playing.phase)
        assertEquals("Title pno-001", playing.track!!.title)
        assertEquals("Artist pno-001", playing.track!!.artist)
        assertEquals("piano", playing.track!!.category)
        assertEquals(30 * 60L, playing.timerRemainingSec)
        assertEquals(4 * 3_600L, playing.trackRemainingSec)
        assertTrue(playing.isActive)

        h.advance(minute)
        assertEquals(29 * 60L, h.state.timerRemainingSec)
        assertEquals(4 * 3_600L - 60, h.state.trackRemainingSec)

        h.engine.togglePause(CommandSource.Remote)
        assertEquals(Phase.Paused, h.state.phase)
        h.advance(5_000)
        assertEquals("the track clock stops while paused", 4 * 3_600L - 61, h.state.trackRemainingSec)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals(Phase.Playing, h.state.phase)

        h.advance(20 * minute)
        assertEquals(Phase.Fading, h.state.phase)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals("paused shows Paused inside the fade", Phase.Paused, h.state.phase)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals(Phase.Fading, h.state.phase)

        h.advance(10 * minute)
        val finished = h.state
        assertEquals(Phase.Finished, finished.phase)
        assertEquals(EndReason.TimerEnded, finished.endReason)
        assertNull(finished.track)
        assertEquals(0L, finished.timerRemainingSec)
        assertEquals(1, finished.sessionId)
        assertFalse(finished.isActive)
    }

    @Test
    fun noEmissionWithoutAVisibleChange() {
        val h = EngineHarness(tracks = listOf(Tracks.track("amb-001", durationSec = 4 * 3_600)))
        h.launcherStart()
        h.advance(40_000)
        val seen = mutableListOf<SessionState>()
        val job = h.scope.launch { h.engine.state.collect { seen += it } }
        h.settle()
        seen.clear()
        h.advance(10_000) // 200 ticks
        job.cancel()
        assertTrue("about one emission per second, got ${seen.size}", seen.size in 9..21)
        assertEquals(seen.size, seen.distinct().size)
    }

    @Test
    fun theTimerEndTimeIsPublishedAndAnOnTimeEndIsNotLate() {
        val h = EngineHarness(tracks = listOf(Tracks.track("amb-001", durationSec = 4 * 3_600)))
        h.engine.setTimerMinutes(30)
        val start = h.now
        h.launcherStart()
        assertEquals(start + 30 * minute, h.state.timerEndAtMs)
        h.advance(30 * minute + PlaybackEngine.TICK_MS)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertEquals(start + 30 * minute, h.state.timerEndAtMs)
        assertTrue("ended within one tick: ${h.state.timerEndLateMs}", h.state.timerEndLateMs <= PlaybackEngine.TICK_MS)
    }

    @Test
    fun aPausedSessionWhoseBoxSleptThroughTheEndFinishesLate() {
        // A paused player holds no wake lock: the box can sleep through the end and only the first
        // tick after it wakes ends the night. #5 must not run the sleep route for that end.
        val h = EngineHarness(tracks = listOf(Tracks.track("amb-001", durationSec = 4 * 3_600)))
        h.engine.setTimerMinutes(30)
        h.launcherStart()
        h.advance(minute)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(2_000)
        h.sleepThrough(7 * 60 * minute)
        assertEquals(Phase.Paused, h.state.phase)
        h.advance(PlaybackEngine.TICK_MS)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("late by ${h.state.timerEndLateMs} ms", h.state.timerEndLateMs > 6 * 60 * minute)
    }

    @Test
    fun onlyATimerEndCanBeLate() {
        val h = EngineHarness()
        h.launcherStart()
        h.sleepThrough(10 * 60 * minute)
        h.engine.stop(CommandSource.MediaSession)
        assertEquals(EndReason.Stopped, h.state.endReason)
        assertEquals(0L, h.state.timerEndLateMs)
    }

    @Test
    fun stopShowsFinishedStopped() {
        val h = EngineHarness()
        h.launcherStart()
        h.engine.stop(CommandSource.MediaSession)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.Stopped, h.state.endReason)
        assertEquals(6, h.state.playableCount)
    }
}
