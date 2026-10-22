package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import com.befeast.sleepmusic.playback.testing.Tracks
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
    fun theMenuShowsTheClockNightsMinutesAndTheListenersTimerAfterIt() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120 }
        val h = EngineHarness(store = store)
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals("the menu's dot and current label show the night's minutes", 45, h.state.timerMinutes)
        h.engine.pause(CommandSource.Remote)
        h.settle()
        assertEquals(Phase.Paused, h.state.phase)
        assertEquals(45, h.state.timerMinutes)
        h.engine.play(CommandSource.Remote)
        h.advance(36 * 60_000L)
        assertEquals(Phase.Fading, h.state.phase)
        assertEquals(45, h.state.timerMinutes)
        h.advance(10 * 60_000L)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals("after the Clock night the menu shows the listener's timer", 120, h.state.timerMinutes)
    }

    @Test
    fun aPickDuringAClockNightChangesOnlyThatNight() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120 }
        val h = EngineHarness(store = store)
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.settle()
        h.advance(5 * 60_000L)
        val sessionId = h.state.sessionId
        val track = h.state.track?.id
        val starts = h.events.startVolumes.size
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(90, h.state.timerMinutes)
        assertEquals("the same Clock night, not a new one", sessionId, h.state.sessionId)
        assertEquals(track, h.state.track?.id)
        assertEquals(starts, h.events.startVolumes.size)
        assertFalse(h.state.tvOff)
        assertEquals("the pick re-arms this night from now", h.now + 90 * 60_000L, h.state.timerEndAtMs)
        assertEquals("the listener's own timer is untouched", 120, store.timerMinutes)
        h.advance(90 * 60_000L + 1_000L)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("still a Clock night: it closes for Clock", h.state.closeActivity)
        assertEquals(120, h.state.timerMinutes)
        assertEquals(120, store.timerMinutes)
    }

    @Test
    fun aPickArrivingAfterTheClockNightExpiredLeavesTheListenersTimer() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120 }
        val h = EngineHarness(store = store)
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.settle()
        h.engine.pause(CommandSource.Remote)
        h.settle()
        // The box sleeps past the end; the pick reaches the engine before the first tick after wake.
        h.sleepThrough(45 * 60_000L + 1_000L)
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("closes for Clock", h.state.closeActivity)
        assertEquals("the menu shows the listener's timer", 120, h.state.timerMinutes)
        assertEquals(120, store.timerMinutes)
    }

    @Test
    fun aLauncherNightStillPersistsAndShowsThePick() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120 }
        val h = EngineHarness(store = store)
        h.launcherStart()
        assertEquals(120, h.state.timerMinutes)
        h.advance(5 * 60_000L)
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals(90, h.state.timerMinutes)
        assertEquals(90, store.timerMinutes)
        assertEquals(h.now + 90 * 60_000L, h.state.timerEndAtMs)
    }

    @Test
    fun theTvOffRowInAClockNightShowsTheNightAndAPickWritesNothing() {
        // #69: the menu row reads "off" for a Clock night; MainActivity sends setTvOff(!state.tvOff) = true.
        for (saved in listOf(false, true)) {
            val store = InMemoryPlaybackStore().apply { tvOff = saved }
            val h = EngineHarness(store = store)
            assertNull(h.engine.startClockSession(request(h, minutes = 45)))
            h.settle()
            assertFalse("a Clock night never turns the TV off", h.state.tvOff)
            h.engine.setTvOff(!h.state.tvOff)
            h.settle()
            assertFalse("the row still shows the night's off", h.state.tvOff)
            assertEquals("the listener's own TV-off setting is untouched", saved, store.tvOff)
            h.advance(45 * 60_000L + 1_000L)
            assertEquals(Phase.Finished, h.state.phase)
            assertTrue("still closes for Clock", h.state.closeActivity)
            assertEquals(saved, store.tvOff)
            h.launcherStart()
            assertEquals("a later launcher night uses the listener's setting", saved, h.state.tvOff)
        }
    }

    @Test
    fun aLauncherNightStillTogglesAndPersistsTvOff() {
        val store = InMemoryPlaybackStore().apply { tvOff = false }
        val h = EngineHarness(store = store)
        h.launcherStart()
        h.engine.setTvOff(true)
        h.settle()
        assertTrue(h.state.tvOff)
        assertTrue(store.tvOff)
    }

    @Test
    fun whileAClockRequestWaitsPicksWriteNothingAndTheMenuShowsTheNight() {
        // #70: first run, the collection is not ready yet; the accepted request waits in pendingClock.
        for (saved in listOf(false, true)) {
            val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = saved }
            val h = EngineHarness(store = store, loaded = false)
            assertNull(h.engine.startClockSession(request(h, minutes = 45)))
            h.settle()
            h.engine.setTimerMinutes(90)
            h.engine.setTvOff(!h.state.tvOff)
            h.settle()
            assertEquals("the listener's timer is untouched", 120, store.timerMinutes)
            assertEquals("the listener's TV-off setting is untouched", saved, store.tvOff)
            assertEquals(Phase.Idle, h.state.phase)
            assertEquals("the waiting night's minutes", 45, h.state.timerMinutes)
            assertFalse("the waiting night never turns the TV off", h.state.tvOff)
            h.source.emit(h.collection)
            h.settle()
            assertEquals(Phase.Playing, h.state.phase)
            assertEquals("the Clock request keeps its own minutes", 45 * 60L, h.state.timerRemainingSec)
            assertEquals(45, h.state.timerMinutes)
            assertEquals(120, store.timerMinutes)
            assertEquals(saved, store.tvOff)
        }
    }

    @Test
    fun aWaitingClockRequestDroppedByStopOrSourceChangeFreesTheSettings() {
        for (drop in listOf("stop", "online")) {
            val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
            val h = EngineHarness(store = store, loaded = false)
            assertNull(h.engine.startClockSession(request(h, minutes = 45)))
            h.settle()
            if (drop == "stop") h.engine.stop(CommandSource.System) else h.engine.setOnline(!h.state.online)
            h.settle()
            assertEquals(drop, 120, h.state.timerMinutes)
            assertTrue(drop, h.state.tvOff)
            h.engine.setTimerMinutes(90)
            h.engine.setTvOff(false)
            h.settle()
            assertEquals("$drop: a pick is the listener's again", 90, store.timerMinutes)
            assertFalse(drop, store.tvOff)
            assertEquals(drop, 90, h.state.timerMinutes)
            assertFalse(drop, h.state.tvOff)
        }
    }

    @Test
    fun aLauncherStartReplacingAWaitingClockRequestShowsTheListenersSettings() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
        val h = EngineHarness(store = store, loaded = false)
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.settle()
        h.launcherStart()
        assertEquals(120, h.state.timerMinutes)
        assertTrue(h.state.tvOff)
        assertFalse("a launcher start is not a Clock night", h.state.clockNight)
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals(90, store.timerMinutes)
        h.source.emit(h.collection)
        h.settle()
        assertEquals("the launcher night, not Clock's", 90 * 60L, h.state.timerRemainingSec)
    }

    @Test
    fun aClockRequestWithNothingEligibleLeavesTheListenersSettingsWorking() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
        val h = EngineHarness(store = store, tracks = Tracks.many(2), loaded = false)
        store.block("amb-001")
        store.block("amb-002")
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.source.emit(h.collection)
        h.settle()
        assertEquals("no night starts", Phase.Idle, h.state.phase)
        assertEquals(120, h.state.timerMinutes)
        assertTrue(h.state.tvOff)
        h.engine.setTimerMinutes(90)
        h.engine.setTvOff(false)
        h.settle()
        assertEquals(90, store.timerMinutes)
        assertFalse(store.tvOff)
    }

    @Test
    fun theStateMarksAClockNightAndAWaitingRequestOnly() {
        // #73: SessionState.clockNight drives the TV off row's «off · Clock night».
        val waiting = EngineHarness(loaded = false)
        assertFalse(waiting.state.clockNight)
        assertNull(waiting.engine.startClockSession(request(waiting, minutes = 45)))
        waiting.settle()
        assertTrue("a waiting Clock request", waiting.state.clockNight)
        waiting.source.emit(waiting.collection)
        waiting.settle()
        assertEquals(Phase.Playing, waiting.state.phase)
        assertTrue("a playing Clock night", waiting.state.clockNight)
        waiting.engine.pause(CommandSource.Remote)
        waiting.settle()
        assertTrue("a paused Clock night", waiting.state.clockNight)
        waiting.engine.stopClockSession("req-1")
        waiting.settle()
        assertFalse("after the Clock night", waiting.state.clockNight)
        waiting.launcherStart()
        assertEquals(Phase.Playing, waiting.state.phase)
        assertFalse("a launcher night", waiting.state.clockNight)

        val dropped = EngineHarness(loaded = false)
        assertNull(dropped.engine.startClockSession(request(dropped, minutes = 45)))
        dropped.settle()
        dropped.engine.cancelPendingStart()
        dropped.settle()
        assertFalse("a dropped request", dropped.state.clockNight)
    }

    @Test
    fun aWakeWhileTheRequestStillWaitsCancelsItAndNothingPlaysLater() {
        // #72: first run, Clock's «Я проснулся» sends the stop while Dremla is still loading.
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
        val h = EngineHarness(store = store, loaded = false)
        assertNull(h.engine.startClockSession(request(h, id = "night-a", minutes = 45)))
        h.settle()
        h.engine.stopClockSession("other")
        h.settle()
        assertTrue("another id leaves the waiting request", h.state.clockNight)
        h.engine.stopClockSession("night-a")
        h.settle()
        assertFalse(h.state.clockNight)
        h.source.emit(h.collection)
        h.advance(60_000L)
        assertEquals("no night starts after the wake", Phase.Idle, h.state.phase)
        assertEquals(0, h.state.sessionId)
        assertTrue("no player started", h.played.isEmpty())
        assertEquals("the menu shows the listener's settings again", 120, h.state.timerMinutes)
        assertTrue(h.state.tvOff)
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals(90, store.timerMinutes)

        // A stop for another id leaves the waiting request, which still plays its night.
        val other = EngineHarness(loaded = false)
        assertNull(other.engine.startClockSession(request(other, id = "night-b", minutes = 45)))
        other.settle()
        other.engine.stopClockSession("night-a")
        other.settle()
        assertTrue(other.state.clockNight)
        other.source.emit(other.collection)
        other.settle()
        assertEquals(Phase.Playing, other.state.phase)
        assertEquals(45 * 60L, other.state.timerRemainingSec)
    }

    @Test
    fun aWaitingClockRequestPastItsWindowEndNeverStarts() {
        // #75: first run, the download outlasts Clock's sleep window and no stop arrives.
        for (lateMs in listOf(0L, 30 * 60_000L)) {
            val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
            val h = EngineHarness(store = store, loaded = false)
            val windowEnd = h.wallNow + 60 * 60_000L
            assertNull(h.engine.startClockSession(request(h, minutes = 45, windowEndMs = windowEnd)))
            h.settle()
            h.advance(windowEnd - h.wallNow + lateMs)
            assertTrue("the wall clock reached the window end", h.wallNow >= windowEnd)
            h.source.emit(h.collection)
            h.advance(60_000L)
            assertEquals("late by $lateMs ms: no night after the window", Phase.Idle, h.state.phase)
            assertEquals(0, h.state.sessionId)
            assertTrue("no player started", h.played.isEmpty())
            assertFalse(h.state.clockNight)
            assertTrue("the collection is shown as loaded", h.state.collectionLoaded)
            assertEquals("the menu shows the listener's settings again", 120, h.state.timerMinutes)
            assertTrue(h.state.tvOff)
            h.engine.setTimerMinutes(90)
            h.settle()
            assertEquals(90, store.timerMinutes)
        }
    }

    @Test
    fun aWaitingRequestPastItsWindowFreesTheMenuAndClocksNextRequestBeforeTheCollection() {
        // #75 review: the collection is still missing after the window end (a stalled first download).
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
        val h = EngineHarness(store = store, loaded = false)
        val windowEnd = h.wallNow + 60 * 60_000L
        assertNull(h.engine.startClockSession(request(h, id = "late", minutes = 45, windowEndMs = windowEnd)))
        h.settle()
        h.advance(61 * 60_000L)
        assertTrue("still shown until something asks", h.state.clockNight)
        h.engine.refreshSleepRoute()   // the menu opens
        h.settle()
        assertFalse(h.state.clockNight)
        assertEquals(120, h.state.timerMinutes)
        assertTrue(h.state.tvOff)
        h.engine.setTimerMinutes(90)
        h.engine.setTvOff(false)
        h.settle()
        assertEquals(90, store.timerMinutes)
        assertFalse(store.tvOff)
        assertNull("Clock's next request is accepted", h.engine.startClockSession(
            request(h, id = "next", minutes = 30, windowEndMs = h.wallNow + 8 * 3_600_000L)))
        h.settle()
        assertTrue(h.state.clockNight)
        h.source.emit(h.collection)
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals("the new request's night", 30 * 60L, h.state.timerRemainingSec)
    }

    @Test
    fun aWaitingClockRequestStillInsideItsWindowStarts() {
        val h = EngineHarness(loaded = false)
        val windowEnd = h.wallNow + 60 * 60_000L
        assertNull(h.engine.startClockSession(request(h, minutes = 45, windowEndMs = windowEnd)))
        h.settle()
        h.advance(59 * 60_000L)
        h.source.emit(h.collection)
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(45 * 60L, h.state.timerRemainingSec)
        assertTrue(h.state.clockNight)
    }

    @Test
    fun aCancelledWaitingClockRequestShowsTheListenersSettingsAgain() {
        val store = InMemoryPlaybackStore().apply { timerMinutes = 120; tvOff = true }
        val h = EngineHarness(store = store, loaded = false)
        assertNull(h.engine.startClockSession(request(h, minutes = 45)))
        h.settle()
        h.engine.cancelPendingStart()
        h.settle()
        assertEquals(120, h.state.timerMinutes)
        assertTrue(h.state.tvOff)
        h.engine.setTimerMinutes(90)
        h.settle()
        assertEquals("with no Clock request a pick is the listener's again", 90, store.timerMinutes)
        assertEquals(90, h.state.timerMinutes)
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
