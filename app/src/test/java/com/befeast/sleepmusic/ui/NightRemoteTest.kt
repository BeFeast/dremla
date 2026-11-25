package com.befeast.sleepmusic.ui

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.playback.testing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = Goldens.TV_1080P, application = TestApp::class)
class NightRemoteTest {
    private val controls = RecordingControls().also { TestApp.graph = TestGraph(it) }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun blackoutClosesMenuAndLongOkReopensItOverBlack() {
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        compose.onNodeWithTag("timer-90").assertIsFocused()
        compose.runOnIdle {
            controls.mutableState.value = controls.state.value.copy(blackScreen = true,
                phase = com.befeast.sleepmusic.playback.Phase.Playing)
        }
        compose.onNodeWithTag("timer-90").assertDoesNotExist()
        compose.onRoot().captureGolden("night-black.png")
        // Offline night after blackout: holding OK must still reach the menu (TX10, 2026-10-21),
        // without pausing or choosing anything.
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        compose.onNodeWithTag("timer-90").assertIsFocused()
        assertTrue("Opening hold over black must not pause or choose", controls.calls.isEmpty())
        // BACK closes it; the scene is black again.
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("timer-90").assertDoesNotExist()
        compose.onRoot().captureGolden("night-black.png")
    }

    @Test fun longOkOpensAndMenuBoundariesRetainFocusWithoutPlaybackCommands() {
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        compose.onNodeWithTag("timer-90").assertIsFocused()
        assertTrue("Opening hold must not choose a tile or pause", controls.calls.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        assertTrue("Holding a menu control must not choose it", controls.calls.isEmpty())

        // Repeated navigation reaches and then pushes past each edge. Check focus after
        // every event pair, so transient focus loss cannot hide behind a later recovery.
        for ((code, boundary) in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT to "timer-30", KeyEvent.KEYCODE_DPAD_UP to "timer-30",
                KeyEvent.KEYCODE_DPAD_RIGHT to "timer-180", KeyEvent.KEYCODE_DPAD_DOWN to "never",
                KeyEvent.KEYCODE_DPAD_LEFT to "never", KeyEvent.KEYCODE_DPAD_RIGHT to "never")) {
            repeat(10) {
                press(code)
                compose.onAllNodes(isFocused()).assertCountEquals(1)
                compose.onNode(isFocused()).assert(
                    hasTestTag("volume") or hasTestTag("tv") or hasTestTag("refresh") or
                        hasTestTag("like") or hasTestTag("never") or
                        hasTestTag("timer-30") or hasTestTag("timer-45") or hasTestTag("timer-60") or
                        hasTestTag("timer-90") or hasTestTag("timer-120") or hasTestTag("timer-180"))
                assertTrue("Menu navigation must not invoke playback", controls.calls.isEmpty())
            }
            compose.onNodeWithTag(boundary).assertIsFocused()
        }
    }

    @Test fun backClosesMenuThenSecondBackUsesActivityDefault() {
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        compose.onNodeWithTag("timer-90").assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("timer-90").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(compose.activity.isFinishing)
            assertFalse("Closed menu must unregister its BACK handler",
                compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
        press(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertTrue("Second BACK must reach the activity default", compose.activity.isFinishing) }
    }

    @Test fun closedMenuOkTogglesPauseRightSkipsAndVolumeIsNotConsumed() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("togglePause(Remote)", "skip(Remote)"), controls.calls)
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            val consumed = press(code)
            assertFalse("Volume down event must fall through", consumed.first)
            assertFalse("Volume up event must fall through", consumed.second)
        }
        assertEquals(listOf("togglePause(Remote)", "skip(Remote)"), controls.calls)
    }

    /** Dispatch through the window/Compose focus chain and Android's key tracking. */
    private fun press(code: Int, longPress: Boolean = false): Pair<Boolean, Boolean> {
        val downTime = android.os.SystemClock.uptimeMillis()
        var downConsumed = false
        var upConsumed = false
        compose.runOnIdle {
            downConsumed = compose.activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
        }
        if (longPress) compose.runOnIdle {
            compose.activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_DOWN,
                code, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        }
        compose.runOnIdle {
            upConsumed = compose.activity.dispatchKeyEvent(KeyEvent(downTime, downTime + if (longPress) 700 else 50,
                KeyEvent.ACTION_UP, code, 0))
        }
        compose.waitForIdle()
        return downConsumed to upConsumed
    }
}
