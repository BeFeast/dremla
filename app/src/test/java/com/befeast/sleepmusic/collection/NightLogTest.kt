package com.befeast.sleepmusic.collection

import com.befeast.sleepmusic.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.PrintWriter
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightLogTest {
    private val app = RuntimeEnvironment.getApplication()
    private val volume = StartVolumeResult(25, 5, false, 15, 15, false, false, null)
    private fun dump(log: NightLog) = StringWriter().also { log.dump(PrintWriter(it)) }.toString()

    @Test fun stableFormatPersistsStartTrackErrorEndAndNeverRawErrorText() {
        var now = 1000L
        val log = NightLog(app) { now }
        log.sessionStarted(1, 120, volume)
        now = 2000
        log.trackStarted(1, "amb-001")
        log.playbackError(1, "ERROR_CODE_IO_UNSPECIFIED")
        log.playbackError(1, "sensitive exception text")
        log.collectionError()
        now = 3000
        log.sessionEnded(1, EndReason.TimerEnded)
        val text = dump(NightLog(app) { now })
        assertEquals("""
            Dremla night log v1 (last 14 nights; timestamps epoch-ms)
            storage=ok nights=1
            night start=1000 end=3000 reason=TimerEnded timerMinutes=120
              volume={"before":5,"after":15,"mutedAfter":false,"fixed":false}
              2000 track=amb-001
              2000 error=ERROR_CODE_IO_UNSPECIFIED
              2000 error=PlaybackFailed
              2000 error=CollectionRefreshFailed

        """.trimIndent(), text)
    }

    @Test fun keepsExactlyFourteenSessionsAcrossProcessRestartAndMarksInterruptedNight() {
        var now = 0L
        val log = NightLog(app) { now }
        repeat(16) { index ->
            now = index * 1000L
            log.sessionStarted(index, 120, volume)
            log.trackStarted(index, "track-$index")
            if (index < 15) log.sessionEnded(index, EndReason.Stopped)
        }
        val text = dump(NightLog(app) { 17000 })
        assertTrue(text.contains("nights=14"))
        assertFalse(text.contains("track=track-0\n"))
        assertFalse(text.contains("track=track-1\n"))
        assertTrue(text.contains("track=track-2\n"))
        assertTrue(text.contains("night start=15000 end=17000 reason=ProcessInterrupted"))
        assertEquals(14, text.lineSequence().count { it.startsWith("night start=") })
    }

    @Test fun failedStorageDoesNotCrashPlaybackAndIsVisibleInDump() {
        val dir = java.io.File(app.filesDir, "night-log.json.new")
        dir.mkdirs(); java.io.File(dir, "occupied").writeText("x")
        val log = NightLog(app) { 0 }
        log.sessionStarted(1, 120, volume)
        log.trackStarted(1, "a")
        log.sessionEnded(1, EndReason.Stopped)
        assertTrue(dump(log).contains("storage=error"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun productionGraphWritesEngineEventsToApplicationJournal() {
        val scope = com.befeast.sleepmusic.playback.testing.CheckedTestScope()
        try {
            val graph = AppPlaybackGraph(
                app, scope,
                com.befeast.sleepmusic.playback.testing.FakeCollectionSource(
                    PlayableCollection(com.befeast.sleepmusic.playback.testing.Tracks.many(2), null)),
                Clock { scope.testScheduler.currentTime },
                { com.befeast.sleepmusic.playback.testing.FakeMediaPlayer() },
            )
            graph.controls.onUiEntry(com.befeast.sleepmusic.playback.testing.EngineHarness.LAUNCHER)
            scope.testScheduler.runCurrent()
            assertTrue(graph.controls.state.value.isActive)
            graph.controls.stop(CommandSource.System)
            val text = dump((app as com.befeast.sleepmusic.SleepMusicApp).nightLog)
            assertTrue(text.contains("nights=1"))
            assertTrue(text.contains("reason=Stopped"))
            assertTrue(text.contains("track="))
            scope.assertHealthy()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun corruptJournalDoesNotPreventANewNight() {
        java.io.File(app.filesDir, "night-log.json").writeText("[{}]")
        val log = NightLog(app) { 0 }
        log.sessionStarted(1, 120, volume)
        log.sessionEnded(1, EndReason.Stopped)
        assertTrue(dump(log).contains("nights=1"))
        assertTrue(dump(log).contains("reason=Stopped"))
    }

}
