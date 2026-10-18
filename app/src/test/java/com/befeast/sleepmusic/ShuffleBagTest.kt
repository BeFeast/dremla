package com.befeast.sleepmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleBagTest {
    private val ids = (1..12).map { "t-%02d".format(it) }

    /** Simulates the Engine loop: feed played/lastId back in, like Prefs does. */
    private fun run(bag: ShuffleBag, eligible: List<String>, liked: Set<String>, n: Int): List<ShuffleBag.Pick> {
        var played = emptySet<String>()
        var last: String? = null
        return (1..n).map {
            val p = bag.next(eligible, liked, played, last)!!
            played = p.played
            last = p.id
            p
        }
    }

    @Test
    fun noRepeatUntilWholeSetPlayed() {
        repeat(200) { seed ->
            val picks = run(ShuffleBag(Random(seed)), ids, setOf("t-01", "t-05"), ids.size * 3).map { it.id }
            picks.chunked(ids.size).forEach { cycle ->
                assertEquals("cycle must be a permutation (seed $seed)", ids.toSet(), cycle.toSet())
            }
        }
    }

    @Test
    fun noImmediateRepeatAcrossCycleBoundary() {
        repeat(500) { seed ->
            val picks = run(ShuffleBag(Random(seed)), ids, emptySet(), ids.size * 2).map { it.id }
            assertNotEquals("seed $seed", picks[ids.size - 1], picks[ids.size])
        }
    }

    @Test
    fun cycleResetFlagOnlyAtBoundary() {
        val picks = run(ShuffleBag(Random(7)), ids, emptySet(), ids.size * 2 + 1)
        val resets = picks.withIndex().filter { it.value.cycleReset }.map { it.index }
        assertEquals(listOf(ids.size, ids.size * 2), resets)
    }

    @Test
    fun likedTracksTendToComeFirst() {
        val bag = ShuffleBag(Random(42))
        val trials = 30_000
        var likedFirst = 0
        repeat(trials) {
            if (bag.next(listOf("a", "b"), setOf("a"), emptySet(), null)!!.id == "a") likedFirst++
        }
        val ratio = likedFirst.toDouble() / trials
        assertEquals("liked weight x2 => ~2/3 first", 2.0 / 3.0, ratio, 0.02)
    }

    @Test
    fun historyOutsideEligibleSetIsIgnored() {
        // A track played earlier then blocked must not stall the cycle.
        val p = ShuffleBag(Random(1)).next(listOf("a", "b"), emptySet(), setOf("a", "zzz"), "zzz")!!
        assertEquals("b", p.id)
        assertEquals(setOf("a", "b"), p.played)
    }

    @Test
    fun emptyEligibleGivesNull() {
        assertNull(ShuffleBag().next(emptyList(), emptySet(), emptySet(), null))
    }

    @Test
    fun singleTrackRepeatsRatherThanStops() {
        val picks = run(ShuffleBag(Random(3)), listOf("only"), emptySet(), 3).map { it.id }
        assertEquals(listOf("only", "only", "only"), picks)
    }

    @Test
    fun eligibilitySelectionMinusBlocked() {
        val e = ShuffleBag.eligibleIds(ids, listOf("t-01", "t-02", "t-03", "nope"), setOf("t-02"))
        assertEquals(listOf("t-01", "t-03"), e.ids)
        assertTrue(e.selectionApplied)
        assertFalse(e.selectionFellBack)
    }

    @Test
    fun eligibilityNullSelectionMeansAll() {
        val e = ShuffleBag.eligibleIds(ids, null, setOf("t-12"))
        assertEquals(ids - "t-12", e.ids)
        assertFalse(e.selectionApplied)
    }

    @Test
    fun eligibilityFallsBackWhenSelectionMatchesNothing() {
        val e = ShuffleBag.eligibleIds(ids, listOf("gone-1", "t-04"), setOf("t-04"))
        assertEquals(ids - "t-04", e.ids)
        assertTrue(e.selectionFellBack)
    }
}
