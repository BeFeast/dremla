package com.befeast.sleepmusic.playback

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * PRD 7 / AC-9 and PRD 15: the service hosts a session only while one is active, builds it on
 * demand, and an idle start never crashes the app.
 *
 * The API 30 cases run on the API 34 runtime with `Build.VERSION.SDK_INT` set to 29: the branch
 * under test is the service's own, and Robolectric models the system's foreground-start crash on
 * no API level, so the test asserts what the system requires instead: the service entered the
 * foreground (and left it, removing the notification) as well as stopping itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class PlaybackServiceTest {
    private val realSdk = Build.VERSION.SDK_INT
    private val services = mutableListOf<ServiceController<PlaybackService>>()

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", realSdk)
        services.forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun create(graph: TestGraph): PlaybackService {
        TestApp.graph = graph
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        services += controller
        return controller.get()
    }

    private fun mediaButton(keyCode: Int) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    private val anyController
        get() = MediaSession.ControllerInfo.createTestOnlyControllerInfo("system", 0, 0, 0, 0, false, Bundle.EMPTY)

    @Test
    fun belowApi31AnIdleServiceEntersTheForegroundBeforeItStops() {
        // A media key after a night whose process died: startForegroundService, no session.
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        val graph = TestGraph(RecordingControls(), FakeMediaPlayer())
        val service = create(graph)
        val shadow = shadowOf(service)
        assertEquals("entered the foreground", PlaybackService.IDLE_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertTrue("left the foreground", shadow.isForegroundStopped)
        assertTrue("and removed the notification", shadow.notificationShouldRemoved)
        assertNull(shadow.lastForegroundNotification)
        assertNotNull(service.getSystemService(NotificationManager::class.java).getNotificationChannel(PlaybackService.IDLE_CHANNEL_ID))
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(mediaButton(KeyEvent.KEYCODE_MEDIA_PLAY), 0, 1))
        assertEquals("no session, no player", 0, graph.sessionPlayerRequests)
        assertTrue(service.sessions.isEmpty())
        assertNull(service.onGetSession(anyController))
    }

    @Test
    fun fromApi31AnIdleServiceStopsWithoutAForeground() {
        val graph = TestGraph(RecordingControls(), FakeMediaPlayer())
        val service = create(graph)
        service.onStartCommand(mediaButton(KeyEvent.KEYCODE_MEDIA_PLAY), 0, 1)
        assertEquals("never entered the foreground", 0, shadowOf(service).lastForegroundNotificationId)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, graph.sessionPlayerRequests)
    }

    @Test
    fun anInstanceCreatedWhileIdleBuildsItsSessionWhenANightStarts() {
        // Created idle and kept alive (a bind), then the night starts and the host starts the service.
        val controls = RecordingControls()
        val graph = TestGraph(controls, FakeMediaPlayer())
        val service = create(graph)
        assertTrue(service.sessions.isEmpty())
        controls.mutableState.value = SessionState(phase = Phase.Playing)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 2))
        val session = service.sessions.single()
        assertTrue(session.player is SessionPlayer)
        assertEquals(PlaybackService.SESSION_ID, session.id)
        assertSame(session, service.onGetSession(anyController))
        // Built once, whatever asks for it next.
        service.onStartCommand(Intent(), 0, 3)
        assertEquals(1, graph.sessionPlayerRequests)
        assertEquals(1, service.sessions.size)
    }

    @Test
    fun aControllerBindingAfterTheNightStartedGetsTheSession() {
        val controls = RecordingControls()
        val graph = TestGraph(controls, FakeMediaPlayer())
        val service = create(graph)
        controls.mutableState.value = SessionState(phase = Phase.Paused)
        val session = service.onGetSession(anyController)
        assertNotNull(session)
        assertSame(session, service.sessions.single())
        controls.mutableState.value = SessionState(phase = Phase.Finished, endReason = EndReason.TimerEnded)
        assertNull("no session for anyone after the end", service.onGetSession(anyController))
    }
}
