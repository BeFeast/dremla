package com.befeast.sleepmusic

import kotlin.random.Random

/**
 * No-repeat weighted shuffle. Each eligible track plays exactly once per cycle; the order is
 * a weighted random draw from what is still unplayed (liked tracks weigh 2, others 1, so they
 * tend to come earlier in a cycle, but never repeat inside it). When the cycle is exhausted
 * it restarts, avoiding an immediate repeat of the last track across the boundary.
 */
class ShuffleBag(private val random: Random = Random.Default) {

    data class Pick(val id: String, val played: Set<String>, val cycleReset: Boolean)

    fun next(
        eligible: List<String>,
        liked: Set<String>,
        played: Set<String>,
        lastId: String?,
    ): Pick? {
        if (eligible.isEmpty()) return null
        val eligibleSet = eligible.toSet()
        var playedInSet: Set<String> = played intersect eligibleSet
        var pool = eligible.filter { it !in playedInSet }
        var reset = false
        if (pool.isEmpty()) {
            reset = true
            playedInSet = emptySet()
            pool = eligible.filter { it != lastId }.ifEmpty { eligible }
        }
        val id = weightedPick(pool, liked)
        return Pick(id, playedInSet + id, reset)
    }

    private fun weightedPick(pool: List<String>, liked: Set<String>): String {
        val weights = pool.map { if (it in liked) LIKED_WEIGHT else 1 }
        var r = random.nextInt(weights.sum())
        for (i in pool.indices) {
            r -= weights[i]
            if (r < 0) return pool[i]
        }
        return pool.last()
    }

    companion object {
        const val LIKED_WEIGHT = 2

        /**
         * Eligible = selection (if any) minus blocked, in library order. If the selection matches
         * nothing playable (ids unknown or all blocked), fall back to all non-blocked tracks.
         */
        fun eligibleIds(all: List<String>, selection: List<String>?, blocked: Set<String>): Eligibility {
            val unblocked = all.filter { it !in blocked }
            if (selection == null) return Eligibility(unblocked, selectionApplied = false, selectionFellBack = false)
            val sel = selection.toSet()
            val chosen = unblocked.filter { it in sel }
            return if (chosen.isEmpty()) {
                Eligibility(unblocked, selectionApplied = false, selectionFellBack = true)
            } else {
                Eligibility(chosen, selectionApplied = true, selectionFellBack = false)
            }
        }
    }

    data class Eligibility(val ids: List<String>, val selectionApplied: Boolean, val selectionFellBack: Boolean)
}
