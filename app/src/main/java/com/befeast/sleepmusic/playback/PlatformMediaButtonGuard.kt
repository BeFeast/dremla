package com.befeast.sleepmusic.playback

import android.content.Intent
import android.media.Rating
import android.media.session.MediaSession as PlatformSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Media3 1.4.1 rejects an empty controller package before its public media-button hook.
 * Intercept framework buttons before that lookup; all other callbacks retain Media3 routing.
 * Reflection touches only the pinned library, never hidden Android APIs. See docs/playback.md.
 */
@OptIn(UnstableApi::class)
internal class PlatformMediaButtonGuard(
    private val delegate: PlatformSession.Callback,
    private val controls: PlaybackControls,
) : PlatformSession.Callback() {
    @Suppress("DEPRECATION")
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        if (mediaButtonIntent.action != Intent.ACTION_MEDIA_BUTTON) return true
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled) return true
        when (RemoteKeyMap.onKeyDown(event.keyCode, event.repeatCount)) {
            RemoteCommand.TogglePause -> controls.togglePause(CommandSource.MediaSession)
            RemoteCommand.Play -> controls.play(CommandSource.MediaSession)
            RemoteCommand.Pause -> controls.pause(CommandSource.MediaSession)
            RemoteCommand.Skip -> controls.skip(CommandSource.MediaSession)
            else -> Unit
        }
        // Consume up, repeat, unsupported and malformed events too: no framework fallback.
        return true
    }

    override fun onCommand(command: String, extras: Bundle?, cb: ResultReceiver?) = delegate.onCommand(command, extras, cb)
    override fun onPrepare() = delegate.onPrepare()
    override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) = delegate.onPrepareFromMediaId(mediaId, extras)
    override fun onPrepareFromSearch(query: String, extras: Bundle?) = delegate.onPrepareFromSearch(query, extras)
    override fun onPrepareFromUri(uri: Uri, extras: Bundle?) = delegate.onPrepareFromUri(uri, extras)
    override fun onPlay() = delegate.onPlay()
    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = delegate.onPlayFromMediaId(mediaId, extras)
    override fun onPlayFromSearch(query: String, extras: Bundle?) = delegate.onPlayFromSearch(query, extras)
    override fun onPlayFromUri(uri: Uri, extras: Bundle?) = delegate.onPlayFromUri(uri, extras)
    override fun onSkipToQueueItem(id: Long) = delegate.onSkipToQueueItem(id)
    override fun onPause() = delegate.onPause()
    override fun onSkipToNext() = delegate.onSkipToNext()
    override fun onSkipToPrevious() = delegate.onSkipToPrevious()
    override fun onFastForward() = delegate.onFastForward()
    override fun onRewind() = delegate.onRewind()
    override fun onStop() = delegate.onStop()
    override fun onSeekTo(pos: Long) = delegate.onSeekTo(pos)
    override fun onSetRating(rating: Rating) = delegate.onSetRating(rating)
    @RequiresApi(29)
    override fun onSetPlaybackSpeed(speed: Float) = delegate.onSetPlaybackSpeed(speed)
    override fun onCustomAction(action: String, extras: Bundle?) = delegate.onCustomAction(action, extras)

    companion object {
        /** Resolve everything before replacing the callback; lookup failures leave Media3 intact. */
        fun install(
            session: MediaSession,
            controls: PlaybackControls,
            lookup: (MediaSession) -> Pair<PlatformSession, PlatformSession.Callback> = ::lookup,
        ): Boolean {
            val resolved = try {
                lookup(session)
            } catch (e: ReflectiveOperationException) {
                return unavailable(e)
            } catch (e: RuntimeException) {
                return unavailable(e)
            } catch (e: LinkageError) {
                return unavailable(e)
            }
            resolved.first.setCallback(
                PlatformMediaButtonGuard(resolved.second, controls),
                Handler(session.player.applicationLooper),
            )
            return true
        }

        private fun unavailable(error: Throwable): Boolean {
            Log.w("SleepMusic", "media-button guard unavailable; retaining Media3 callback", error)
            return false
        }

        internal fun lookup(session: MediaSession): Pair<PlatformSession, PlatformSession.Callback> {
            val compat = MediaSession::class.java.getDeclaredMethod("getSessionCompat")
                .apply { isAccessible = true }.invoke(session)!!
            val impl = compat.javaClass.getDeclaredField("mImpl")
                .apply { isAccessible = true }.get(compat)!!
            val callback = impl.javaClass.getMethod("getCallback")
                .apply { isAccessible = true }.invoke(impl)!!
            val callbackBase = Class.forName("androidx.media3.session.legacy.MediaSessionCompat\$Callback")
            val delegate = callbackBase.getDeclaredField("mCallbackFwk")
                .apply { isAccessible = true }.get(callback) as PlatformSession.Callback
            val platform = compat.javaClass.getMethod("getMediaSession").invoke(compat) as PlatformSession
            return platform to delegate
        }
    }
}
