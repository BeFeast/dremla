package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.playback.BagState
import com.befeast.sleepmusic.playback.PlaybackStore
import com.befeast.sleepmusic.playback.SleepTimer

class InMemoryPlaybackStore : PlaybackStore {
    private val likedIds = linkedSetOf<String>()
    private val blockedIds = linkedSetOf<String>()
    private var onlineState = BagState()
    override fun onlineBag() = onlineState
    override fun saveOnlineBag(state: BagState) { onlineState = state }
    private var bagState = BagState()
    private var timer = SleepTimer.DEFAULT_MINUTES
    override var lastClockRequestId: String? = null
    val bagHistory = mutableListOf<BagState>()

    override fun liked(): Set<String> = likedIds.toSet()
    override fun blocked(): Set<String> = blockedIds.toSet()

    override fun setLiked(id: String, liked: Boolean) {
        if (liked) likedIds += id else likedIds -= id
    }

    override fun block(id: String) {
        blockedIds += id
        likedIds -= id
    }

    override fun bag(): BagState = bagState

    override fun saveBag(state: BagState) {
        bagState = state
        bagHistory += state
    }

    override var tvOff = true

    override var timerMinutes: Int
        get() = timer
        set(value) {
            timer = SleepTimer.sanitize(value)
        }
}
