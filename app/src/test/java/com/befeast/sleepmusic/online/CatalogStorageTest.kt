package com.befeast.sleepmusic.online

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.befeast.sleepmusic.playback.*
import com.befeast.sleepmusic.playback.testing.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(name: String) = File(System.getProperty("fixtures")!!, name).readText()
    @Test fun nightJournalRecordsOnlyFixedOnlineReasons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val log = NightLog(context)
        log.sessionStarted(1, 120, StartVolumeResult(25, 5, false, 5, false, false))
        FailureReason.entries.forEach { log.playbackError(1, it.code) }
        log.playbackError(1, "https://media.example/private?signature=fixture")
        val text = File(context.filesDir, "night-log.json").readText()
        FailureReason.entries.forEach { assertTrue(text.contains(it.code)) }
        assertFalse(text.contains("https")); assertFalse(text.contains("signature"))
        assertTrue(text.contains("PlaybackFailed"))
    }

    @Test fun lastValidSurvivesInvalidUnavailableAndOlderVersionsAndRestart() {
        val file = File(temp.root, "catalog/current.json")
        var text = fixture("catalog-v2.json")
        var fail = false
        val repo = CatalogRepository(file) { if (fail) error("fetch failed") else text }
        assertEquals(2L, repo.refresh()!!.version)
        val bytes = file.readBytes()
        for (bad in listOf(fixture("catalog-v1.json"), "{}", "404")) {
            text = bad
            assertEquals(2L, repo.refresh()!!.version)
            assertArrayEquals(bytes, file.readBytes())
        }
        fail = true
        assertEquals(2L, repo.refresh()!!.version)
        assertEquals(2L, CatalogRepository(file).load()!!.version)
        assertFalse(File(file.parentFile, "current.json.part").exists())
        assertNull(CatalogRepository(File(temp.root, "missing.json")) { error("404") }.refresh())
    }

    @Test fun strictGrammarMatchesOnAndroidAndRejectsLenientExtensions() {
        val valid = fixture("catalog-v1.json")
        val variants = listOf(
            valid.replace("\"version\": 1", "\"version\": 01"),
            valid.replace("\"version\": 1", "\"version\": 0x1"),
            valid.replace("\"version\": 1", "\"version\": 1."),
            valid.replace("\"version\": 1", "\"version\": +1"),
            valid.replace("\"version\": 1", "\"version\": 1e0"),
            valid.replace("\"version\": 1", "\"version\": true"),
            valid.replace("\"version\": 1", "\"version\": 1, \"version\": 2"),
            valid.replace("\"schema\"", "'schema'"),
            valid.replace("\"schema\"", "/* comment */ \"schema\""),
            valid.replace("dremla.catalog/1", "dremla.catalog\\x2f1"),
            valid.replace("dremla.catalog/1", "dremla.catalog\n/1"),
            valid.dropLast(2) + ",}", valid + " false",
        )
        for (text in variants) assertThrows(Exception::class.java) { Catalog.parse(text) }
        assertEquals(1L, Catalog.parse(valid).version)
    }

    @Test fun sourceDefaultsOffPersistsChoiceAndNeverTouchesOfflineOnOnlineLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(SharedPrefsPlaybackStore.FILE, 0).edit().clear().commit()
        val prefs = SharedPrefsPlaybackStore(context)
        assertFalse(prefs.enabled)
        val offlineBag = BagState(setOf("offline-id"), "offline-id")
        prefs.saveBag(offlineBag)
        prefs.saveOnlineBag(BagState(setOf("yt-fxAmbient01"), "yt-fxAmbient01"))
        assertEquals(offlineBag, prefs.bag())
        val scope = CheckedTestScope()
        val localContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = temp.root
        }
        val body = "offline audio bytes".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val manifest = File(temp.root, "collection/manifest.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"version":1,"selection":["offline"],"tracks":[{"id":"offline","title":"Offline","file":"audio/offline.opus","bytes":${body.size},"sha256":"$sha"}]}""")
        }
        val library = com.befeast.sleepmusic.Library(localContext,
            openOutput = { error("Must not download") }, commitManifest = { _, _ -> error("Must not refresh") })
        val audio = library.localFile(library.loadLocal()!!.tracks.single()).apply { parentFile!!.mkdirs(); writeBytes(body) }
        val offline = DownloadCollectionSource(localContext, scope, StandardTestDispatcher(scope.testScheduler), baseUrl = "fixture:offline/", library = library)
        scope.testScheduler.runCurrent()
        assertEquals(CollectionPhase.Ready, offline.status.value.phase)
        // A stale file added after the initial offline load proves online launches never prune.
        val sentinel = File(audio.parentFile, "unselected.opus").apply { writeText("do not prune") }
        val before = listOf(manifest, audio, sentinel).associateWith { it.readBytes() }
        var offlineCalls = 0
        fun source() = SelectableSource(scope, prefs, { offlineCalls++; offline },
            CatalogRepository(File(temp.root, "online/current.json")) { fixture("catalog-v1.json") },
            StandardTestDispatcher(scope.testScheduler))
        val source = source()
        scope.testScheduler.runCurrent()
        assertSame(offline.collection.value, source.collection.value)
        source.setOnline(true); scope.testScheduler.runCurrent()
        assertTrue(SharedPrefsPlaybackStore(context).enabled)
        assertEquals(1, offlineCalls)
        assertEquals(1L, Catalog.parse(File(temp.root, "online/current.json").readText()).version)
        val relaunched = source(); relaunched.onLaunch(); scope.testScheduler.runCurrent()
        assertEquals(1, offlineCalls)
        source.setOnline(false); scope.testScheduler.runCurrent()
        assertSame(offline.collection.value, source.collection.value)
        assertFalse(prefs.enabled)
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(CollectionPhase.Ready, offline.status.value.phase)
        scope.assertHealthy(); scope.cancel()
    }

    @Test fun cachedCatalogStartsSynchronouslyAndRefreshAppliesNextLaunch() {
        val scope = CheckedTestScope()
        val io = ResolutionDispatcher()
        val file = temp.newFile("cached.json").apply { writeText(fixture("catalog-v1.json")) }
        val source = SelectableSource(scope, object : OnlinePreference { override var enabled = true },
            { error("offline") }, CatalogRepository(file) { fixture("catalog-v2.json") }, io)
        try {
            source.onLaunch()
            assertEquals(Catalog.parse(fixture("catalog-v1.json")).selection, source.collection.value!!.selection)
            scope.testScheduler.runCurrent(); io.runOne(); scope.testScheduler.runCurrent()
            assertEquals(Catalog.parse(fixture("catalog-v1.json")).selection, source.collection.value!!.selection)
            source.onLaunch()
            assertEquals(Catalog.parse(fixture("catalog-v2.json")).selection, source.collection.value!!.selection)
        } finally { scope.assertHealthy(); scope.cancel() }
    }

    @Test fun sleepRouteEndThenOnlineRelaunchClearsActivityDismissalWithOrWithoutCache() {
        for (missing in listOf(false, true)) {
            val scope = CheckedTestScope()
            val io = ResolutionDispatcher()
            val pref = object : OnlinePreference { override var enabled = true }
            val file = temp.newFile("night-catalog-$missing.json").apply { writeText(fixture("catalog-v1.json")) }
            val source = SelectableSource(scope, pref, { FakeCollectionSource(PlayableCollection.EMPTY) }, CatalogRepository(file) { fixture("catalog-v1.json") }, io)
            source.onLaunch(); scope.testScheduler.runCurrent(); io.runOne(); scope.testScheduler.runCurrent()
            val h = OnlineHarness(sourceOverride = source, sleepRoute = object : SleepRoute {
                override fun status() = SleepRouteStatus.DeviceAdmin
                override fun sleep() = true
            })
            try {
                scope.testScheduler.runCurrent()
                while (io.pending.isNotEmpty()) { io.runOne(); scope.testScheduler.runCurrent() }
                h.settle(); h.resolve(); h.expire(); assertTrue(h.state.closeActivity)
                // Lose the last valid document to also exercise the no-cache pending path.
                if (missing) {
                    file.delete()
                    source.setOnline(false); source.setOnline(true)
                }
                h.engine.onUiEntry(EngineHarness.LAUNCHER)
                assertFalse(h.state.closeActivity); assertNotEquals(Phase.Finished, h.state.phase)
                if (!missing) assertTrue("cached launch starts before network refresh", h.state.isActive)
                h.settle() // Observe pending state before the asynchronous catalog fetch completes.
                scope.testScheduler.runCurrent()
                while (io.pending.isNotEmpty()) { io.runOne(); scope.testScheduler.runCurrent() }
                h.settle(); h.resolve()
                assertTrue(h.state.isActive); assertFalse(h.state.closeActivity)
            } finally { h.close(); scope.cancel() }
        }
    }

    @Test fun switchBackPreservesSynchronouslyReemittedCollectionState() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val pref = object : OnlinePreference { override var enabled = true }
        val offline = FakeCollectionSource(PlayableCollection(Tracks.many(3), null))
        val source = SelectableSource(scope, pref, { offline },
            CatalogRepository(temp.newFile("empty.json")) { error("404") }, ResolutionDispatcher())
        val clock = Clock { 0L }
        val engine = PlaybackEngine(scope, clock, FakePlayerPort(clock, mutableListOf()),
            FakeAudioControl(mutableListOf()), InMemoryPlaybackStore(), source, RecordingHost(mutableListOf()))
        try {
            engine.setOnline(false)
            assertTrue(engine.state.value.collectionLoaded); assertEquals(3, engine.state.value.playableCount)
        } finally { engine.stop(CommandSource.System); scope.cancel() }
    }

    @Test fun pythonWhitespaceOnlyMetadataIsRejected() {
        val valid = fixture("catalog-v1.json")
        for (key in listOf("title", "artist", "playlist")) {
            val json = org.json.JSONObject(valid)
            val target = if (key == "playlist") json.getJSONArray("playlists").getJSONObject(0)
                else json.getJSONArray("tracks").getJSONObject(0)
            target.put(if (key == "playlist") "title" else key, "\u0085")
            assertThrows(Exception::class.java) { Catalog.parse(json.toString()) }
        }
    }

    @Test fun missingCatalogIsCompletedUnavailableWithoutOfflineFallback() {
        val scope = CheckedTestScope()
        val pref = object : OnlinePreference { override var enabled = true }
        val source = SelectableSource(scope, pref, { error("No offline fallback") },
            CatalogRepository(File(temp.root, "missing.json")) { error("404") }, StandardTestDispatcher(scope.testScheduler))
        source.onLaunch(); scope.testScheduler.runCurrent()
        assertEquals(PlayableCollection.EMPTY, source.collection.value)
        scope.assertHealthy(); scope.cancel()
    }
}
