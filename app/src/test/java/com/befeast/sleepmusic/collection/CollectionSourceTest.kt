package com.befeast.sleepmusic.collection

import com.befeast.sleepmusic.playback.*
import com.befeast.sleepmusic.playback.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionSourceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = CheckedTestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private fun settle() { scope.testScheduler.runCurrent(); scope.assertHealthy() }
    private fun source(server: CollectionFixture) = DownloadCollectionSource(app, scope, dispatcher, server.base)

    @org.junit.After fun down() { scope.assertHealthy(); scope.cancel() }

    @Test fun launcherWaitsThroughFailureAndRetryThenOfflineRestartDoesNotRequestNetwork() = CollectionFixture().use { server ->
        val source = source(server)
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val port = FakePlayerPort(clock, calls)
        val engine = PlaybackEngine(scope, clock, port, FakeAudioControl(calls), InMemoryPlaybackStore(), source, RecordingHost(calls))
        engine.onUiEntry(EngineHarness.LAUNCHER)
        settle()
        assertEquals(CollectionPhase.FirstRunError, source.status.value.phase)
        assertNull(source.collection.value)
        assertTrue(port.started.isEmpty())
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1, 2)))
        source.refresh(); settle()
        assertEquals(CollectionPhase.Ready, source.status.value.phase)
        assertEquals(listOf("a"), port.started)
        assertTrue(engine.state.value.isActive)
        engine.stop(CommandSource.System)
        val count = server.requests.size
        server.routes.clear()
        val offline = source(server); settle()
        assertEquals(listOf("a"), offline.collection.value!!.ids)
        assertEquals(count, server.requests.size)
    }

    @Test fun refreshAddsTracksPreservesPreferencesAndOutageDoesNotStopPlayback() = CollectionFixture().use { server ->
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        val source = source(server); settle()
        val store = SharedPrefsPlaybackStore(app).apply { setLiked("a", true); block("b") }
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val port = FakePlayerPort(clock, calls)
        val engine = PlaybackEngine(scope, clock, port, FakeAudioControl(calls), store, source, RecordingHost(calls))
        engine.onUiEntry(EngineHarness.LAUNCHER); settle()
        server.manifest(listOf("a", "b"), mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2)))
        source.refresh(); settle()
        assertEquals(listOf("a", "b"), source.collection.value!!.ids)
        assertEquals(setOf("a"), SharedPrefsPlaybackStore(app).liked())
        assertEquals(setOf("b"), SharedPrefsPlaybackStore(app).blocked())
        val old = source.collection.value
        server.routes.clear()
        source.refresh(); settle()
        assertEquals(CollectionPhase.RefreshError, source.status.value.phase)
        assertSame(old, source.collection.value)
        assertTrue(engine.state.value.isActive)
        assertEquals(listOf("a"), port.started)
        assertEquals(1, engine.state.value.playableCount)
    }

    @Test fun cancelledLauncherAndPassiveEntryNeverStartOnDownloadCompletion() = CollectionFixture().use { server ->
        val source = source(server)
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val port = FakePlayerPort(clock, calls)
        val engine = PlaybackEngine(scope, clock, port, FakeAudioControl(calls), InMemoryPlaybackStore(), source, RecordingHost(calls))
        engine.onUiEntry(EngineHarness.LAUNCHER)
        engine.cancelPendingStart()
        settle()
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        source.refresh(); settle()
        assertEquals(Phase.Idle, engine.state.value.phase)
        assertTrue(calls.isEmpty())
    }
    @Test
    @Config(application = TestApp::class)
    fun activityOkRetriesFirstRunFailureAndLeavingCancelsPendingStart() = CollectionFixture().use { server ->
        val source = source(server)
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val port = FakePlayerPort(clock, calls)
        val engine = PlaybackEngine(scope, clock, port, FakeAudioControl(calls), InMemoryPlaybackStore(), source, RecordingHost(calls))
        TestApp.graph = object : PlaybackGraph by TestGraph(engine) { override val downloads = source }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        val activity = org.robolectric.Robolectric.buildActivity(com.befeast.sleepmusic.MainActivity::class.java, intent).setup()
        settle()
        assertEquals(CollectionPhase.FirstRunError, source.status.value.phase)
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        val key = android.view.KeyEvent.KEYCODE_DPAD_CENTER
        activity.get().dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
        activity.get().dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        settle()
        assertEquals(CollectionPhase.Ready, source.status.value.phase)
        assertEquals(listOf("a"), port.started)
        activity.pause().stop()
        assertTrue("leaving never stops an active night", engine.state.value.isActive)
        activity.destroy()
        Unit
    }

    @Test
    @Config(application = TestApp::class)
    fun leavingActivityDuringFirstDownloadCancelsAutoplay() = CollectionFixture().use { server ->
        val source = source(server)
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val engine = PlaybackEngine(scope, clock, FakePlayerPort(clock, calls), FakeAudioControl(calls), InMemoryPlaybackStore(), source, RecordingHost(calls))
        TestApp.graph = object : PlaybackGraph by TestGraph(engine) { override val downloads = source }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        val activity = org.robolectric.Robolectric.buildActivity(com.befeast.sleepmusic.MainActivity::class.java, intent).setup()
        activity.pause().stop()
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        settle()
        assertEquals(CollectionPhase.Ready, source.status.value.phase)
        assertEquals(Phase.Idle, engine.state.value.phase)
        assertTrue(calls.isEmpty())
        activity.destroy()
        Unit
    }

    @Test fun launcherBetweenIdleSnapshotAndPublicationKeepsSelectedFilePlayable() = CollectionFixture().use { server ->
        var afterIdleRead: () -> Unit = {}
        var reads = 0
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1, 2)))
        val source = DownloadCollectionSource(app, scope, dispatcher, server.base, keepId = {
            // Capture idle, then interleave the launcher before returning that stale observation.
            if (++reads == 2) afterIdleRead()
            null
        })
        settle()
        val clock = Clock { scope.testScheduler.currentTime }
        val calls = mutableListOf<String>()
        val port = FakePlayerPort(clock, calls)
        val engine = PlaybackEngine(scope, clock, port, FakeAudioControl(calls), InMemoryPlaybackStore(), source, RecordingHost(calls))
        settle()
        // The second read is the pruning idle check, immediately before the deletion loop.
        reads = 0
        afterIdleRead = {
            assertEquals(listOf("a"), source.collection.value!!.ids)
            engine.onUiEntry(EngineHarness.LAUNCHER)
            assertTrue(port.playing)
            assertTrue(port.current!!.file.isFile)
        }
        server.manifest(listOf("b"), mapOf("b" to byteArrayOf(3)))
        source.refresh(); settle()
        assertEquals(listOf("b"), source.collection.value!!.ids)
        assertEquals("a", port.current!!.trackId)
        assertArrayEquals(byteArrayOf(1, 2), port.current!!.file.readBytes())
        scope.testScheduler.advanceTimeBy(1000); settle()
        assertTrue(port.playing)
        assertTrue(port.positionMs >= 1000)
    }

    private class ProcessDeath : Error()

    private fun interruptDuringC(server: CollectionFixture) {
        val library = com.befeast.sleepmusic.Library(app, openOutput = { file ->
            if (file.name.startsWith("c.")) {
                file.writeBytes(byteArrayOf(99))
                throw ProcessDeath() // B has been renamed; bypass all normal Exception cleanup.
            }
            file.outputStream()
        })
        assertThrows(ProcessDeath::class.java) { library.refresh(server.base, null, true) {} }
        assertTrue(java.io.File(app.filesDir, "collection/manifest.pending.json").isFile)
    }

    @Test fun interruptedRefreshRetainsVerifiedBOnRestartAndRetriesOnlyC() = CollectionFixture().use { server ->
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        source(server); settle()
        server.manifest(listOf("a", "b", "c"), mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2), "c" to byteArrayOf(3)))
        interruptDuringC(server)
        val restarted = source(server); settle()
        assertEquals(listOf("a"), restarted.collection.value!!.ids)
        restarted.refresh(); settle()
        assertEquals(listOf("a", "b", "c"), restarted.collection.value!!.ids)
        assertEquals(1, server.requests.count { it == "/audio/a.opus" })
        assertEquals(1, server.requests.count { it == "/audio/b.opus" })
        assertEquals(2, server.requests.count { it == "/audio/c.opus" })
        assertFalse(server.headers.any { it.startsWith("Range:", true) })
        assertFalse(java.io.File(app.filesDir, "collection/manifest.pending.json").exists())
        val orphan = java.io.File(app.filesDir, "collection/audio/orphan.opus").apply { writeBytes(byteArrayOf(9)) }
        val count = server.requests.size
        val committed = source(server); settle()
        assertEquals(listOf("a", "b", "c"), committed.collection.value!!.ids)
        assertFalse(orphan.exists())
        assertEquals(count, server.requests.size)
    }

    @Test fun interruptedFirstRunRetainsVerifiedFilesOnRestart() = CollectionFixture().use { server ->
        server.manifest(listOf("b", "c"), mapOf("b" to byteArrayOf(2), "c" to byteArrayOf(3)))
        interruptDuringC(server)
        assertNull(com.befeast.sleepmusic.Library(app).loadLocal())
        val restarted = source(server); settle()
        assertEquals(listOf("b", "c"), restarted.collection.value!!.ids)
        assertEquals(1, server.requests.count { it == "/audio/b.opus" })
        assertEquals(2, server.requests.count { it == "/audio/c.opus" })
        assertFalse(java.io.File(app.filesDir, "collection/manifest.pending.json").exists())
    }

    @Test fun corruptOrUnreadablePendingManifestKeepsCommittedCollectionPlayable() = CollectionFixture().use { server ->
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        source(server); settle()
        val pending = java.io.File(app.filesDir, "collection/manifest.pending.json")
        val count = server.requests.size
        for (contents in listOf("{broken", "{\"tracks\":[{\"id\":\"../bad\",\"file\":\"bad.opus\"}],\"selection\":[\"../bad\"]}")) {
            pending.writeText(contents)
            val restarted = source(server); settle()
            assertEquals(CollectionPhase.Ready, restarted.status.value.phase)
            val track = restarted.collection.value!!.tracks.single()
            assertArrayEquals(byteArrayOf(1), restarted.fileFor(track).readBytes())
        }
        pending.delete(); pending.mkdir()
        val restarted = source(server); settle()
        assertEquals(listOf("a"), restarted.collection.value!!.ids)
        assertEquals(count, server.requests.size)
    }

    @Test fun overlappingRefreshIsIgnored() = CollectionFixture().use { server ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var block = false
        val library = com.befeast.sleepmusic.Library(app) { from, to ->
            if (block) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            from.renameTo(to)
        }
        server.manifest(listOf("a"), mapOf("a" to byteArrayOf(1)))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val io = executor.asCoroutineDispatcher()
        val workerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io)
        try {
            val source = DownloadCollectionSource(app, workerScope, io, server.base, library = library)
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10000) {
                source.status.first { it.phase == CollectionPhase.Ready }
            } }
            block = true
            source.refresh()
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            source.refresh()
            // FIFO barrier on the remaining worker proves the second refresh ran while locked.
            executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2, server.requests.count { it == "/manifest.json" })
            release.countDown()
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10000) {
                source.status.first { it.phase == CollectionPhase.Ready }
            } }
            assertEquals(2, server.requests.count { it == "/manifest.json" })
        } finally {
            release.countDown(); workerScope.cancel(); io.close()
        }
    }

}
