package com.befeast.sleepmusic.playback

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * PRD 3 / AC-5 and PRD 7 / AC-9 through a real MediaSession: controllers and media buttons reach
 * the engine only through [SessionPlayer] and [SessionCallback]. The engine drives the real
 * [ExoPlayerPort] over the player the session wraps, as in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class MediaSessionRoutingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val inner = FakeMediaPlayer()
    private val h = EngineHarness(tracks = Tracks.many(6, durationSec = 3_600), enginePort = ExoPlayerPort { inner })
    private val sessions = mutableListOf<MediaSession>()
    private val controllers = mutableListOf<MediaController>()
    private val services = mutableListOf<ServiceController<PlaybackService>>()

    @After
    fun release() {
        controllers.forEach { it.release() }
        sessions.forEach { it.release() }
        services.forEach { it.destroy() }
        idle()
    }

    private fun idle(ms: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (ms > 0) looper.idleFor(Duration.ofMillis(ms)) else looper.idle()
    }

    private fun session(id: String): MediaSession =
        MediaSession.Builder(context, SessionPlayer(inner, h.engine))
            .setId(id)
            .setCallback(SessionCallback())
            .build()
            .also { sessions += it }

    private fun connect(session: MediaSession): MediaController {
        val future = MediaController.Builder(context, session.token).buildAsync()
        idle()
        return future.get(5, TimeUnit.SECONDS).also { controllers += it }
    }

    private fun mediaButton(keyCode: Int) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    /**
     * A media button as the platform delivers it below API 31: addressed to the session by its
     * URI, so `MediaSessionService.onStartCommand` finds the live session without `onGetSession`.
     * The URI is package-private in Media3 1.4.1, hence the reflection.
     */
    private fun mediaButtonFor(session: MediaSession, keyCode: Int): Intent {
        val uri = MediaSession::class.java.getDeclaredMethod("getUri").apply { isAccessible = true }.invoke(session) as Uri
        return mediaButton(keyCode).setData(uri)
    }

    private fun startService(): PlaybackService {
        TestApp.graph = TestGraph(h.engine, inner)
        val controller = Robolectric.buildService(PlaybackService::class.java).create().also {
            it.get().onStartCommand(Intent(), 0, 1)
        }
        services += controller
        idle()
        return controller.get()
    }

    @Test
    fun controllersGetTransportAndReadAccessOnly() {
        h.launcherStart()
        val controller = connect(session("grant"))
        val commands = controller.availableCommands
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .forEach { assertTrue("granted $it", commands.contains(it)) }
        listOf(
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_PREPARE,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
        ).forEach { assertFalse("not granted $it", commands.contains(it)) }
        assertTrue(controller.availableSessionCommands.commands.isEmpty())
    }

    @Test
    fun controllerNextIsTheEngineSkipToTheBagsLookahead() {
        h.launcherStart()
        h.advance(31_000)
        val controller = connect(session("next"))
        val lookahead = inner.mediaIds[1]
        assertEquals(h.state.track!!.id, controller.currentMediaItem!!.mediaId)
        controller.seekToNext()
        idle()
        h.advance(700)
        idle()
        assertEquals(lookahead, h.state.track!!.id)
        assertEquals(lookahead, controller.currentMediaItem!!.mediaId)
        assertEquals(2, controller.mediaItemCount)
    }

    @Test
    fun controllerPauseAndPlayGoThroughTheEngine() {
        h.launcherStart()
        h.advance(31_000)
        val controller = connect(session("pause"))
        controller.pause()
        idle()
        assertEquals(Phase.Paused, h.state.phase)
        controller.play()
        idle()
        assertEquals(Phase.Playing, h.state.phase)
    }

    @Test
    fun controllerPlayAfterTheEndDoesNothing() {
        h.launcherStart()
        val controller = connect(session("end"))
        h.engine.stop(CommandSource.MediaSession)
        idle()
        inner.calls.clear()
        val logSize = h.log.size
        controller.play()
        controller.prepare()
        controller.seekToDefaultPosition()
        controller.seekToNext()
        controller.setPlayWhenReady(true)
        idle()
        h.advance(5_000)
        idle()
        assertEquals(Phase.Finished, h.state.phase)
        assertTrue("${inner.calls}", inner.calls.isEmpty())
        assertEquals(logSize, h.log.size)
    }

    @Test
    fun controllerQueueEditsAreRefused() {
        h.launcherStart()
        val controller = connect(session("edit"))
        val before = inner.mediaIds
        controller.setMediaItem(MediaItem.fromUri("file:///elsewhere.opus"))
        controller.addMediaItem(MediaItem.fromUri("file:///elsewhere.opus"))
        controller.clearMediaItems()
        controller.seekToPrevious()
        controller.seekTo(1, 0L)
        idle()
        assertEquals(before, inner.mediaIds)
    }

    @Test
    fun playbackResumptionAndExternalQueuesAlwaysFail() {
        h.launcherStart()
        val session = session("resume")
        connect(session)
        val info = session.connectedControllers.first()
        val callback = SessionCallback()
        listOf(
            callback.onPlaybackResumption(session, info),
            callback.onAddMediaItems(session, info, mutableListOf(MediaItem.fromUri("file:///x.opus"))),
            callback.onSetMediaItems(session, info, mutableListOf(MediaItem.fromUri("file:///x.opus")), 0, 0L),
        ).forEach { future ->
            assertTrue(future.isDone)
            assertThrows(ExecutionException::class.java) { future.get() }
        }
    }

    @Test
    fun theServiceHostsTheSessionOverTheSessionPlayer() {
        h.launcherStart()
        val service = startService()
        val session = service.sessions.single()
        assertTrue(session.player is SessionPlayer)
        assertSame(session, service.onGetSession(session.connectedControllers.first()))
        assertEquals(PlaybackService.SESSION_ID, session.id)
    }

    @Test
    fun mediaButtonNextDuringASessionSkipsThroughTheSameEnginePath() {
        h.launcherStart()
        h.advance(31_000)
        val service = startService()
        val lookahead = inner.mediaIds[1]
        service.onStartCommand(mediaButton(KeyEvent.KEYCODE_MEDIA_NEXT), 0, 1)
        idle()
        h.advance(700)
        idle()
        assertEquals(lookahead, h.state.track!!.id)
    }

    @Test
    fun mediaButtonPlayAfterTheTimerEndedDoesNothing() {
        h.engine.setTimerMinutes(30)
        h.launcherStart()
        val service = startService()
        // The timer ends while the service is still alive (the worst case: the stop is still on its way).
        h.advance(31 * 60_000L)
        idle()
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertNull("no session for anyone after the end", service.onGetSession(session0(service)))
        inner.calls.clear()
        val logSize = h.log.size
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT).forEach {
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(mediaButton(it), 0, 2))
            idle(1_000)
        }
        h.advance(60_000)
        idle()
        assertEquals(Phase.Finished, h.state.phase)
        assertTrue("${inner.calls}", inner.calls.isEmpty())
        assertEquals(logSize, h.log.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aMediaButtonAddressedToTheLiveSessionSkipsDuringANight() {
        h.launcherStart()
        h.advance(31_000)
        val service = startService()
        val lookahead = inner.mediaIds[1]
        service.onStartCommand(mediaButtonFor(service.sessions.single(), KeyEvent.KEYCODE_MEDIA_NEXT), 0, 1)
        idle()
        h.advance(700)
        idle()
        assertEquals(lookahead, h.state.track!!.id)
    }

    @Test
    fun aMediaButtonAddressedToTheLiveSessionAfterTheEndDoesNothing() {
        h.engine.setTimerMinutes(30)
        h.launcherStart()
        val service = startService()
        val session = service.sessions.single()
        // The session is still alive after the end: the worst case, the service stop is on its way.
        h.advance(31 * 60_000L)
        idle()
        assertEquals(EndReason.TimerEnded, h.state.endReason)
        assertTrue("the service still hosts it", session in service.sessions)
        inner.calls.clear()
        val logSize = h.log.size
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT).forEach {
            service.onStartCommand(mediaButtonFor(session, it), 0, 2)
            idle(1_000)
        }
        h.advance(60_000)
        idle()
        assertEquals(Phase.Finished, h.state.phase)
        assertTrue("${inner.calls}", inner.calls.isEmpty())
        assertEquals(logSize, h.log.size)
    }

    private fun session0(service: PlaybackService) = service.sessions.single().connectedControllers.first()
}
