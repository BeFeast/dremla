package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 1-2 / AC-2: one press starts a random selected track, audible, with a 30 s fade-in. */
class SessionStartTest {
    @Test
    fun startOrderSilencesThePlayerSetsTheStreamThenPlaysThenStartsTheService() {
        val h = EngineHarness()
        h.audio.muted = true
        h.launcherStart()
        val first = h.played.single()
        val upNext = h.port.upNext!!.trackId
        assertEquals(
            listOf(
                "port.setVolume(0.0)",
                "audio.unmute",
                "audio.setIndex(15)",
                "port.load($first, $upNext)",
                "host.sessionStarted",
            ),
            h.log,
        )
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(1, h.state.sessionId)
    }

    @Test
    fun theFirstTrackIsARandomDrawFromTheSelection() {
        val tracks = Tracks.many(10)
        val selection = listOf("amb-002", "amb-004", "amb-007", "amb-009")
        val firsts = (1..40).map { seed ->
            val h = EngineHarness(tracks = tracks, selection = selection, seed = seed)
            h.launcherStart()
            h.played.single()
        }
        assertTrue(firsts.all { it in selection })
        assertTrue("different seeds start different tracks: ${firsts.toSet()}", firsts.toSet().size >= 3)
    }

    @Test
    fun fadeInLasts30SecondsFromTheFirstAudibleMoment() {
        val h = EngineHarness(tracks = listOf(Tracks.track("amb-001", durationSec = 3_600)))
        h.port.reportPlayingOnPlay = false
        h.launcherStart()
        h.advance(5_000)
        assertEquals("silent until audio really plays", 0f, h.port.volume, 0f)
        h.port.becomeAudible()
        h.advance(15_000)
        assertEquals(0.125f, h.port.volume, 1e-3f) // cubic: (15/30)^3
        h.advance(14_000)
        assertTrue(h.port.volume < 1f)
        h.advance(1_050)
        assertEquals(1f, h.port.volume, 0f)
        val volumes = h.port.volumes.map { it.second }
        volumes.zipWithNext().forEach { (a, b) -> assertTrue("the fade-in only rises", b >= a) }
    }

    @Test
    fun everyPassiveVerdictLeavesTheEngineIdle() {
        // Process restart, configuration change, recents, open UI and explicit starts all arrive as
        // a passive verdict; none starts a session, and none leaves a start pending for a later load.
        val passive = LaunchReason.entries - LaunchReason.LauncherStart
        LaunchEntry.entries.forEach { entry ->
            passive.forEach { reason ->
                listOf(true, false).forEach { loaded ->
                    val h = EngineHarness(loaded = loaded)
                    val verdict = LaunchVerdict(entry, reason)
                    h.engine.onUiEntry(verdict)
                    h.advance(60_000)
                    if (!loaded) {
                        h.source.emit(h.collection)
                        h.advance(60_000)
                    }
                    val what = "$entry $reason, collection loaded first: $loaded"
                    assertEquals(what, Phase.Idle, h.state.phase)
                    assertEquals(what, 0, h.state.sessionId)
                    assertTrue("$what: ${h.log}", h.log.isEmpty())
                    assertEquals(what, 0, h.host.started)
                    assertEquals(what, listOf(verdict), h.events.verdicts)
                }
            }
        }
    }

    @Test
    fun anActiveSessionIgnoresAnotherLauncherStart() {
        val h = EngineHarness()
        h.launcherStart()
        h.advance(60_000)
        h.launcherStart()
        assertEquals(1, h.log.count { it.startsWith("port.load") })
        assertEquals(1, h.host.started)
        assertEquals(1, h.state.sessionId)
    }

    @Test
    fun aLauncherStartResumesPausedSessionAfterRestartingHost() {
        val h = EngineHarness()
        h.launcherStart()
        h.engine.pause(CommandSource.Remote)
        h.advance(2_000)
        val before = h.state
        h.log.clear()
        h.launcherStart()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(before.sessionId, h.state.sessionId)
        assertEquals(before.track, h.state.track)
        assertEquals(before.timerEndAtMs, h.state.timerEndAtMs)
        assertEquals(listOf("host.sessionResumed", "port.play"), h.log)
    }

    @Test
    fun anUnmatchedOrBlockedSelectionNeverFallsBackToOtherTracks() {
        listOf(emptyList(), listOf("missing"), listOf("amb-001")).forEach { selection ->
            val h = EngineHarness(tracks = Tracks.many(3), selection = selection)
            h.store.block("amb-001")
            h.launcherStart()
            assertEquals(0, h.state.playableCount)
            assertEquals(Phase.Idle, h.state.phase)
            assertTrue(h.log.isEmpty())
        }
    }

    @Test
    fun blockingTheLastSelectedTrackEndsInsteadOfPlayingOutsideSelection() {
        val h = EngineHarness(tracks = Tracks.many(3), selection = listOf("amb-001"))
        h.launcherStart()
        h.engine.neverAgain()
        h.advance(1_000)
        assertEquals(0, h.state.playableCount)
        assertEquals(EndReason.NothingToPlay, h.state.endReason)
        assertEquals(listOf("amb-001"), h.played)
    }

    @Test
    fun aLauncherStartBeforeTheCollectionLoadsPlaysOnceItIsLoaded() {
        val h = EngineHarness(loaded = false)
        h.launcherStart()
        assertEquals(Phase.Idle, h.state.phase)
        assertTrue(h.log.isEmpty())
        h.advance(20_000)
        h.source.emit(h.collection)
        h.settle()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(1, h.host.started)
    }

    @Test
    fun stopDropsAPendingStart() {
        val h = EngineHarness(loaded = false)
        h.launcherStart()
        h.engine.stop(CommandSource.System)
        h.source.emit(h.collection)
        h.settle()
        assertEquals(Phase.Idle, h.state.phase)
        assertTrue(h.log.isEmpty())
    }

    @Test
    fun anEmptyCollectionLeavesTheEngineIdleAndTouchesNothing() {
        val h = EngineHarness(tracks = emptyList())
        h.launcherStart()
        h.advance(60_000)
        assertEquals(Phase.Idle, h.state.phase)
        assertTrue(h.state.collectionLoaded)
        assertEquals(0, h.state.playableCount)
        assertTrue(h.log.isEmpty())
    }

    @Test
    fun everythingBlockedIsLikeAnEmptyCollection() {
        val h = EngineHarness(tracks = Tracks.many(2))
        h.store.block("amb-001")
        h.store.block("amb-002")
        h.launcherStart()
        assertEquals(Phase.Idle, h.state.phase)
        assertEquals(0, h.state.playableCount)
        assertTrue(h.log.isEmpty())
    }

    @Test
    fun aFinishedSessionStartsAgainOnlyFromTheLauncher() {
        val h = EngineHarness()
        h.launcherStart()
        h.engine.stop(CommandSource.MediaSession)
        assertEquals(Phase.Finished, h.state.phase)
        h.launcherStart()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(2, h.state.sessionId)
        assertEquals(2, h.host.started)
    }
}
