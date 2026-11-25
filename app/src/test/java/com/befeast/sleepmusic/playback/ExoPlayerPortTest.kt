package com.befeast.sleepmusic.playback

import androidx.media3.common.PlaybackException
import com.befeast.sleepmusic.Fades
import com.befeast.sleepmusic.playback.testing.EngineHarness
import com.befeast.sleepmusic.playback.testing.FakeMediaPlayer
import com.befeast.sleepmusic.playback.testing.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PRD 3 / AC-5: the player mirrors the queue as `[current, upNext]`; both a natural track end and
 * an engine skip arrive as one onAdvanced, and the current item is always index 0. PRD 1 / AC-2:
 * the silent start reaches a player the port creates later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExoPlayerPortTest {
    private val player = FakeMediaPlayer()
    private var created = 0
    private val port = ExoPlayerPort {
        created++
        player
    }
    private val events = mutableListOf<String>()

    init {
        port.setListener(object : PlayerPort.Listener {
            override fun onPlaying() { events += "playing" }
            override fun onAdvanced() { events += "advanced" }
            override fun onEnded() { events += "ended" }
            override fun onPausedBySystem() { events += "pausedBySystem" }
            override fun onError(code: String) { events += "error:$code" }
        })
    }

    private fun item(id: String) = QueueItem(id, File("collection/audio/$id.opus"), "Title $id", "Artist $id")

    @Test
    fun noPlayerIsBuiltBeforeTheFirstLoad() {
        port.setUpNext(item("b"))
        port.advance()
        port.play()
        port.pause()
        port.setVolume(0.5f)
        port.stopAndClear()
        assertEquals(0, created)
        assertEquals(0L, port.positionMs)
        assertEquals(-1L, port.durationMs)
    }

    @Test
    fun loadQueuesCurrentAndLookaheadAndPlays() {
        port.load(item("a"), item("b"))
        assertEquals(listOf("a", "b"), player.mediaIds)
        assertEquals(listOf("setMediaItems(a, b)", "prepare", "setPlayWhenReady(true)"), player.calls)
        assertTrue(player.isPlaying)
        assertEquals(listOf("playing"), events)
        assertEquals("Title a", player.mediaMetadata.title)
    }

    @Test
    fun setUpNextReplacesOrDropsOnlyTheItemAfterTheCurrentOne() {
        port.load(item("a"), item("b"))
        port.setUpNext(item("c"))
        assertEquals(listOf("a", "c"), player.mediaIds)
        port.setUpNext(null)
        assertEquals(listOf("a"), player.mediaIds)
        port.setUpNext(item("d"))
        assertEquals(listOf("a", "d"), player.mediaIds)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun advanceMovesToTheLookaheadAndDropsThePlayedItem() {
        port.load(item("a"), item("b"))
        events.clear()
        port.advance()
        assertEquals(listOf("b"), player.mediaIds)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(listOf("advanced"), events)
    }

    @Test
    fun aNaturalTrackEndIsTheSameAdvance() {
        port.load(item("a"), item("b"))
        events.clear()
        player.completeCurrentItem()
        assertEquals(listOf("b"), player.mediaIds)
        assertEquals(listOf("advanced"), events)
    }

    @Test
    fun theEndWithNothingQueuedIsReported() {
        port.load(item("a"), null)
        events.clear()
        player.completeCurrentItem()
        assertEquals(listOf("ended"), events)
    }

    @Test
    fun onlyPausesTheEngineDidNotAskForAreReportedAsSystemPauses() {
        port.load(item("a"), item("b"))
        events.clear()
        port.pause()
        assertFalse(player.playWhenReady)
        assertTrue(events.isEmpty())
        port.play()
        events.clear()
        player.loseAudioFocus()
        assertEquals(listOf("pausedBySystem"), events)
    }

    @Test
    fun errorsAreReportedWithTheirCodeName() {
        port.load(item("a"), item("b"))
        player.failWith(PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals("error:ERROR_CODE_DECODING_FAILED", events.last())
    }

    @Test
    fun stopAndClearEmptiesThePlayerWithoutAnEndEvent() {
        port.load(item("a"), item("b"))
        events.clear()
        port.stopAndClear()
        assertTrue(player.mediaIds.isEmpty())
        assertFalse("ended" in events)
    }

    @Test
    fun aVolumeSetBeforeThePlayerExistsAppliesToThePlayerTheLoadCreates() {
        port.setVolume(0f)
        assertEquals(0, created)
        port.load(item("a"), item("b"))
        assertEquals(1, created)
        assertEquals("a new player starts at volume 1 unless the port applies the silent start", 0f, player.playerVolume, 0f)
    }

    @Test
    fun theFirstSessionOfAProcessStartsSilentAndOnlyRisesUnderTheFadeIn() {
        // Regression: the engine silenced the port before the player existed, and the first player
        // of the process played at volume 1 until the fade-in curve was large enough to be written.
        val inner = FakeMediaPlayer()
        var built = 0
        val h = EngineHarness(tracks = Tracks.many(4, durationSec = 3_600), enginePort = ExoPlayerPort { built++; inner })
        var startedAt = Long.MAX_VALUE
        inner.positionMs = { (h.now - startedAt).coerceAtLeast(0L) }
        assertEquals("an idle process builds no player", 0, built)
        startedAt = h.now
        h.launcherStart()
        assertEquals(1, built)
        assertTrue(inner.isPlaying)
        assertEquals(0f, inner.playerVolume, 0f)
        var last = 0f
        repeat(60) {
            h.advance(PlaybackEngine.TICK_MS)
            val elapsed = h.now - startedAt
            val volume = inner.playerVolume
            val fadeIn = Fades.fadeIn(elapsed, Fades.SESSION_FADE_IN_MS).toFloat()
            assertTrue("at $elapsed ms the volume $volume is above the session fade-in $fadeIn", volume <= fadeIn + 1e-7f)
            assertTrue("at $elapsed ms the volume fell from $last to $volume", volume >= last)
            last = volume
        }
        assertTrue("audible by 3 s: $last", last > 0f)
    }

    @Test
    fun volumeIsThePlayerVolume() {
        port.load(item("a"), null)
        port.setVolume(0.25f)
        assertEquals(0.25f, player.playerVolume, 0f)
        port.setVolume(1.5f)
        assertEquals(1f, player.playerVolume, 0f)
    }
}
