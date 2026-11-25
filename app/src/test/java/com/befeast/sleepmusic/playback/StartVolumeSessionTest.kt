package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeAudioControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 2 / AC-2 after #34: a session never raises (or otherwise changes) STREAM_MUSIC. It plays at
 * the household level the remote left and fades only the player gain within it.
 */
class StartVolumeSessionTest {
    private fun audioCalls(h: EngineHarness) = h.log.filter { it.startsWith("audio.") }

    private fun startAt(index: Int, muted: Boolean = false): EngineHarness {
        val h = EngineHarness()
        h.audio.systemChange(index, muted)
        h.launcherStart()
        return h
    }

    @Test
    fun aStreamFarBelowTheOldDefaultStaysWhereItIs() {
        // The #34 night: a 15-step box at 1 was raised to 9. Now 1 stays 1, on any max.
        for ((index, max) in listOf(1 to 15, 1 to 25, 3 to 25)) {
            val log = mutableListOf<String>()
            val audio = FakeAudioControl(log, maxIndex = max, current = index)
            val result = StartVolumeSequence.observe(audio)
            assertEquals(index, audio.index())
            assertEquals(StartVolumeResult(max, index, false, index, false, false), result)
            assertTrue(log.isEmpty())
        }
        val h = startAt(1)
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(1, h.audio.index())
        assertEquals(StartVolumeResult(25, 1, false, 1, false, false), h.events.startVolumes.single())
    }

    @Test
    fun theHouseholdLevelIsNotLoweredEither() {
        val h = startAt(22)
        assertEquals("kept exactly, no downward step", 22, h.audio.index())
        assertEquals(22, h.events.startVolumes.single().after)
    }

    @Test
    fun aMutedStreamStaysMutedAndTheSessionStillStarts() {
        val h = startAt(16, muted = true)
        assertEquals(Phase.Playing, h.state.phase)
        assertTrue(h.audio.isMuted())
        assertEquals(StartVolumeResult(25, 0, true, 0, true, false), h.events.startVolumes.single())
    }

    @Test
    fun aStreamAtZeroStaysAtZero() {
        val h = startAt(0)
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(0, h.audio.index())
        assertFalse(h.audio.isMuted())
        assertEquals(StartVolumeResult(25, 0, false, 0, false, false), h.events.startVolumes.single())
    }

    @Test
    fun observedAtEverySessionStartAndNeverChangedDuringOne() {
        val h = startAt(16)
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
        assertEquals("the remote's change stays", 7, h.audio.index())

        h.engine.stop(CommandSource.MediaSession)
        h.audio.systemChange(0, muted = true)
        h.launcherStart()
        assertEquals(0, h.audio.index())
        assertTrue(h.audio.isMuted())
        assertEquals(listOf(16, 0), h.events.startVolumes.map { it.before })
        assertTrue(audioCalls(h).isEmpty())
    }

    @Test
    fun aFixedVolumeStreamIsReported() {
        val log = mutableListOf<String>()
        val result = StartVolumeSequence.observe(FakeAudioControl(log, isFixed = true))
        assertTrue(result.fixed)
    }
}
