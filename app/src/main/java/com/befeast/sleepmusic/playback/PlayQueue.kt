package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.ShuffleBag

/**
 * Shuffle-bag order with a one-track lookahead. The current pick is committed to the store when it
 * starts; the lookahead is drawn as if the current track had played, but is committed only when it
 * becomes current, so a track that was queued and never started does not count as played.
 * `eligible` is recomputed by the caller for every draw, so never again and refreshes apply at once.
 */
class PlayQueue(private val bag: ShuffleBag, private val store: PlaybackStore) {
    var current: ShuffleBag.Pick? = null
        private set
    var upNext: ShuffleBag.Pick? = null
        private set

    /** Draws and commits the first track of a session and draws the lookahead. */
    fun begin(eligible: List<String>): ShuffleBag.Pick? {
        clear()
        val saved = store.bag()
        val first = bag.next(eligible, store.liked(), saved.played, saved.lastId) ?: return null
        commit(first)
        current = first
        upNext = draw(eligible)
        return first
    }

    /** The lookahead becomes current (it is what the player now plays) and a new one is drawn. */
    fun promote(eligible: List<String>): ShuffleBag.Pick? {
        val next = upNext ?: return null
        commit(next)
        current = next
        upNext = draw(eligible)
        return next
    }

    /**
     * Redraws the lookahead if it is no longer eligible (or missing while something is), or if it repeats the
     * current track while another is eligible: a night that began on a one-track set while the first download
     * was still running (#83). True if it changed.
     */
    fun revalidate(eligible: List<String>): Boolean {
        val now = current ?: return false
        val before = upNext
        if (before != null && before.id in eligible && !(before.id == now.id && eligible.any { it != now.id })) return false
        upNext = draw(eligible)
        return upNext != before
    }

    fun clear() {
        current = null
        upNext = null
    }

    private fun draw(eligible: List<String>): ShuffleBag.Pick? {
        val now = current ?: return null
        return bag.next(eligible, store.liked(), now.played, now.id)
    }

    private fun commit(pick: ShuffleBag.Pick) = store.saveBag(BagState(pick.played, pick.id))
}
