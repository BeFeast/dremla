package com.befeast.sleepmusic.playback

/** Shuffle-bag progress: the ids played in the current cycle and the last one. */
data class BagState(val played: Set<String> = emptySet(), val lastId: String? = null)

/** Local preferences of the listener. Nothing here ever leaves the device (PRD item 13). */
interface PlaybackStore {
    fun liked(): Set<String>
    fun blocked(): Set<String>
    fun setLiked(id: String, liked: Boolean)

    /** Never again: blocks the track for good and drops its like. */
    fun block(id: String)

    fun bag(): BagState
    fun saveBag(state: BagState)

    /** End-of-night sleep preference; default on. */
    var tvOff: Boolean

    /** Sanitised to [SleepTimer.CHOICES]; default 120. */
    var timerMinutes: Int
}
