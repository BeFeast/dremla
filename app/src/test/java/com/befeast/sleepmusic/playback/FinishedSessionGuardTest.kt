package com.befeast.sleepmusic.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PRD 7 / AC-9, regression for the known risk "media buttons resumed a finished session": after the
 * timer end or a stop, nothing but a launcher start makes a sound, whatever sends the command.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(UnstableApi::class)
class FinishedSessionGuardTest {
    private val inner = FakeMediaPlayer()

    private fun finished(end: (EngineHarness) -> Unit): EngineHarness {
        val h = EngineHarness(tracks = Tracks.many(4, durationSec = 3_600), enginePort = ExoPlayerPort { inner })
        h.engine.setTimerMinutes(30)
        h.launcherStart()
        h.advance(60_000)
        end(h)
        assertEquals(Phase.Finished, h.state.phase)
        assertTrue("the player is emptied at the end", inner.mediaIds.isEmpty())
        inner.calls.clear()
        return h
    }

    @Suppress("DEPRECATION")
    private fun everyRestartAttempt(h: EngineHarness) {
        val session = SessionPlayer(inner, h.engine)
        session.play()
        session.setPlayWhenReady(true)
        session.prepare()
        session.seekToDefaultPosition()
        session.seekTo(0L)
        session.seekToNext()
        session.next()
        session.seekToPrevious()
        // What Media3 runs for a play media button or a controller play on a stopped player.
        Util.handlePlayButtonAction(session)
        Util.handlePlayPauseButtonAction(session)
        CommandSource.entries.forEach { source ->
            h.engine.play(source)
            h.engine.togglePause(source)
            h.engine.skip(source)
            h.engine.pause(source)
            h.engine.stop(source)
        }
        h.engine.toggleLike()
        h.engine.neverAgain()
        h.advance(3 * 3_600_000L)
    }

    private fun assertStillFinished(h: EngineHarness, reason: EndReason) {
        val sessionsBefore = h.state.sessionId
        val hostStarts = h.host.started
        val logSize = h.log.size
        everyRestartAttempt(h)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(reason, h.state.endReason)
        assertEquals(sessionsBefore, h.state.sessionId)
        assertEquals("the service is not started again", hostStarts, h.host.started)
        assertEquals("no audio or player call", logSize, h.log.size)
        assertTrue("the wrapped player is never touched: ${inner.calls}", inner.calls.isEmpty())
        assertFalse(inner.isPlaying)
    }

    @Test
    fun afterTheTimerEndsNothingRestarts() {
        assertStillFinished(finished { it.advance(30 * 60_000L) }, EndReason.TimerEnded)
    }

    @Test
    fun afterAStopNothingRestarts() {
        assertStillFinished(finished { it.engine.stop(CommandSource.MediaSession) }, EndReason.Stopped)
    }

    @Test
    fun afterTheLastTrackWasBlockedNothingRestarts() {
        val h = finished {
            repeat(4) { _ ->
                it.engine.neverAgain()
                it.advance(1_000)
            }
        }
        assertStillFinished(h, EndReason.NothingToPlay)
    }

    @Test
    fun theSessionNeverOffersPlaybackResumption() {
        val session = SessionPlayer(inner, finished { it.engine.stop(CommandSource.System) }.engine)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(session.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(session.isCommandAvailable(Player.COMMAND_PREPARE))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
        assertTrue(session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertEquals(SessionPlayer.COMMANDS, session.availableCommands)
    }

    @Test
    fun aLauncherStartIsTheOnlyWayBack() {
        val h = finished { it.advance(30 * 60_000L) }
        everyRestartAttempt(h)
        h.launcherStart()
        assertEquals(Phase.Playing, h.state.phase)
        assertEquals(2, h.state.sessionId)
        assertEquals(2, inner.mediaIds.size)
    }
}
