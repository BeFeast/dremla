package com.befeast.sleepmusic.playback.testing

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A small playlist player standing in for ExoPlayer (Robolectric, main looper): set, add and
 * remove items, prepare, play and pause, seek to the next item, stop and volume. Every handled
 * call is recorded in [calls]. [completeCurrentItem] plays the current item to its end; the
 * position comes from [positionMs].
 */
@OptIn(UnstableApi::class)
class FakeMediaPlayer(looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {
    private data class Entry(val uid: Any, val item: MediaItem)

    private var nextUid = 0
    private val entries = mutableListOf<Entry>()
    private var currentIndex = 0
    private var playWhenReady = false
    private var prepared = false
    private var ended = false
    private var volume = 1f
    private var autoTransition = false
    private var error: PlaybackException? = null
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    val calls = mutableListOf<String>()

    /** Content position of the current item; 0 unless a test drives it (for example from a virtual clock). */
    var positionMs: () -> Long = { 0L }

    val mediaIds: List<String> get() = entries.map { it.item.mediaId }
    val playerVolume: Float get() = volume

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                entries.map {
                    MediaItemData.Builder(it.uid)
                        .setMediaItem(it.item)
                        .setDurationUs(300_000_000L)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(if (entries.isEmpty()) 0 else currentIndex)
            .setPlayWhenReady(playWhenReady, playWhenReadyReason)
            .setVolume(volume)
            .setContentPositionMs(PositionSupplier { positionMs() })
            .setPlayerError(error)
            .setPlaybackState(
                when {
                    entries.isEmpty() || !prepared -> Player.STATE_IDLE
                    ended -> Player.STATE_ENDED
                    else -> Player.STATE_READY
                },
            )
        if (autoTransition) {
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L)
            autoTransition = false
        }
        return builder.build()
    }

    /** The current item plays to its end: the next one starts, or the player ends. */
    fun completeCurrentItem() {
        if (currentIndex + 1 < entries.size) {
            currentIndex++
            autoTransition = true
        } else {
            ended = true
        }
        invalidateState()
    }

    /** Audio focus lost for good: the player pauses itself. */
    fun loseAudioFocus() {
        playWhenReady = false
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        invalidateState()
    }

    fun failWith(errorCode: Int) {
        error = PlaybackException("test", null, errorCode)
        prepared = false
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        calls += "setPlayWhenReady($playWhenReady)"
        this.playWhenReady = playWhenReady
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        calls += "prepare"
        prepared = true
        error = null
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        calls += "stop"
        prepared = false
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        calls += "setMediaItems(${mediaItems.joinToString { it.mediaId }})"
        entries.clear()
        mediaItems.forEach { entries += Entry(nextUid++, it) }
        currentIndex = if (startIndex == androidx.media3.common.C.INDEX_UNSET) 0 else startIndex
        ended = false
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        calls += "addMediaItems($index, ${mediaItems.joinToString { it.mediaId }})"
        entries.addAll(index.coerceAtMost(entries.size), mediaItems.map { Entry(nextUid++, it) })
        if (ended && currentIndex + 1 < entries.size) ended = false
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        calls += "removeMediaItems($fromIndex, $toIndex)"
        val to = toIndex.coerceAtMost(entries.size)
        repeat(to - fromIndex) { entries.removeAt(fromIndex) }
        if (currentIndex >= to) {
            currentIndex -= to - fromIndex
        } else if (currentIndex >= fromIndex) {
            currentIndex = fromIndex.coerceAtMost((entries.size - 1).coerceAtLeast(0))
        }
        if (entries.isEmpty()) {
            currentIndex = 0
            ended = false
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        calls += "seek($mediaItemIndex, $seekCommand)"
        if (mediaItemIndex != androidx.media3.common.C.INDEX_UNSET) currentIndex = mediaItemIndex
        ended = false
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        calls += "release"
        return Futures.immediateVoidFuture()
    }
}
