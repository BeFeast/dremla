package com.befeast.sleepmusic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * [PlayerPort] over the process-scoped player (ExoPlayer in production). The player holds at most
 * two items, `[current, upNext]`: when it moves to the second one (end of track, or [advance]) the
 * port drops the first and reports [PlayerPort.Listener.onAdvanced], so the current item is always
 * index 0. The lookahead keeps the player out of STATE_ENDED between tracks, which keeps the
 * MediaSessionService in the foreground.
 *
 * The player is created on first use, so an idle process never builds one. The port keeps the
 * last [setVolume] value and applies it to a player it creates later: the engine silences the
 * port before the first load of a process, and a new player would otherwise start at volume 1.
 */
class ExoPlayerPort(private val playerFactory: () -> Player) : PlayerPort {
    private var player: Player? = null
    private var listener: PlayerPort.Listener? = null
    private var volume = 1f

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) listener?.onPlaying()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                return
            }
            val p = player ?: return
            val index = p.currentMediaItemIndex
            if (index <= 0) return
            p.removeMediaItems(0, index)
            listener?.onAdvanced()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_ENDED && p.mediaItemCount > 0) listener?.onEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                listener?.onPausedBySystem()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(error.errorCodeName)
        }
    }

    private fun player(): Player = player ?: playerFactory().also {
        it.volume = volume
        it.addListener(playerListener)
        player = it
    }

    override val positionMs: Long get() = player?.currentPosition ?: 0L

    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: -1L

    override fun load(current: QueueItem, upNext: QueueItem?, play: Boolean) {
        val p = player()
        p.setMediaItems(listOfNotNull(current, upNext).map(::mediaItem), 0, 0L)
        p.prepare()
        if (play) p.play() else p.pause()
    }

    override fun setUpNext(upNext: QueueItem?) {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val next = p.currentMediaItemIndex + 1
        if (p.mediaItemCount > next) p.removeMediaItems(next, p.mediaItemCount)
        if (upNext != null) p.addMediaItem(mediaItem(upNext))
    }

    override fun advance() {
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stopAndClear() {
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
    }

    override fun setVolume(gain: Float) {
        volume = gain.coerceIn(0f, 1f)
        player?.volume = volume
    }

    override fun setListener(listener: PlayerPort.Listener?) {
        this.listener = listener
    }

    private fun mediaItem(item: QueueItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.trackId)
        .setUri(Uri.fromFile(item.file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .build(),
        )
        .build()
}
