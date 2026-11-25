package com.befeast.sleepmusic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Contract-shaped manifest served over real HTTP; Robolectric supplies a real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTest {
    private class Resp(val status: Int = 200, val headers: List<String> = emptyList(), val body: ByteArray = ByteArray(0))

    private lateinit var server: ServerSocket
    private lateinit var other: ServerSocket // a second origin, for cross-host redirects
    private val routes = ConcurrentHashMap<String, () -> Resp>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val good = ByteArray(300_000) { (it % 251).toByte() }
    private val tampered = ByteArray(5_000) { 7 }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val manifestJson by lazy {
        """
        {
          "version": 1,
          "generated": "2026-10-17T21:00:00Z",
          "tracks": [
            {"id": "amb-001", "title": "Low Clouds", "artist": "Someone", "category": "ambient",
             "duration_sec": 600, "file": "audio/amb-001.opus", "bytes": ${good.size}, "sha256": "${sha(good)}",
             "preview": "previews/amb-001.mp3", "license": "CC0-1.0", "source_url": "https://example.org/a",
             "loudness": {"i_lufs": -22.5, "lra": 4.0}, "flags": []},
            {"id": "pno-004", "title": "Night Piano", "artist": "Someone Else", "category": "piano",
             "duration_sec": 300, "file": "audio/pno-004.opus", "bytes": ${tampered.size}, "sha256": "${"0".repeat(64)}",
             "preview": "previews/pno-004.mp3", "license": "PDM-1.0", "source_url": "https://example.org/b",
             "loudness": {"i_lufs": -25.0, "lra": 6.1}, "flags": ["quiet-start"]}
          ],
          "selection": ["amb-001"]
        }
        """.trimIndent()
    }

    private fun ok(body: ByteArray, contentType: String? = null) =
        Resp(headers = listOfNotNull(contentType?.let { "Content-Type: $it" }), body = body)

    private fun redirect(code: Int, location: String) =
        Resp(code, listOf("Location: $location"), "moved".toByteArray())

    private fun ServerSocket.base() = "http://127.0.0.1:$localPort"

    /** Minimal HTTP/1.1 server on ServerSocket (com.sun.net.httpserver is not on the Android test classpath). */
    private fun serve(prefix: String): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val path = prefix + (reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use)
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    val r = routes[path]?.invoke() ?: Resp(404)
                    val reason = mapOf(200 to "OK", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other",
                        307 to "Temporary Redirect", 308 to "Permanent Redirect", 404 to "Not Found")[r.status] ?: "X"
                    val head = buildString {
                        append("HTTP/1.1 ${r.status} $reason\r\n")
                        r.headers.forEach { append(it).append("\r\n") }
                        append("Content-Length: ${r.body.size}\r\nConnection: close\r\n\r\n")
                    }
                    val out = c.getOutputStream()
                    out.write(head.toByteArray())
                    out.write(r.body)
                    out.flush()
                }
            }
        }
        return s
    }

    @Before
    fun up() {
        routes["/base/manifest.json"] = { ok(manifestJson.toByteArray()) }
        routes["/base/audio/amb-001.opus"] = { ok(good) }
        routes["/base/audio/pno-004.opus"] = { ok(tampered) }
        server = serve("")
        other = serve("other:")
    }

    @After
    fun down() {
        server.close()
        other.close()
    }

    @Test
    fun downloadsVerifiesShaAndSkipsVerifiedFilesOnRefresh() {
        val lib = Library(RuntimeEnvironment.getApplication())
        val base = "http://127.0.0.1:${server.localPort}/base" // no trailing slash on purpose
        val progress = mutableListOf<Library.Progress>()

        val res = lib.refresh(base, keepId = null) { progress += it }
        assertEquals(1, res.ok)
        assertEquals(listOf("pno-004"), res.failed)
        val (amb, pno) = res.manifest.tracks
        assertTrue(lib.isAvailable(amb))
        assertFalse("sha mismatch must not be playable", lib.isAvailable(pno))
        assertEquals(1f, progress.last().fraction, 0f)

        // Parsed fields + selection, read back from local storage (offline path).
        val local = lib.loadLocal()
        assertNotNull(local)
        assertEquals(listOf("amb-001"), local!!.selection)
        assertEquals(-22.5, local.tracks[0].iLufs!!, 1e-9)
        assertEquals("piano", local.tracks[1].category)
        assertEquals(listOf("quiet-start"), local.tracks[1].flags)

        // Second refresh: the verified file is not downloaded again.
        lib.refresh(base, keepId = null) {}
        assertEquals(1, hits["/base/audio/amb-001.opus"]!!.get())
        assertEquals(2, hits["/base/manifest.json"]!!.get())
    }

    /**
     * Static hosts (pomoi) may redirect and label files octet-stream or text/plain: redirects of every
     * kind are followed (relative, absolute, another origin), the Content-Type is ignored, sha256 still decides.
     */
    @Test
    fun followsRedirectsIgnoresContentTypeAndStillVerifiesSha() {
        val o = other.base()
        routes["/moved/manifest.json"] = { redirect(302, "/base/manifest.json") }
        routes["/moved/audio/amb-001.opus"] = { redirect(301, "../../hop/amb-001.opus") }
        routes["/hop/amb-001.opus"] = { redirect(307, "$o/cdn/amb-001.opus") }
        routes["other:/cdn/amb-001.opus"] = { ok(good, "application/octet-stream") }
        routes["/moved/audio/pno-004.opus"] = { redirect(308, "$o/cdn/pno-004.opus") }
        routes["other:/cdn/pno-004.opus"] = { ok(tampered, "text/plain; charset=utf-8") }
        routes["/base/manifest.json"] = { ok(manifestJson.toByteArray(), "text/plain; charset=utf-8") }

        val lib = Library(RuntimeEnvironment.getApplication())
        val res = lib.refresh("${server.base()}/moved/", keepId = null) {}
        assertEquals(1, res.ok)
        assertEquals("tampered file behind a redirect is still rejected", listOf("pno-004"), res.failed)
        assertTrue(lib.isAvailable(res.manifest.tracks[0]))
        assertFalse(lib.isAvailable(res.manifest.tracks[1]))
        assertEquals(1, hits["other:/cdn/amb-001.opus"]!!.get())
        assertEquals(1, hits["/hop/amb-001.opus"]!!.get())
    }

    @Test
    fun redirectLoopFailsTheRefresh() {
        routes["/loop/manifest.json"] = { redirect(302, "/loop/manifest.json") }
        val lib = Library(RuntimeEnvironment.getApplication())
        val e = runCatching { lib.refresh("${server.base()}/loop/", keepId = null) {} }.exceptionOrNull()
        assertTrue("expected IOException, got $e", e is IOException)
        assertEquals(Library.MAX_REDIRECTS + 1, hits["/loop/manifest.json"]!!.get())
        assertEquals(null, lib.loadLocal())
    }

    @Test
    fun nextHopResolvesAndRefusesDowngrades() {
        // compared as strings: URL.equals would resolve the hosts
        val from = URL("https://html.me.uk/t/dremla/collection/audio/a.opus")
        assertEquals("https://html.me.uk/t/dremla/collection/audio/b.opus", Library.nextHop(from, 302, "b.opus")?.toString())
        assertEquals("https://cdn.example.org/x/a.opus", Library.nextHop(from, 307, "https://cdn.example.org/x/a.opus")?.toString())
        assertEquals("https://html.me.uk/t/x", Library.nextHop(from, 308, " /t/x ")?.toString())
        assertEquals("https://html.me.uk/a", Library.nextHop(URL("http://collection.example:8790/a"), 301, "https://html.me.uk/a")?.toString())
        assertEquals(null, Library.nextHop(from, 404, "b.opus"))
        assertEquals(null, Library.nextHop(from, 304, "b.opus"))
        assertEquals(null, Library.nextHop(from, 302, null))
        assertTrue(runCatching { Library.nextHop(from, 302, "http://html.me.uk/a") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { Library.nextHop(from, 302, "ftp://html.me.uk/a") }.exceptionOrNull() is IOException)
    }

    @Test
    fun nullSelectionParsesAsNull() {
        val m = ManifestParser.parse("""{"version":1,"generated":"x","tracks":[],"selection":null}""")
        assertEquals(null, m.selection)
    }
}
