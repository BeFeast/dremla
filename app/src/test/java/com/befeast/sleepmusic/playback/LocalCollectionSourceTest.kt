package com.befeast.sleepmusic.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Seam for #4: the local app-private collection the ported Library reads; no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCollectionSourceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val dir = File(app.filesDir, "collection")

    private fun source() = LocalCollectionSource(app, TestScope(), Dispatchers.Unconfined)

    @Test
    fun aFreshInstallLoadsAnEmptyCollection() {
        assertEquals(PlayableCollection.EMPTY, source().collection.value)
    }

    @Test
    fun onlyTracksWhoseFileIsPresentArePlayable() {
        File(dir, "audio").mkdirs()
        File(dir, "manifest.json").writeText(
            """
            {"version": 1, "generated": "2026-10-18T00:00:00Z",
             "tracks": [
               {"id": "amb-001", "title": "Low Clouds", "artist": "A", "category": "ambient", "duration_sec": 600,
                "file": "audio/amb-001.opus", "bytes": 3, "sha256": "", "loudness": {"i_lufs": -24.0}, "flags": []},
               {"id": "pno-002", "title": "Night Piano", "artist": "B", "category": "piano", "duration_sec": 300,
                "file": "audio/pno-002.opus", "bytes": 3, "sha256": "", "flags": []},
               {"id": "amb-003", "title": "Not Here", "artist": "C", "category": "ambient", "duration_sec": 300,
                "file": "audio/amb-003.opus", "bytes": 3, "sha256": "", "flags": []}
             ],
             "selection": ["amb-001", "amb-003"]}
            """.trimIndent(),
        )
        File(dir, "audio/amb-001.opus").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "audio/pno-002.opus").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "audio/amb-003.opus").writeBytes(byteArrayOf(1)) // truncated: not available
        val source = source()
        val collection = source.collection.value!!
        assertEquals(listOf("amb-001", "pno-002"), collection.ids)
        assertEquals(listOf("amb-001", "amb-003"), collection.selection)
        assertEquals(File(dir, "audio/amb-001.opus"), source.fileFor(collection.track("amb-001")!!))
    }

    @Test
    fun anUnreadableManifestLoadsAnEmptyCollection() {
        dir.mkdirs()
        File(dir, "manifest.json").writeText("{ not json")
        assertEquals(PlayableCollection.EMPTY, source().collection.value)
    }
}
