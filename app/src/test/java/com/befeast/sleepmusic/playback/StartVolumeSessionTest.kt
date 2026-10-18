package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeAudioControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 2 / AC-2: every session unmutes STREAM_MUSIC and applies the start volume (default 15 of 25). */
class StartVolumeSessionTest {
    private fun audioCalls(h: EngineHarness) = h.log.filter { it.startsWith("audio.") }

    @Test
    fun aMutedStreamIsUnmutedAndSetToFifteenOfTwentyFive() {
        val h = EngineHarness()
        h.audio.muted = true
        h.audio.current = 3
        h.launcherStart()
        assertEquals(listOf("audio.unmute", "audio.setIndex(15)"), audioCalls(h))
        assertFalse(h.audio.isMuted())
        assertEquals(15, h.audio.index())
        val result = h.events.startVolumes.single()
        assertEquals(StartVolumeResult(25, 0, true, 15, 15, false, false, null), result)
    }

    @Test
    fun anUnmutedStreamIsNotSentAMuteAdjustment() {
        val h = EngineHarness()
        h.audio.current = 22
        h.launcherStart()
        assertEquals(listOf("audio.setIndex(15)"), audioCalls(h))
        assertEquals("set exactly, also downwards", 15, h.audio.index())
    }

    @Test
    fun theLevelIsScaledOnAnotherMax() {
        val log = mutableListOf<String>()
        val audio = FakeAudioControl(log, maxIndex = 15)
        assertEquals(9, StartVolumeSequence.apply(audio, 15).target)
        assertEquals("audio.setIndex(9)", log.single())
    }

    @Test
    fun theStoredLevelIsUsed() {
        val h = EngineHarness()
        h.engine.setStartVolumeLevel(18)
        h.launcherStart()
        assertEquals(18, h.audio.index())
        assertEquals(18, h.store.startVolumeLevel)
    }

    @Test
    fun anInvalidLevelFallsBackToTheDefault() {
        val h = EngineHarness()
        h.engine.setStartVolumeLevel(40)
        assertEquals(15, h.store.startVolumeLevel)
    }

    @Test
    fun appliedAtEverySessionStartAndNeverDuringOne() {
        val h = EngineHarness()
        h.launcherStart()
        h.advance(40_000)
        h.audio.systemChange(7) // the listener turns it down with the remote
        h.engine.togglePause(CommandSource.Remote)
        h.advance(2_000)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(3_000)
        h.engine.skip(CommandSource.Remote)
        h.advance(1_000)
        h.engine.setTimerMinutes(30)
        h.advance(25 * 60_000L) // into the sleep fade
        assertEquals(listOf("audio.setIndex(15)"), audioCalls(h))
        assertEquals("the remote's change stays", 7, h.audio.index())

        h.engine.stop(CommandSource.MediaSession)
        h.audio.systemChange(0, muted = true)
        h.launcherStart()
        assertEquals(listOf("audio.setIndex(15)", "audio.unmute", "audio.setIndex(15)"), audioCalls(h))
        assertEquals(15, h.audio.index())
    }

    @Test
    fun aRefusedVolumeChangeIsRecordedAndTheSessionStillStarts() {
        val h = EngineHarness()
        h.audio.muted = true
        h.audio.denyChanges = true
        h.launcherStart()
        assertEquals(Phase.Playing, h.state.phase)
        val result = h.events.startVolumes.single()
        assertNotNull(result.error)
        assertTrue(result.mutedAfter)
    }

    @Test
    fun aFixedVolumeStreamIsReported() {
        val log = mutableListOf<String>()
        val result = StartVolumeSequence.apply(FakeAudioControl(log, isFixed = true), 15)
        assertTrue(result.fixed)
        assertNull(result.error)
    }
}
