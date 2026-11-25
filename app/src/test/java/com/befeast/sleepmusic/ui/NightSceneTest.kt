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
    @Test fun aNightPlayingTheFirstTracksShowsThePlayerWhileTheRestDownloads() {
        // #83: a first night starts on the first verified tracks; the download scene is for waiting only.
        compose.setContent { NightScene(playing.copy(collectionLoaded = true),
            CollectionStatus(CollectionPhase.Downloading, Library.Progress(3, 47, 1, 2, "x"))) }
        compose.onNodeWithText("Remember Eternity 07").assertExists()
        compose.onNodeWithText("Downloading the collection").assertDoesNotExist()
    }
    @Test fun starting() {
        compose.setContent { NightScene(playing.copy(startingAudio = true)) }
        compose.onNodeWithText("STARTING").assertExists()
        compose.onRoot().captureGolden("night-player-starting.png")
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
    @Test fun menuInAClockNight() {
        // #73: a Clock night never turns the TV off; the row says why it does not react.
        // With the Music source row, as on the TX10 (production supports online).
        compose.setContent { NightScene(playing.copy(clockNight = true, tvOff = false, timerMinutes = 45), menuOpen = true, onSource = {}) }
        compose.onNodeWithTag("timer-90").assertIsFocused()
        compose.onNodeWithText("off · Clock night").assertExists()
        compose.onRoot().captureGolden("night-menu-clock.png")
    }
    @Test fun menuInARegularNightKeepsTheRouteWording() {
        compose.setContent { NightScene(playing.copy(tvOff = false), menuOpen = true) }
        compose.onNodeWithText("off · not enabled").assertExists()
        compose.onAllNodesWithText("off · Clock night").assertCountEquals(0)
    }
    @Test fun download() {
        compose.setContent { NightScene(SessionState(), CollectionStatus(CollectionPhase.Downloading,
            Library.Progress(25, 60, 42, 100, ""))) }
        compose.onRoot().captureGolden("night-download.png")
    }
    @Test fun black() {
        compose.setContent { NightScene(playing.copy(blackScreen = true), CollectionStatus(CollectionPhase.RefreshError)) }
        compose.onRoot().captureGolden("night-black.png")
    }
    @Test fun menuExplicitlyOpenedOverBlackIsShown() {
        compose.setContent { NightScene(playing.copy(blackScreen = true), menuOpen = true) }
        compose.onNodeWithTag("timer-90").assertIsFocused()
    }
    @Test fun finished() {
        compose.setContent { NightScene(playing.copy(phase = Phase.Finished), menuOpen = true) }
        compose.onRoot().captureGolden("night-finished.png")
    }
    @Test fun dpadReachesAndActivatesEveryMenuControl() {
        val calls = mutableListOf<String>()
        compose.setContent { NightScene(playing, menuOpen = true,
            onTimer = { calls += "timer-$it" }, onTvOff = { calls += "tv" },
            onRefresh = { calls += "refresh" }, onLike = { calls += "like" }, onNever = { calls += "never" }) }
        fun key(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }
        repeat(3) { key(Key.DirectionLeft) }
        for (value in SleepTimer.CHOICES) {
            compose.onNodeWithTag("timer-$value").assertIsFocused()
            assertFill("timer-$value")
            key(Key.DirectionCenter)
            if (value != 180) key(Key.DirectionRight)
        }
        for (tag in listOf("tv", "refresh", "like", "never")) {
            key(Key.DirectionDown)
            compose.onNodeWithTag(tag).assertIsFocused()
            assertFill(tag)
            key(Key.DirectionCenter)
        }
        org.junit.Assert.assertEquals(SleepTimer.CHOICES.map { "timer-$it" } + listOf("tv", "refresh", "like", "never"), calls)
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
