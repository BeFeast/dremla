package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PRD 3 / AC-5, regression for the known defect "system next ignored the shuffle bag" (the session
 * exposed only the current item and the stock next): every next the MediaSession can send goes to
 * the engine's skip, the same function Right on the remote calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemNextRegressionTest {
    private val tracks = Tracks.many(8, durationSec = 3_600)

    @Suppress("DEPRECATION")
    @Test
    fun everySessionNextCallsTheEngineSkipAndNeverThePlayerNext() {
        val controls = RecordingControls()
        val inner = FakeMediaPlayer()
        val player = SessionPlayer(inner, controls)
        player.seekToNext()
        player.seekToNextMediaItem()
        player.next()
        player.seekToNextWindow()
        assertEquals(List(4) { "skip(MediaSession)" }, controls.calls)
        assertTrue("the wrapped player never moves on its own", inner.calls.isEmpty())
    }

    @Test
    fun systemNextFollowsTheSameOrderAsRight() {
        repeat(10) { seed ->
            val byRemote = skipThrough(seed) { it.engine.skip(CommandSource.Remote) }
            val bySession = skipThrough(seed) { SessionPlayer(FakeMediaPlayer(), it.engine).seekToNext() }
            assertEquals("seed $seed", byRemote, bySession)
        }
    }

    @Test
    fun systemNextNeverRepeatsWithinACycleAndNeverPlaysABlockedTrack() {
        repeat(10) { seed ->
            val store = InMemoryPlaybackStore()
            store.block("amb-003")
            val order = skipThrough(seed, store, skips = 6 * 3) { SessionPlayer(FakeMediaPlayer(), it.engine).seekToNext() }
            assertFalse("seed $seed: $order", "amb-003" in order)
            order.chunked(7).filter { it.size == 7 }.forEach { cycle ->
                assertEquals("seed $seed: one cycle is a permutation", 7, cycle.toSet().size)
            }
        }
    }

    @Test
    fun theSessionTimelineIsTheShuffleBagOrder() {
        // Production wiring: the engine drives the real port over the player the session wraps.
        val inner = FakeMediaPlayer()
        val store = InMemoryPlaybackStore()
        store.block("amb-006")
        val h = EngineHarness(tracks = tracks, store = store, enginePort = ExoPlayerPort { inner })
        val session = SessionPlayer(inner, h.engine)
        h.launcherStart()
        h.advance(31_000)
        val seen = mutableListOf<String>()
        repeat(7 * 2) {
            val current = h.state.track!!.id
            assertEquals("the session's current item is the engine's track", current, session.currentMediaItem!!.mediaId)
            assertEquals("the session's next item is the bag's lookahead", 2, session.mediaItemCount)
            seen += current
            val lookahead = session.getMediaItemAt(1).mediaId
            session.seekToNext()
            h.advance(700)
            assertEquals(lookahead, h.state.track!!.id)
        }
        assertFalse("amb-006" in seen)
        seen.chunked(7).forEach { assertEquals(7, it.toSet().size) }
        assertEquals("one player move per next, made by the engine's port", 14, inner.calls.count { it.startsWith("seek(") })
    }

    private fun skipThrough(
        seed: Int,
        store: InMemoryPlaybackStore = InMemoryPlaybackStore(),
        skips: Int = 12,
        next: (EngineHarness) -> Unit,
    ): List<String> {
        val h = EngineHarness(tracks = tracks, store = store, seed = seed)
        h.launcherStart()
        h.advance(31_000)
        repeat(skips) {
            next(h)
            h.advance(700)
        }
        return h.played
    }
}
