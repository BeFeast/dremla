package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 3, 9 / AC-5: like and never again are stored; a blocked track never plays again. */
class LikesAndBlocksTest {
    private fun playing(store: InMemoryPlaybackStore = InMemoryPlaybackStore(), seed: Int = 7) =
        EngineHarness(tracks = Tracks.many(6, durationSec = 3_600), store = store, seed = seed).also {
            it.launcherStart()
            it.advance(31_000)
        }

    @Test
    fun likeTogglesAndIsStored() {
        val h = playing()
        val id = h.state.track!!.id
        h.engine.toggleLike()
        assertEquals(setOf(id), h.store.liked())
        assertTrue(h.state.track!!.liked)
        h.engine.toggleLike()
        assertTrue(h.store.liked().isEmpty())
        assertFalse(h.state.track!!.liked)
    }

    @Test
    fun neverAgainBlocksDropsTheLikeAndSkipsAtOnce() {
        val h = playing()
        val id = h.state.track!!.id
        h.engine.toggleLike()
        h.engine.neverAgain()
        assertEquals(setOf(id), h.store.blocked())
        assertTrue(h.store.liked().isEmpty())
        h.advance(700)
        assertNotEquals(id, h.state.track!!.id)
        assertEquals(5, h.state.playableCount)
        repeat(40) {
            h.port.completeCurrentTrack()
            h.settle()
        }
        assertFalse("never plays again", id in h.played.drop(1))
        assertFalse(id == h.port.upNext?.trackId)
    }

    @Test
    fun aBlockedLookaheadIsReplacedBeforeItCanPlay() {
        val h = playing()
        val queued = h.port.upNext!!.trackId
        // Blocked elsewhere, then the collection is re-published (a refresh in #4).
        h.store.block(queued)
        h.source.emit(h.collection.copy(selection = h.collection.ids))
        h.settle()
        assertNotEquals(queued, h.port.upNext!!.trackId)
        h.port.completeCurrentTrack()
        h.settle()
        assertNotEquals(queued, h.state.track!!.id)
    }

    @Test
    fun aRefreshThatDropsTheLookaheadRedrawsIt() {
        val h = playing()
        val queued = h.port.upNext!!.trackId
        h.source.emit(h.collection.copy(tracks = h.collection.tracks.filter { it.id != queued }))
        h.settle()
        assertNotEquals(queued, h.port.upNext!!.trackId)
        assertEquals(5, h.state.playableCount)
    }

    @Test
    fun blockedTracksNeverPlayAcrossManySessionsAndRestarts() {
        val store = InMemoryPlaybackStore()
        store.block("amb-002")
        store.block("amb-005")
        repeat(30) { seed ->
            // A new engine on the same store: a new process after a restart or reboot.
            val h = playing(store, seed)
            repeat(10) {
                h.engine.skip(if (it % 2 == 0) CommandSource.Remote else CommandSource.MediaSession)
                h.advance(700)
            }
            assertTrue("seed $seed: ${h.played}", h.played.none { it == "amb-002" || it == "amb-005" })
            h.engine.stop(CommandSource.MediaSession)
        }
    }

    @Test
    fun neverAgainOutsideASessionDoesNothing() {
        val h = EngineHarness()
        h.engine.neverAgain()
        h.engine.toggleLike()
        assertTrue(h.store.blocked().isEmpty())
        assertTrue(h.store.liked().isEmpty())
        assertTrue(h.log.isEmpty())
    }
}
