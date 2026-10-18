package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 8 / AC-10: OK pauses and resumes, Right skips; both ramp the player volume, never the stream. */
class TransportTest {
    private fun playing(): EngineHarness = EngineHarness(tracks = Tracks.many(6, durationSec = 3_600)).also {
        it.launcherStart()
        it.advance(31_000) // past the session fade-in
        assertEquals(1f, it.port.volume, 0f)
    }

    @Test
    fun okPausesAfterTheRampAndResumesWithARamp() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        assertEquals(Phase.Paused, h.state.phase)
        h.advance(600)
        assertFalse("still ramping", "port.pause" in h.log)
        assertEquals(0.125f, h.port.volume, 0.01f)
        h.advance(650)
        assertEquals(1, h.log.count { it == "port.pause" })
        assertEquals(0f, h.port.volume, 0f)

        h.engine.togglePause(CommandSource.Remote)
        assertEquals("port.play", h.log.last())
        assertEquals(Phase.Playing, h.state.phase)
        h.advance(1_000)
        assertEquals(0.125f, h.port.volume, 0.01f)
        h.advance(1_050)
        assertEquals(1f, h.port.volume, 0f)
    }

    @Test
    fun okDuringThePauseRampResumesWithoutPausingThePlayer() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        h.advance(500)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(3_000)
        assertFalse("port.pause" in h.log)
        assertEquals(1f, h.port.volume, 0f)
    }

    @Test
    fun rightSkipsAfterAShortRampToTheLookahead() {
        val h = playing()
        val before = h.played.single()
        val queued = h.port.upNext!!.trackId
        h.engine.skip(CommandSource.Remote)
        h.advance(300)
        assertFalse("port.advance" in h.log)
        h.advance(350)
        assertTrue("port.advance" in h.log)
        assertEquals(listOf(before, queued), h.played)
        assertEquals(queued, h.state.track?.id)
        assertNotEquals("a new lookahead is queued", queued, h.port.upNext!!.trackId)
        // The new track starts under its 4 s edge fade-in, no click.
        val afterSkip = h.port.volumes.last().second
        assertTrue(afterSkip < 0.01f)
        h.advance(4_100)
        assertEquals(1f, h.port.volume, 0f)
    }

    @Test
    fun skipWhilePausedResumes() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        h.advance(1_500)
        assertTrue("port.pause" in h.log)
        h.engine.skip(CommandSource.Remote)
        assertTrue("port.advance" in h.log)
        assertEquals("port.play", h.log.filter { it == "port.play" || it == "port.pause" }.last())
        assertEquals(Phase.Playing, h.state.phase)
        assertTrue(h.port.playing)
    }

    @Test
    fun okDuringTheSkipRampLeavesTheNewTrackPaused() {
        val h = playing()
        h.engine.skip(CommandSource.Remote)
        h.advance(200)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(1_000)
        assertTrue("port.advance" in h.log)
        assertEquals(Phase.Paused, h.state.phase)
        assertFalse(h.port.playing)
    }

    @Test
    fun aSecondSkipInFlightIsIgnored() {
        val h = playing()
        h.engine.skip(CommandSource.Remote)
        h.advance(100)
        h.engine.skip(CommandSource.MediaSession)
        h.advance(100)
        h.engine.skip(CommandSource.Remote)
        h.advance(2_000)
        assertEquals(1, h.log.count { it == "port.advance" })
        assertEquals(2, h.played.size)
    }

    @Test
    fun skipWithNothingEligibleFinishes() {
        val h = EngineHarness(tracks = Tracks.many(2, durationSec = 3_600))
        h.launcherStart()
        h.advance(31_000)
        h.engine.neverAgain() // blocks the first track and skips to the other one
        h.advance(1_000)
        assertEquals(Phase.Playing, h.state.phase)
        h.engine.neverAgain() // nothing eligible is left: no lookahead
        h.advance(1_000)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.NothingToPlay, h.state.endReason)
        assertEquals(1, h.host.ended)
        assertEquals(2, h.played.size)
    }

    @Test
    fun audioFocusLossShowsPausedAndOkResumes() {
        val h = playing()
        h.port.loseAudioFocus()
        assertEquals(Phase.Paused, h.state.phase)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals("port.play", h.log.last())
        assertEquals(Phase.Playing, h.state.phase)
    }

    @Test
    fun playAndPauseAreIdempotent() {
        val h = playing()
        h.engine.play(CommandSource.MediaSession)
        h.advance(2_000)
        assertFalse("port.play" in h.log)
        h.engine.pause(CommandSource.MediaSession)
        h.engine.pause(CommandSource.Remote)
        h.advance(2_000)
        assertEquals(1, h.log.count { it == "port.pause" })
        h.engine.play(CommandSource.MediaSession)
        h.engine.play(CommandSource.Remote)
        assertEquals(1, h.log.count { it == "port.play" })
    }

    @Test
    fun aResumeOfAPausedPlayerStartsTheHostAgainFirst() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        h.advance(1_500)
        assertTrue("port.pause" in h.log)
        assertEquals(0, h.host.resumed)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals(1, h.host.resumed)
        assertEquals(listOf("host.sessionResumed", "port.play"), h.log.takeLast(2))
        // A skip out of a paused player resumes it too.
        h.engine.togglePause(CommandSource.Remote)
        h.advance(1_500)
        h.engine.skip(CommandSource.Remote)
        assertEquals(2, h.host.resumed)
        // A resume inside the pause ramp never paused the player: nothing to start again.
        h.advance(5_000)
        h.engine.togglePause(CommandSource.Remote)
        h.advance(500)
        h.engine.togglePause(CommandSource.Remote)
        assertEquals(2, h.host.resumed)
        assertEquals(1, h.host.started)
    }

    @Test
    fun aPlayerErrorWhilePausedLoadsTheNextTrackPaused() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        h.advance(1_500)
        assertFalse(h.port.playing)
        val queued = h.port.upNext!!.trackId
        h.port.fail("ERROR_CODE_IO_FILE_NOT_FOUND")
        h.advance(5_000)
        assertEquals(Phase.Paused, h.state.phase)
        assertEquals(queued, h.state.track?.id)
        assertEquals("port.load($queued, ${h.port.upNext?.trackId}, paused)", h.log.last { it.startsWith("port.load") })
        assertFalse("the paused music does not start", h.port.playing)
        assertEquals(0f, h.port.volume, 0f)
        h.engine.togglePause(CommandSource.Remote)
        assertTrue(h.port.playing)
        assertEquals(Phase.Playing, h.state.phase)
    }

    @Test
    fun aPlayerErrorDuringThePauseRampLoadsTheNextTrackPausedAndSilent() {
        val h = playing()
        h.engine.togglePause(CommandSource.Remote)
        h.advance(500)
        assertTrue("still ramping", h.port.playing)
        h.port.fail()
        assertEquals(0f, h.port.volume, 0f)
        h.advance(5_000)
        assertEquals(Phase.Paused, h.state.phase)
        assertFalse(h.port.playing)
        assertEquals(0f, h.port.volume, 0f)
    }

    @Test
    fun aTrackEndingNaturallyMovesToTheLookahead() {
        val h = playing()
        val queued = h.port.upNext!!.trackId
        h.port.completeCurrentTrack()
        h.settle()
        assertEquals(queued, h.state.track?.id)
        assertEquals(2, h.played.size)
        assertTrue(h.log.last().startsWith("port.setUpNext("))
    }

    @Test
    fun aPlayerErrorSkipsAndThreeInARowEndTheSession() {
        val h = playing()
        h.port.fail()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(2, h.played.size)
        // A track that plays resets the count; three failures in a row without audio end the night.
        h.port.reportPlayingOnPlay = false
        h.port.fail()
        h.port.fail()
        assertEquals(Phase.Playing, h.state.phase)
        h.port.fail()
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(EndReason.PlaybackFailed, h.state.endReason)
        assertEquals(4, h.events.errors.size)
    }
}
