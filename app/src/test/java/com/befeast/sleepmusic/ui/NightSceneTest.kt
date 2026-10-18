package com.befeast.sleepmusic.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import com.github.takahirom.roborazzi.fetchImage
import androidx.compose.ui.graphics.toArgb
import com.befeast.sleepmusic.Library
import com.befeast.sleepmusic.playback.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = Goldens.TV_1080P)
@OptIn(ExperimentalTestApi::class)
class NightSceneTest {
    @get:Rule val compose = createComposeRule()
    private val playing = SessionState(phase = Phase.Playing,
        track = NowPlaying("fixture", "Remember Eternity 07", "Hji Zerena", "Piano", false),
        timerRemainingSec = 6420, playableCount = 60)
    private fun assertFill(tag: String) {
        val bitmap = compose.onNodeWithTag(tag).fetchSemanticsNode().fetchImage(Goldens.options.recordOptions)!!
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        org.junit.Assert.assertTrue("Visible focus fill for $tag", pixels.count { it == BrandFocus.Fill.toArgb() } > pixels.size / 2)
    }
    @Test fun player() {
        compose.setContent { NightScene(playing) }
        compose.onRoot().captureGolden("night-player.png")
    }
    @Test fun longTitle() {
        compose.setContent { NightScene(playing.copy(track = playing.track!!.copy(
            title = "Whatever Happened to Peter Roe?, Part 1", artist = "TRG Banks", category = "Ambient"), timerRemainingSec = 1380)) }
        compose.onRoot().captureGolden("night-player-long.png")
    }
    @Test fun menu() {
        compose.setContent { NightScene(playing, menuOpen = true) }
        compose.onNodeWithTag("timer-90").assertIsFocused()
        compose.onRoot().captureGolden("night-menu.png")
    }
    @Test fun download() {
        compose.setContent { NightScene(SessionState(), CollectionStatus(CollectionPhase.Downloading,
            Library.Progress(25, 60, 42, 100, ""))) }
        compose.onRoot().captureGolden("night-download.png")
    }
    @Test fun black() {
        compose.setContent { NightScene(playing.copy(blackScreen = true), CollectionStatus(CollectionPhase.RefreshError), menuOpen = true) }
        compose.onRoot().captureGolden("night-black.png")
    }
    @Test fun finished() {
        compose.setContent { NightScene(playing.copy(phase = Phase.Finished), menuOpen = true) }
        compose.onRoot().captureGolden("night-finished.png")
    }
    @Test fun dpadReachesAndActivatesEveryMenuControl() {
        val calls = mutableListOf<String>()
        compose.setContent { NightScene(playing, menuOpen = true,
            onTimer = { calls += "timer-$it" }, onVolume = { calls += "volume" }, onTvOff = { calls += "tv" },
            onRefresh = { calls += "refresh" }, onLike = { calls += "like" }, onNever = { calls += "never" }) }
        fun key(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }
        repeat(3) { key(Key.DirectionLeft) }
        for (value in SleepTimer.CHOICES) {
            compose.onNodeWithTag("timer-$value").assertIsFocused()
            assertFill("timer-$value")
            key(Key.DirectionCenter)
            if (value != 180) key(Key.DirectionRight)
        }
        for (tag in listOf("volume", "tv", "refresh", "like", "never")) {
            key(Key.DirectionDown)
            compose.onNodeWithTag(tag).assertIsFocused()
            assertFill(tag)
            key(Key.DirectionCenter)
        }
        org.junit.Assert.assertEquals(SleepTimer.CHOICES.map { "timer-$it" } + listOf("volume", "tv", "refresh", "like", "never"), calls)
    }
    @Test fun firstRunRetryAndRefreshNotice() {
        var retries = 0
        compose.setContent { NightScene(SessionState(), CollectionStatus(CollectionPhase.FirstRunError), onRefresh = { retries++ }) }
        compose.onNodeWithTag("retry").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertFill("retry")
        org.junit.Assert.assertEquals(1, retries)
    }
    @Test fun refreshErrorKeepsMenuAndTrackActions() {
        compose.setContent { NightScene(playing, CollectionStatus(CollectionPhase.RefreshError), menuOpen = true) }
        compose.onNodeWithText("Refresh failed. Your saved collection is still available.").assertExists()
        compose.onNodeWithText("Remember Eternity 07").assertDoesNotExist()
        compose.onNodeWithTag("like").assertExists()
    }
}
