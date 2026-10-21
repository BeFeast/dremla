package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A night requested by TX10 Clock: its music minutes, never the TV off, closes for Clock at the end. */
class ClockSessionTest {
    private class CountingSleepRoute : SleepRoute {
        var sleeps = 0
        override fun status() = SleepRouteStatus.DeviceAdmin
        override fun sleep(): Boolean { sleeps++; return true }
    }

    private fun request(h: EngineHarness, id: String = "req-1", minutes: Int = 45, mode: String = "day",
                        windowEndMs: Long = h.wallNow + 8 * 3_600_000L) = ClockSessionRequest(mode, minutes, windowEndMs, id)

    @Test
    fun aClockNightPlaysItsMusicMinutesNeverTurnsTheTvOffAndClosesForClock() {
        val store = InMemoryPlaybackStore().apply { tvOff = true; timerMinutes = 120 }
        val route = CountingSleepRoute()
        val h = EngineHarness(store = store, sleepRoute = route)
        assertNull(h.engine.startClockSession(request(h)))
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertFalse("a Clock night never turns the TV off", h.state.tvOff)
        assertEquals(45 * 60L, h.state.timerRemainingSec)
        h.advance(45 * 60_000L + 1_000L)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("the night closes so Clock's protected screen remains", h.state.closeActivity)
        assertEquals(0, route.sleeps)
        assertEquals("the listener's own timer choice is untouched", 120, store.timerMinutes)
        assertTrue("store keeps TV-off for normal nights", store.tvOff)
    }

    @Test
    fun invalidRepeatedOrConcurrentRequestsAreRefusedAndPlayNothing() {
        val h = EngineHarness()
        assertEquals("unknown mode", h.engine.startClockSession(request(h, mode = "nap")))
        assertEquals("invalid music duration", h.engine.startClockSession(request(h, minutes = 50)))
        assertEquals("sleep window already ended", h.engine.startClockSession(request(h, windowEndMs = h.wallNow)))
        assertEquals("sleep window more than a day away",
            h.engine.startClockSession(request(h, windowEndMs = h.wallNow + 25 * 3_600_000L)))
        assertEquals("missing request id", h.engine.startClockSession(request(h, id = " ")))
        h.settle()
        assertEquals(Phase.Idle, h.state.phase)
        assertTrue(h.log.isEmpty())
        assertNull(h.engine.startClockSession(request(h, id = "a")))
        h.settle()
        assertEquals("a night is already playing", h.engine.startClockSession(request(h, id = "b")))
        h.engine.stop(CommandSource.Remote)
        h.settle()
        assertEquals("repeated request", h.engine.startClockSession(request(h, id = "a")))
        h.settle()
        assertEquals(1, h.state.sessionId)
    }

    @Test
    fun theWindowEndIsEpochTimeWhileTheMusicTimerStaysMonotonic() {
        // Production: elapsed realtime for timers, epoch from Clock for the window. Different scales.
        val h = EngineHarness()
        assertTrue("the scales differ by far more than a day", h.wallNow - h.now > 365L * 24 * 3_600_000L)
        assertEquals("an elapsed-scale window end is in the past on the wall clock",
            "sleep window already ended", h.engine.startClockSession(request(h, id = "elapsed", windowEndMs = h.now + 3_600_000L)))
        assertNull(h.engine.startClockSession(request(h, id = "epoch", minutes = 30, windowEndMs = h.wallNow + 8 * 3_600_000L)))
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(h.now + 30 * 60_000L, h.state.timerEndAtMs)
    }

    @Test
    fun onlyTheMatchingRequestIdStopsTheNight() {
        val h = EngineHarness()
        h.engine.startClockSession(request(h, id = "a"))
        h.settle()
        h.engine.stopClockSession("other")
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        h.engine.stopClockSession("a")
        h.settle()
        assertEquals(Phase.Finished, h.state.phase)
        assertTrue(h.state.closeActivity)
    }

    @Test
    fun aRequestWaitsForTheCollectionAndACancelledOneNeverPlaysLater() {
        val waiting = EngineHarness(loaded = false)
        assertNull(waiting.engine.startClockSession(request(waiting, minutes = 30)))
        waiting.settle()
        assertEquals(Phase.Idle, waiting.state.phase)
        waiting.source.emit(waiting.collection)
        waiting.settle()
        assertEquals(Phase.Playing, waiting.state.phase)
        assertEquals(30 * 60L, waiting.state.timerRemainingSec)

        val store = InMemoryPlaybackStore().apply { timerMinutes = 90 }
        val cancelled = EngineHarness(store = store, loaded = false)
        cancelled.engine.startClockSession(request(cancelled, minutes = 30))
        cancelled.engine.cancelPendingStart()
        cancelled.source.emit(cancelled.collection)
        cancelled.settle()
        assertEquals(Phase.Idle, cancelled.state.phase)
        cancelled.launcherStart()
        assertEquals("a later launcher start uses the listener's timer", 90 * 60L, cancelled.state.timerRemainingSec)
    }
}
