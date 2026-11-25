package com.befeast.sleepmusic.online

import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.befeast.sleepmusic.playback.*
import com.befeast.sleepmusic.playback.testing.*
import com.befeast.sleepmusic.ui.NightScene
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class OnlinePlatformTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceChoiceIsDpadReachableAndUnavailableMenuIsVisibleOverBlackout() {
        var chosen = false
        compose.setContent {
            NightScene(SessionState(online = true, onlinePhase = "unavailable", blackScreen = true,
                phase = Phase.Playing), menuOpen = true, onSource = { chosen = true })
        }
        compose.onNodeWithTag("timer-90").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("source").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(chosen) }
        compose.onNodeWithText("unavailable · needs catalog or new launch").assertExists()
    }

    @Test fun blackTransitionClosesOnlineMenuButLongPressCanReopenIt() {
        val controls = RecordingControls(SessionState(online = true, phase = Phase.Playing))
        TestApp.graph = TestGraph(controls)
        val controller = Robolectric.buildActivity(com.befeast.sleepmusic.MainActivity::class.java).setup()
        val activity = controller.get()
        fun open() { activity.onKeyLongPress(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)) }
        fun isOpen(): Boolean {
            val field = activity.javaClass.getDeclaredField("menuOpen").apply { isAccessible = true }
            return (field.get(activity) as androidx.compose.runtime.MutableState<*>).value as Boolean
        }
        try {
            open(); assertTrue(isOpen())
            controls.mutableState.value = controls.state.value.copy(blackScreen = true)
            shadowOf(Looper.getMainLooper()).idle(); assertFalse(isOpen())
            open(); assertTrue(isOpen())
            controls.mutableState.value = controls.state.value.copy(timerRemainingSec = 20)
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(isOpen())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun boundServiceReleasesNightWakeAndReacquiresForNextNight() {
        val controls = RecordingControls(SessionState(online = true, phase = Phase.Playing, sessionId = 1))
        TestApp.graph = TestGraph(controls, FakeMediaPlayer())
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            controls.mutableState.value = controls.state.value.copy(onlinePhase = "unavailable")
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(lock.isHeld)
            controls.mutableState.value = controls.state.value.copy(phase = Phase.Finished)
            shadowOf(Looper.getMainLooper()).idle(); assertFalse(lock.isHeld)
            // Controller retains the service and MediaSession through both nights.
            controls.mutableState.value = SessionState(online = true, phase = Phase.Playing, sessionId = 2)
            service.onStartCommand(Intent(), 0, 2)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            controls.mutableState.value = controls.state.value.copy(online = false, phase = Phase.Finished)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun silentOnlineServiceStaysForegroundAndReleasesWakeLockOnDestroy() {
        val controls = RecordingControls(SessionState(online = true, onlinePhase = "unavailable", phase = Phase.Playing))
        TestApp.graph = TestGraph(controls, FakeMediaPlayer())
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(), 0, 1)
            assertEquals(PlaybackService.IDLE_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
            assertFalse(shadowOf(service).isForegroundStopped)
            val lock: PowerManager.WakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            service.onUpdateNotification(service.sessions.single(), false)
            assertFalse(shadowOf(service).isForegroundStopped)
        } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
