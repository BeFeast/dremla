package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.ui.nightPlaceholder
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NightSessionTest {
    private class Route(var enabled: Boolean = true) : SleepRoute {
        var calls = 0
        var beforeSleep: () -> Unit = {}
        override fun status() = if (enabled) SleepRouteStatus.Accessibility else SleepRouteStatus.NotEnabled
        override fun sleep(): Boolean { calls++; beforeSleep(); return enabled }
    }

    @Test fun blackAtTenSecondsEvenWhenPausedAndRefreshFails() {
        val h = EngineHarness()
        h.launcherStart()
        h.advance(9_950)
        assertFalse(h.state.blackScreen)
        h.engine.pause(CommandSource.Remote)
        h.advance(50)
        assertTrue(h.state.blackScreen)
        assertNull(nightPlaceholder(h.state, "Refresh failed").message)
        assertEquals(Phase.Paused, h.state.phase)
        h.engine.play(CommandSource.Remote)
        h.advance(1_000)
        assertTrue(h.state.blackScreen)
        assertEquals(Phase.Playing, h.state.phase)
    }

    @Test fun blackTenSecondsAfterTheMusicIsHeardNotAfterTheSessionStarts() {
        // #88: a slow first load turned the screen black before anything was audible.
        val h = EngineHarness()
        h.port.reportPlayingOnPlay = false
        h.launcherStart()
        h.advance(12_000)
        assertFalse(h.state.blackScreen)
        assertTrue(h.state.startingAudio)
        h.port.becomeAudible()
        h.advance(9_950)
        assertFalse(h.state.blackScreen)
        assertFalse(h.state.startingAudio)
        h.engine.pause(CommandSource.Remote)
        h.advance(50)
        assertTrue(h.state.blackScreen)
    }

    @Test fun aNightPausedBeforeItsFirstAudioIsBlackAfterAMinute() {
        // #88: black follows the first audio, but a paused or stalled first load never keeps the panel lit.
        val h = EngineHarness()
        h.port.reportPlayingOnPlay = false
        h.launcherStart()
        h.engine.pause(CommandSource.Remote)
        h.advance(59_950)
        assertFalse(h.state.blackScreen)
        h.advance(50)
        assertTrue(h.state.blackScreen)
    }

    @Test fun twoHoursContinueOnBlackThenStopBeforeOneSleepRequest() {
        val route = Route()
        val h = EngineHarness(sleepRoute = route)
        route.beforeSleep = {
            assertEquals(Phase.Finished, h.state.phase)
            assertEquals(1, h.host.ended)
            assertEquals(0f, h.port.volume)
        }
        h.launcherStart()
        h.advance(7_199_950)
        assertEquals(Phase.Fading, h.state.phase)
        assertTrue(h.state.blackScreen)
        assertEquals(0, route.calls)
        h.advance(50)
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue(h.state.closeActivity)
        assertEquals(1, route.calls)
        for (source in CommandSource.entries) {
            h.engine.play(source); h.engine.togglePause(source); h.engine.skip(source)
        }
        h.engine.refreshSleepRoute()
        h.advance(60_000)
        assertEquals(1, route.calls)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(1, h.played.size)
    }

    @Test fun disabledTvOffAndMissingOrFailedRouteStayBlack() {
        for (tvOff in listOf(false, true)) {
            val route = Route(enabled = false)
            val h = EngineHarness(sleepRoute = route)
            h.engine.setTvOff(tvOff)
            assertEquals(tvOff, h.store.tvOff)
            h.launcherStart()
            h.sleepThrough(7_200_000)
            h.advance(50)
            assertEquals(if (tvOff) 1 else 0, route.calls)
            assertTrue(h.state.blackScreen)
            assertFalse(h.state.closeActivity)
            assertEquals(SleepRouteStatus.NotEnabled, h.state.sleepRouteStatus)
            assertNull(nightPlaceholder(h.state, "error").message)
        }
    }

    @Test fun tvOffChangedDuringNightPreventsAnAvailableRouteAndNeverReplays() {
        val route = Route()
        val h = EngineHarness(sleepRoute = route)
        h.launcherStart()
        h.engine.setTvOff(false)
        h.sleepThrough(7_200_000)
        h.advance(50)
        assertTrue(h.state.blackScreen)
        assertFalse(h.state.closeActivity)
        assertEquals(0, route.calls)
        h.engine.setTvOff(true)
        h.engine.refreshSleepRoute()
        h.advance(50)
        assertEquals(0, route.calls)
    }

    @Test fun lateTimerThresholdIsInclusiveAndMorningNeverLocks() {
        for (late in listOf(30_000L, 30_001L, 8 * 60 * 60_000L)) {
            val route = Route()
            val h = EngineHarness(sleepRoute = route)
            h.launcherStart()
            h.sleepThrough(7_200_000 + late - 50)
            h.advance(50)
            assertEquals(late, h.state.timerEndLateMs)
            assertEquals(if (late <= 30_000) 1 else 0, route.calls)
            assertTrue(h.state.closeActivity)
            assertEquals(Phase.Finished, h.state.phase)
        }
    }

    @Test fun pausedWakeWithinGraceNeverLocksInEitherCallbackOrder() {
        for (tickFirst in listOf(false, true)) {
            val route = Route()
            val h = EngineHarness(sleepRoute = route)
            h.launcherStart()
            h.engine.pause(CommandSource.Remote)
            h.advance(1_500)
            h.sleepThrough(7_208_450)
            // onResume only refreshes capability; no transport command is sent.
            if (tickFirst) h.advance(50)
            h.engine.refreshSleepRoute()
            if (!tickFirst) h.advance(50)
            assertEquals(10_000L, h.state.timerEndLateMs)
            assertEquals(Phase.Finished, h.state.phase)
            assertFalse(h.port.playing)
            assertEquals(0, route.calls)
            assertTrue(h.state.closeActivity)
        }
    }

    @Test fun lateDismissalRequiresTvOffAndAvailableRouteAndCannotCloseNewNight() {
        for (enabled in listOf(false, true)) for (tvOff in listOf(false, true)) {
            val route = Route(enabled)
            val h = EngineHarness(sleepRoute = route)
            h.engine.setTvOff(tvOff)
            h.launcherStart()
            h.sleepThrough(7_240_000)
            h.advance(50)
            assertEquals(tvOff && enabled, h.state.closeActivity)
            assertTrue(h.state.blackScreen)
            assertEquals(0, route.calls)
            h.launcherStart()
            h.engine.refreshSleepRoute()
            h.advance(50)
            assertEquals(2, h.state.sessionId)
            assertTrue(h.state.isActive)
            assertFalse(h.state.closeActivity)
        }
    }

    @Test fun stopFailureAndProcessReplacementNeverSleepOrAutoplay() {
        val route = Route()
        val h = EngineHarness(sleepRoute = route)
        h.launcherStart()
        h.engine.stop(CommandSource.System)
        h.advance(7_300_000)
        assertEquals(0, route.calls)
        val replacement = EngineHarness(store = h.store, sleepRoute = route)
        replacement.engine.play(CommandSource.MediaSession)
        replacement.advance(7_300_000)
        assertEquals(Phase.Idle, replacement.state.phase)
        assertTrue(replacement.played.isEmpty())
        assertEquals(0, route.calls)
    }

    @Test fun processReplacementDuringActiveNightRestoresNoSession() {
        val route = Route()
        val old = EngineHarness(sleepRoute = route)
        old.launcherStart()
        old.advance(10_000)
        assertTrue(old.state.isActive)
        // Discard the running process: only its preferences survive.
        val replacement = EngineHarness(store = old.store, sleepRoute = route)
        replacement.engine.play(CommandSource.System)
        replacement.advance(7_200_000)
        assertEquals(Phase.Idle, replacement.state.phase)
        assertTrue(replacement.played.isEmpty())
        assertEquals(0, route.calls)
    }

    @Test fun wakeCommandBeforeFirstTickCannotResumeExpiredAudioOrLock() {
        for (command in listOf<(PlaybackEngine) -> Unit>(
            { it.play(CommandSource.MediaSession) },
            { it.togglePause(CommandSource.Remote) },
            { it.skip(CommandSource.System) },
        )) {
            val route = Route()
            val h = EngineHarness(sleepRoute = route)
            h.launcherStart()
            h.engine.pause(CommandSource.Remote)
            h.advance(1_500)
            assertFalse(h.port.playing)
            h.sleepThrough(7_200_000)
            command(h.engine)
            assertFalse(h.port.playing)
            assertEquals(Phase.Finished, h.state.phase)
            h.advance(50)
            assertEquals(0, route.calls)
        }
    }

    @Test fun playbackFailureAndEmptyQueueNeverLock() {
        for (fail in listOf(true, false)) {
            val route = Route()
            val h = EngineHarness(sleepRoute = route)
            h.launcherStart()
            if (fail) {
                h.port.reportPlayingOnPlay = false
                repeat(3) { h.port.fail() }
            }
            else h.port.listener?.onEnded()
            assertEquals(if (fail) EndReason.PlaybackFailed else EndReason.NothingToPlay, h.state.endReason)
            assertEquals(0, route.calls)
            assertTrue(h.state.blackScreen)
        }
    }

    @Test fun explicitLauncherAfterAnUntickedExpiredNightStartsANewNightWithoutLocking() {
        val route = Route()
        val h = EngineHarness(sleepRoute = route)
        h.launcherStart()
        h.engine.pause(CommandSource.Remote)
        h.sleepThrough(8 * 60 * 60_000L)
        val closeStates = mutableListOf<Boolean>()
        val observer = h.scope.launch(Dispatchers.Unconfined) {
            h.engine.state.collect { closeStates += it.closeActivity }
        }
        h.launcherStart()
        observer.cancel()
        assertFalse(closeStates.any { it })
        assertEquals(2, h.state.sessionId)
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(0, route.calls)
        assertFalse(h.state.blackScreen)
        assertFalse(h.state.closeActivity)
        h.advance(50)
        assertFalse(h.state.closeActivity)
    }

    @Test fun changingTimerAfterAnUntickedExpiredNightDoesNotRearmIt() {
        val route = Route()
        val h = EngineHarness(sleepRoute = route)
        h.launcherStart()
        h.sleepThrough(8 * 60 * 60_000L)
        h.engine.setTimerMinutes(30)
        h.engine.play(CommandSource.MediaSession)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(30, h.state.timerMinutes)
        assertEquals(0, route.calls)
        assertFalse(h.port.playing)
    }

    @Test fun newNightResetsBlackAndCloseState() {
        val h = EngineHarness(sleepRoute = Route())
        h.launcherStart()
        h.sleepThrough(7_200_000)
        h.advance(50)
        assertTrue(h.state.closeActivity)
        h.launcherStart()
        assertFalse(h.state.blackScreen)
        assertFalse(h.state.closeActivity)
        assertEquals(2, h.state.sessionId)
    }
}
