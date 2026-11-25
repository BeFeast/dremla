package com.befeast.sleepmusic.playback

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PRD 8 / AC-10 and the media-key part of PRD 7 / AC-9. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class MainActivityKeysTest {
    private val controls = RecordingControls()
    private lateinit var activity: MainActivity

    @Before
    fun start() {
        TestApp.graph = TestGraph(controls)
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun down(keyCode: Int, repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeat)
    }

    private fun up(keyCode: Int, flags: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags)
    }

    /** The repeat the system sends once a key is held past the long-press timeout. */
    private fun longPress(keyCode: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS)
    }

    @Test
    fun volumeAndMuteKeysAreNotIntercepted() {
        listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE)
            .forEach {
                assertFalse("key $it goes to the system", activity.onKeyDown(it, down(it)))
                assertFalse(activity.onKeyUp(it, up(it)))
            }
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun volumeKeysControlTheMusicStream() {
        assertEquals(AudioManager.STREAM_MUSIC, activity.volumeControlStream)
    }

    @Test
    fun okTogglesPauseOnReleaseAndRightSkipsOnPress() {
        val okDown = down(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, okDown))
        assertTrue("nothing before the release", controls.calls.isEmpty())
        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, up(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.FLAG_TRACKING)))
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, down(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(listOf("togglePause(Remote)", "skip(Remote)"), controls.calls)
    }

    @Test
    fun okAndRightThroughTheWindowReachTheSameCommands() {
        activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_DPAD_RIGHT))
        activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_RIGHT))
        activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("skip(Remote)"), controls.calls)
        activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("skip(Remote)", "togglePause(Remote)"), controls.calls)
    }

    @Test
    fun aLongPressOfOkThroughTheWindowNeverToggles() {
        // #6 opens the menu on a long press of OK; the listener's music must not pause on the way.
        assertTrue(activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(activity.dispatchKeyEvent(longPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(controls.calls.isEmpty())
        // The next short press works again.
        activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_DPAD_CENTER))
        activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("togglePause(Remote)"), controls.calls)
    }

    @Test
    fun anOkReleaseWhosePressWentElsewhereDoesNothing() {
        assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, up(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun mediaKeysGoToTheEngineAndAreAlwaysConsumed() {
        val expected = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "togglePause(Remote)",
            KeyEvent.KEYCODE_MEDIA_PLAY to "play(Remote)",
            KeyEvent.KEYCODE_MEDIA_PAUSE to "pause(Remote)",
            KeyEvent.KEYCODE_MEDIA_NEXT to "skip(Remote)",
        )
        expected.forEach { (key, call) ->
            controls.calls.clear()
            assertTrue(activity.onKeyDown(key, down(key)))
            assertTrue(activity.onKeyUp(key, up(key)))
            assertEquals(listOf(call), controls.calls)
        }
        listOf(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND).forEach { key ->
            controls.calls.clear()
            assertTrue("consumed $key", activity.onKeyDown(key, down(key)))
            assertTrue(activity.onKeyUp(key, up(key)))
            assertTrue(controls.calls.isEmpty())
        }
    }

    @Test
    fun heldKeysDoNotRepeatCommands() {
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)))
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 3)))
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun navigationKeysAreLeftToTheUiAndTheSystem() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertFalse(activity.onKeyDown(it, down(it))) }
        assertTrue(controls.calls.isEmpty())
    }
}
