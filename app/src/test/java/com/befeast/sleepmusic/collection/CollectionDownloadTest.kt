package com.befeast.sleepmusic.collection

import com.befeast.sleepmusic.Library
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionDownloadTest {
    private val app = RuntimeEnvironment.getApplication()
    private val a = ByteArray(4000) { 1 }
    private val b = ByteArray(7000) { 2 }

    @Test fun selectionOnlyAndWholeFileRetryWithoutLengthOrRange() = CollectionFixture().use { server ->
        server.manifest(listOf("a", "b"), mapOf("a" to a, "b" to b, "c" to a))
        server.routes["/audio/b.opus"] = CollectionFixture.Response(b.copyOf(100))
        val library = Library(app)
        assertTrue(runCatching { library.refresh(server.base, null, true) {} }.exceptionOrNull() is IOException)
        assertNull(library.loadLocal())
        assertFalse(server.requests.contains("/audio/c.opus"))
        // Simulate the residue of process death mid-file: retry must truncate, never append.
        val audio = File(app.filesDir, "collection/audio")
        val part = File(audio, "b.${CollectionFixture.sha(b)}.opus.part")
        part.writeBytes(byteArrayOf(99, 98))
        server.routes["/audio/b.opus"] = CollectionFixture.Response(b)
        val result = library.refresh(server.base, null, true) {}
        assertEquals(2, result.ok)
        assertEquals(1, server.requests.count { it == "/audio/a.opus" })
        assertEquals(2, server.requests.count { it == "/audio/b.opus" })
        assertArrayEquals(b, library.localFile(result.manifest.tracks[1]).readBytes())
        assertFalse(part.exists())
        assertFalse(server.headers.any { it.startsWith("Range:", true) })
    }

    @Test fun missingHashFailsEvenWithExistingFile() = CollectionFixture().use { server ->
        server.manifest(listOf("a"), mapOf("a" to a), mapOf("a" to ""))
        val file = File(app.filesDir, "collection/audio/a.opus")
        file.parentFile!!.mkdirs(); file.writeBytes(a)
        assertTrue(runCatching { Library(app).refresh(server.base, null, true) {} }.exceptionOrNull() is IOException)
        assertFalse(server.requests.contains("/audio/a.opus"))
        assertNull(Library(app).loadLocal())
    }

    @Test fun failedManifestRenamePreservesOldManifestAndDroppedAudio() = CollectionFixture().use { server ->
        var commit = true
        val library = Library(app) { from, to -> commit && from.renameTo(to) }
        server.manifest(listOf("a"), mapOf("a" to a))
        val old = library.refresh(server.base, null, true) {}.manifest
        commit = false
        server.manifest(listOf("b"), mapOf("b" to b))
        assertTrue(runCatching { library.refresh(server.base, null, true) {} }.exceptionOrNull() is IOException)
        assertEquals(old, library.loadLocal())
        assertArrayEquals(a, library.localFile(old.tracks.single()).readBytes())
        commit = true
        library.refresh(server.base, null, true) {}
        assertTrue(library.localFile(old.tracks.single()).exists())
        library.pruneBeforePublication(library.loadLocal()!!)
        assertFalse(library.localFile(old.tracks.single()).exists())
    }

    @Test fun changedContentDoesNotOverwritePlayingRevisionAndKeepIdSurvivesPruning() = CollectionFixture().use { server ->
        val library = Library(app)
        server.manifest(listOf("a"), mapOf("a" to a))
        val old = library.refresh(server.base, null, true) {}.manifest.tracks.single()
        server.manifest(listOf("a"), mapOf("a" to b))
        val new = library.refresh(server.base, "a", true) {}.manifest.tracks.single()
        assertArrayEquals(a, library.localFile(old).readBytes())
        assertArrayEquals(b, library.localFile(new).readBytes())
    }

    @Test fun productionOriginAndRedirectTargetsFailClosed() = CollectionFixture().use { server ->
        for (url in listOf("http://html.me.uk/a", "https://127.0.0.1/a", "https://example.org/a", "https://html.me.uk.example.org/a", "https://user@html.me.uk/a")) {
            assertTrue(runCatching { Library.checkUrl(URL(url)) }.exceptionOrNull() is IOException)
        }
        Library.checkUrl(URL(Library.BASE_URL))
        server.routes["/manifest.json"] = CollectionFixture.Response(byteArrayOf(), 302, "https://example.org/manifest.json")
        assertTrue(runCatching { Library(app).refresh(server.base, null, true) {} }.exceptionOrNull() is IOException)
        assertEquals(listOf("/manifest.json"), server.requests.toList())
    }
    @Test fun partialRefreshKeepsOldManifestAndFilesEvenWhenSelectionDropsThem() = CollectionFixture().use { server ->
        val library = Library(app)
        server.manifest(listOf("a"), mapOf("a" to a))
        val old = library.refresh(server.base, null, true) {}.manifest
        server.manifest(listOf("b"), mapOf("b" to b))
        server.routes["/audio/b.opus"] = CollectionFixture.Response(b.copyOf(5))
        assertTrue(runCatching { library.refresh(server.base, null, true) {} }.isFailure)
        assertEquals(old, library.loadLocal())
        assertArrayEquals(a, library.localFile(old.tracks.single()).readBytes())
    }

    @Test fun unsafeIdAndUnknownSelectionFailBeforeAudioRequests() = CollectionFixture().use { server ->
        server.manifest(listOf("../escape"), mapOf("../escape" to a))
        assertTrue(runCatching { Library(app).refresh(server.base, null, true) {} }.isFailure)
        server.manifest(listOf("missing"), mapOf("a" to a))
        assertTrue(runCatching { Library(app).refresh(server.base, null, true) {} }.isFailure)
        assertEquals(listOf("/manifest.json", "/manifest.json"), server.requests.toList())
        assertNull(Library(app).loadLocal())
    }

    @Test fun diskFullMidFilePreservesOldCollectionAndRemovesPart() = CollectionFixture().use { server ->
        server.manifest(listOf("a"), mapOf("a" to a))
        val old = Library(app).refresh(server.base, null, true) {}.manifest
        var written = 0
        val library = Library(app, openOutput = { file ->
            object : java.io.FilterOutputStream(file.outputStream()) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, 1)
                    written++
                    throw IOException("storage full")
                }
            }
        })
        server.manifest(listOf("b"), mapOf("b" to b))
        assertTrue(runCatching { library.refresh(server.base, null, true) {} }.isFailure)
        assertEquals(1, written)
        assertEquals(old, library.loadLocal())
        assertArrayEquals(a, library.localFile(old.tracks.single()).readBytes())
        assertTrue(File(app.filesDir, "collection/audio").listFiles()!!.none { it.name.endsWith(".part") })
    }

}
