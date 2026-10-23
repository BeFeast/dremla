package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.ShuffleBag
import com.befeast.sleepmusic.playback.testing.InMemoryPlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** PRD 3 / AC-5: shuffle-bag order with a lookahead that is committed only when it starts. */
class PlayQueueTest {
    private val ids = (1..8).map { "amb-%03d".format(it) }

    @Test
    fun aRepeatQueuedFromAOneTrackSetIsRedrawnWhenMoreTracksArrive() {
        // #83: a first night can begin on the first verified track while the rest still downloads.
        val queue = PlayQueue(ShuffleBag(Random(3)), InMemoryPlaybackStore())
        assertEquals("a", queue.begin(listOf("a"))!!.id)
        assertEquals("a", queue.upNext!!.id)
        assertTrue(queue.revalidate(listOf("a", "b")))
        assertEquals("b", queue.upNext!!.id)
        assertFalse(queue.revalidate(listOf("a", "b", "c")))
    }

    @Test
    fun lookaheadIsNotCommittedUntilItStarts() {
        val store = InMemoryPlaybackStore()
        val queue = PlayQueue(ShuffleBag(Random(3)), store)
        val first = queue.begin(ids)!!
        val next = queue.upNext!!
        assertEquals(1, store.bagHistory.size)
        assertEquals(BagState(setOf(first.id), first.id), store.bag())
        assertFalse(next.id in store.bag().played)

        val promoted = queue.promote(ids)!!
        assertEquals(next, promoted)
        assertEquals(BagState(setOf(first.id, next.id), next.id), store.bag())
    }

    @Test
    fun noRepeatWithinACycle() {
        repeat(50) { seed ->
            val queue = PlayQueue(ShuffleBag(Random(seed)), InMemoryPlaybackStore())
            val order = mutableListOf(queue.begin(ids)!!.id)
            repeat(ids.size * 3 - 1) { order += queue.promote(ids)!!.id }
            order.chunked(ids.size).forEach { assertEquals("seed $seed", ids.toSet(), it.toSet()) }
            order.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed", a, b) }
        }
    }

    @Test
    fun aBlockedLookaheadIsRedrawn() {
        val queue = PlayQueue(ShuffleBag(Random(1)), InMemoryPlaybackStore())
        queue.begin(ids)
        val blocked = queue.upNext!!.id
        val eligible = ids - blocked
        assertTrue(queue.revalidate(eligible))
        val redrawn = queue.upNext!!
        assertNotEquals(blocked, redrawn.id)
        assertTrue(redrawn.id in eligible)
        assertFalse("unchanged when still eligible", queue.revalidate(eligible))
    }

    @Test
    fun aLikeKeepsTheLookahead() {
        val store = InMemoryPlaybackStore()
        val queue = PlayQueue(ShuffleBag(Random(1)), store)
        queue.begin(ids)
        val before = queue.upNext
        store.setLiked(ids[5], true)
        assertFalse(queue.revalidate(ids))
        assertEquals(before, queue.upNext)
    }

    @Test
    fun nothingEligibleMeansNoLookahead() {
        val queue = PlayQueue(ShuffleBag(Random(1)), InMemoryPlaybackStore())
        assertNull(queue.begin(emptyList()))
        queue.begin(ids)
        assertTrue(queue.revalidate(emptyList()))
        assertNull(queue.upNext)
        assertNull(queue.promote(emptyList()))
    }

    @Test
    fun aSingleEligibleTrackRepeats() {
        val queue = PlayQueue(ShuffleBag(Random(1)), InMemoryPlaybackStore())
        assertEquals("amb-001", queue.begin(listOf("amb-001"))!!.id)
        assertEquals("amb-001", queue.upNext!!.id)
    }

    @Test
    fun progressCarriesOverToTheNextSessionThroughTheStore() {
        val store = InMemoryPlaybackStore()
        val first = PlayQueue(ShuffleBag(Random(11)), store)
        val playedBefore = mutableListOf(first.begin(ids)!!.id)
        repeat(3) { playedBefore += first.promote(ids)!!.id }
        // A new queue (new session or new process) on the same store continues the cycle.
        val second = PlayQueue(ShuffleBag(Random(99)), store)
        val playedAfter = mutableListOf(second.begin(ids)!!.id)
        repeat(3) { playedAfter += second.promote(ids)!!.id }
        assertEquals(ids.toSet(), (playedBefore + playedAfter).toSet())
        assertNotNull(store.bag().lastId)
    }
}
