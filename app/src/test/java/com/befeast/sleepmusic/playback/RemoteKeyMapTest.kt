package com.befeast.sleepmusic.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 8 / AC-10 (OK, Right, volume keys) and PRD 7 / AC-9 (media keys never fall through). */
class RemoteKeyMapTest {
    private val ok = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    @Test
    fun okPausesAndResumesOnItsRelease() {
        ok.forEach {
            assertEquals(RemoteCommand.TrackPress, RemoteKeyMap.onKeyDown(it, 0))
            assertEquals(RemoteCommand.TogglePause, RemoteKeyMap.onKeyUp(it, tracked = true, canceled = false))
        }
        assertEquals(RemoteCommand.TogglePause, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    }

    @Test
    fun okReleasedAfterALongPressOrAPressThatWentElsewhereDoesNothing() {
        ok.forEach {
            assertNull("long press cancelled the release", RemoteKeyMap.onKeyUp(it, tracked = true, canceled = true))
            assertNull("the press went to a focused control", RemoteKeyMap.onKeyUp(it, tracked = false, canceled = false))
            assertTrue("a long press of OK is reserved", RemoteKeyMap.consumesLongPress(it))
        }
    }

    @Test
    fun rightAndMediaNextSkip() {
        assertEquals(RemoteCommand.Skip, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertEquals(RemoteCommand.Skip, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, 0))
    }

    @Test
    fun mediaPlayAndPauseAreDirectional() {
        assertEquals(RemoteCommand.Play, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        assertEquals(RemoteCommand.Pause, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }

    @Test
    fun volumeAndMuteKeysAreNeverIntercepted() {
        listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE)
            .forEach {
                assertNull(RemoteKeyMap.onKeyDown(it, 0))
                assertNull(RemoteKeyMap.onKeyDown(it, 3))
                assertNull(RemoteKeyMap.onKeyUp(it, tracked = true, canceled = false))
                assertFalse(RemoteKeyMap.consumesLongPress(it))
            }
    }

    @Test
    fun otherMediaKeysAreConsumedWithoutACommand() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
        ).forEach {
            assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyDown(it, 0))
            assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyUp(it, tracked = false, canceled = false))
        }
    }

    @Test
    fun autoRepeatIsConsumedWithoutACommand() {
        assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, 1))
        assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 2))
    }

    @Test
    fun navigationAndBackStayWithTheSystemAndTheUi() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME)
            .forEach { assertNull(RemoteKeyMap.onKeyDown(it, 0)) }
    }

    @Test
    fun keyUpCarriesOnlyTheOkReleaseAndConsumesMediaKeys() {
        assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyUp(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, tracked = true, canceled = false))
        assertEquals(RemoteCommand.Consume, RemoteKeyMap.onKeyUp(KeyEvent.KEYCODE_MEDIA_NEXT, tracked = false, canceled = false))
        assertNull(RemoteKeyMap.onKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT, tracked = true, canceled = false))
        assertFalse(RemoteKeyMap.consumesLongPress(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(RemoteKeyMap.consumesLongPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }
}
