package com.befeast.sleepmusic

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.befeast.sleepmusic.playback.Phase
import com.befeast.sleepmusic.playback.SessionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-only on a real TV box: an offline night that is paused and already black. Holding OK must
 * open the menu over black; BACK closes it. Keys are injected through the instrumentation into
 * the activity window. The pauses let the host take screenshots between the logged markers.
 */
@RunWith(AndroidJUnit4::class)
class OfflineBlackMenuDeviceTest {
    private val controls = DeviceUiTestApp.controls.also {
        it.reset(SessionState(online = false, phase = Phase.Paused, blackScreen = true, sessionId = 1,
            collectionLoaded = true, playableCount = 46))
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun holdingOkOpensMenuOverOfflineBlackScreenWithoutPlaybackCommands() {
        compose.onNodeWithTag("timer-90").assertDoesNotExist()
        mark("black-before-hold")
        hold()
        compose.onNodeWithTag("timer-90").assertIsFocused()
        assertEquals(emptyList<String>(), controls.calls)
        mark("menu-open")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("timer-90").assertDoesNotExist()
        assertEquals(emptyList<String>(), controls.calls)
        mark("black-after-back")
    }

    private fun mark(name: String) {
        compose.waitForIdle()
        Log.i(TAG, "DEVICE_UI $name")
        Thread.sleep(SCREENSHOT_WINDOW_MS)
    }

    private fun hold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(down, down + 600, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
            1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        instrumentation.sendKeySync(KeyEvent(down, down + 700, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitForIdle()
    }

    private fun key(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(down, down + 50, KeyEvent.ACTION_UP, code, 0))
        compose.waitForIdle()
    }

    private companion object {
        const val TAG = "DremlaDeviceUi"
        const val SCREENSHOT_WINDOW_MS = 6_000L
    }
}
