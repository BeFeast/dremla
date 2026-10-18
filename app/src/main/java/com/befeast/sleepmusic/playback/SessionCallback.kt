package com.befeast.sleepmusic.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Every controller (system UIs, media buttons through the notification controller, the platform
 * session) gets [SessionPlayer.COMMANDS] and no session commands. Playback resumption and external
 * queue edits always fail, so nothing outside a launcher start can put music into the player.
 */
@OptIn(UnstableApi::class)
class SessionCallback : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(SessionPlayer.COMMANDS)
            .setAvailableSessionCommands(SessionCommands.EMPTY)
            .build()

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("a session starts only from the launcher"))

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("the queue belongs to the shuffle bag"))

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("the queue belongs to the shuffle bag"))
}
