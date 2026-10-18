package com.befeast.sleepmusic.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi

/**
 * The only player the MediaSession knows. State getters (timeline, metadata, position, playing)
 * come from the wrapped player unchanged; every transport call from outside goes to
 * [PlaybackControls], so system next, media keys and Right on the remote reach the same engine
 * functions and obey the same shuffle-bag and never-again rules. Everything else is refused: the
 * playlist, the player volume and the lifecycle of the wrapped player belong to the engine, which
 * drives that player directly through [ExoPlayerPort], never through this class.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(inner: Player, private val controls: PlaybackControls) : ForwardingPlayer(inner) {

    override fun getAvailableCommands(): Player.Commands = COMMANDS

    override fun isCommandAvailable(command: Int): Boolean = COMMANDS.contains(command)

    // ---- routed to the engine ----

    override fun play() = controls.play(CommandSource.MediaSession)

    override fun pause() = controls.pause(CommandSource.MediaSession)

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun seekToNext() = controls.skip(CommandSource.MediaSession)

    override fun seekToNextMediaItem() = controls.skip(CommandSource.MediaSession)

    @Deprecated("Deprecated in Player", ReplaceWith("seekToNext()"))
    override fun next() = controls.skip(CommandSource.MediaSession)

    @Deprecated("Deprecated in Player", ReplaceWith("seekToNextMediaItem()"))
    override fun seekToNextWindow() = controls.skip(CommandSource.MediaSession)

    override fun stop() = controls.stop(CommandSource.MediaSession)

    // ---- refused: a finished session is never prepared, sought or rebuilt from outside ----

    override fun prepare() = Unit
    override fun seekToDefaultPosition() = Unit
    override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun seekToPrevious() = Unit
    override fun seekToPreviousMediaItem() = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("seekToPrevious()"))
    override fun previous() = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("seekToPreviousMediaItem()"))
    override fun seekToPreviousWindow() = Unit

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem) = Unit
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit
    override fun addMediaItem(mediaItem: MediaItem) = Unit
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) = Unit
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = Unit
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = Unit
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) = Unit
    override fun removeMediaItem(index: Int) = Unit
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit
    override fun clearMediaItems() = Unit
    override fun setRepeatMode(repeatMode: Int) = Unit
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) = Unit
    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("setDeviceVolume(volume, flags)"))
    override fun setDeviceVolume(volume: Int) = Unit
    override fun setDeviceVolume(volume: Int, flags: Int) = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("increaseDeviceVolume(flags)"))
    override fun increaseDeviceVolume() = Unit
    override fun increaseDeviceVolume(flags: Int) = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("decreaseDeviceVolume(flags)"))
    override fun decreaseDeviceVolume() = Unit
    override fun decreaseDeviceVolume(flags: Int) = Unit

    @Deprecated("Deprecated in Player", ReplaceWith("setDeviceMuted(muted, flags)"))
    override fun setDeviceMuted(muted: Boolean) = Unit
    override fun setDeviceMuted(muted: Boolean, flags: Int) = Unit

    /** The wrapped player is process-scoped; a released session must not release it. */
    override fun release() = Unit

    companion object {
        /**
         * What the session advertises and grants: transport (play/pause, stop, next) and read
         * access. No prepare, seek, previous, playlist edits, volume or device volume. Without
         * COMMAND_SET_MEDIA_ITEM and COMMAND_CHANGE_MEDIA_ITEMS Media3 never attempts playback
         * resumption: a play request on an empty player becomes a plain [play], which the engine
         * ignores outside a session.
         */
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_DEVICE_VOLUME,
            )
            .build()
    }
}
