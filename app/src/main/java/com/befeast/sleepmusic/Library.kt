package com.befeast.sleepmusic

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val category: String,
    val durationSec: Long,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val iLufs: Double?,
    val flags: List<String>,
)

data class Manifest(
    val version: Int,
    val generated: String,
    val tracks: List<Track>,
    val selection: List<String>?,
)

object ManifestParser {
    fun parse(json: String): Manifest {
        val root = JSONObject(json)
        val arr = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val file = o.optString("file")
            if (id.isEmpty() || file.isEmpty()) return@mapNotNull null
            val loud = o.optJSONObject("loudness")
            Track(
                id = id,
                title = o.optString("title", id),
                artist = o.optString("artist", ""),
                category = o.optString("category", ""),
                durationSec = o.optLong("duration_sec", 0),
                file = file,
                bytes = o.optLong("bytes", -1),
                sha256 = o.optString("sha256", "").lowercase(),
                license = o.optString("license", ""),
                sourceUrl = o.optString("source_url", ""),
                iLufs = loud?.let { if (it.has("i_lufs")) it.optDouble("i_lufs") else null },
                flags = o.optJSONArray("flags")?.let { f -> (0 until f.length()).map { f.optString(it) } } ?: emptyList(),
            )
        }
        val sel = if (root.isNull("selection")) null else root.optJSONArray("selection")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        }
        return Manifest(root.optInt("version", 1), root.optString("generated", ""), tracks, sel)
    }
}

/** Collection stored in app-private storage: files/collection/manifest.json and files/collection/audio/ID.opus. */
class Library(context: Context) {
    private val dir = File(context.filesDir, "collection")
    private val audioDir = File(dir, "audio")
    private val manifestFile = File(dir, "manifest.json")

    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val doneBytes: Long,
        val totalBytes: Long,
        val currentTitle: String,
    ) {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class RefreshResult(val manifest: Manifest, val ok: Int, val failed: List<String>)

    fun loadLocal(): Manifest? = try {
        if (manifestFile.exists()) ManifestParser.parse(manifestFile.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "local manifest unreadable", e)
        null
    }

    fun localFile(t: Track): File = File(audioDir, t.id + "." + t.file.substringAfterLast('.', "opus"))

    /** Cheap availability check; sha256 was verified when the file was written. */
    fun isAvailable(t: Track): Boolean {
        val f = localFile(t)
        return f.isFile && (t.bytes <= 0 || f.length() == t.bytes)
    }

    /**
     * Blocking: call from Dispatchers.IO. Downloads <base>/manifest.json and every track file,
     * verifying sha256. Already-verified local files are kept. Throws if the manifest is unreachable.
     */
    fun refresh(baseUrl: String, keepId: String?, onProgress: (Progress) -> Unit): RefreshResult {
        val base = URL(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        val manifestJson = String(fetchBytes(URL(base, "manifest.json")), Charsets.UTF_8)
        val manifest = ManifestParser.parse(manifestJson)
        audioDir.mkdirs()

        val total = manifest.tracks.sumOf { it.bytes.coerceAtLeast(0) }
        var done = 0L
        var ok = 0
        val failed = mutableListOf<String>()
        manifest.tracks.forEachIndexed { i, t ->
            onProgress(Progress(i + 1, manifest.tracks.size, done, total, t.title))
            val dst = localFile(t)
            try {
                if (dst.isFile && (t.sha256.isEmpty() || sha256(dst) == t.sha256)) {
                    done += t.bytes.coerceAtLeast(0)
                    ok++
                    return@forEachIndexed
                }
                val tmp = File(audioDir, dst.name + ".part")
                val startDone = done
                val digest = download(URL(base, t.file), tmp) { n ->
                    onProgress(Progress(i + 1, manifest.tracks.size, startDone + n, total, t.title))
                }
                if (t.sha256.isNotEmpty() && digest != t.sha256) {
                    tmp.delete()
                    throw IOException("sha256 mismatch for ${t.id}: got $digest")
                }
                if (!tmp.renameTo(dst)) throw IOException("rename failed for ${t.id}")
                done = startDone + t.bytes.coerceAtLeast(0)
                ok++
            } catch (e: Exception) {
                Log.w(TAG, "track ${t.id} failed: ${e.message}")
                failed += t.id
                done += t.bytes.coerceAtLeast(0)
            }
        }
        onProgress(Progress(manifest.tracks.size, manifest.tracks.size, total, total, ""))

        // Remove files that are no longer in the collection (never the one playing right now).
        val wanted = manifest.tracks.map { localFile(it).name }.toSet()
        audioDir.listFiles()?.forEach { f ->
            val isKept = keepId != null && f.name.startsWith("$keepId.")
            if (f.name !in wanted && !isKept) f.delete()
        }
        dir.mkdirs()
        val tmpManifest = File(dir, "manifest.json.part")
        tmpManifest.writeText(manifestJson)
        tmpManifest.renameTo(manifestFile)
        return RefreshResult(manifest, ok, failed)
    }

    /**
     * GET that follows redirects by hand ([nextHop]), because HttpURLConnection does not follow a
     * protocol change (http -> https). The Content-Type is never looked at: static hosts send
     * application/octet-stream, text/plain, audio/ogg ...; the manifest is parsed as JSON and every
     * audio file is checked against its sha256.
     */
    private fun open(url: URL): HttpURLConnection {
        var u = url
        repeat(MAX_REDIRECTS + 1) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 200..299) return c
            val location = c.getHeaderField("Location")
            c.disconnect()
            val next = nextHop(u, code, location) ?: throw IOException("HTTP $code for $u")
            Log.i(TAG, "redirect $code: $u -> $next")
            u = next
        }
        throw IOException("more than $MAX_REDIRECTS redirects from $url")
    }

    private fun fetchBytes(url: URL): ByteArray {
        val c = open(url)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Streams to [dst] while hashing; returns lowercase hex sha256. */
    private fun download(url: URL, dst: File, onBytes: (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(url)
        try {
            c.inputStream.use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n = 0L
                    var lastReport = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        md.update(buf, 0, r)
                        n += r
                        if (n - lastReport > 256 * 1024) {
                            lastReport = n
                            onBytes(n)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return md.digest().toHex()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val TAG = "SleepMusic"
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * Where a redirect points: [location] (absolute or relative) resolved against [from], or null if
         * [code] is not a redirect or there is no usable Location. http and https only; http -> https is
         * followed, https -> http is refused.
         */
        fun nextHop(from: URL, code: Int, location: String?): URL? {
            if (code !in REDIRECT_CODES || location.isNullOrBlank()) return null
            val next = try {
                URL(from, location.trim())
            } catch (e: java.net.MalformedURLException) {
                throw IOException("bad redirect Location '$location' from $from", e)
            }
            if (next.protocol != "http" && next.protocol != "https") {
                throw IOException("redirect to ${next.protocol} refused: $from -> $next")
            }
            if (from.protocol == "https" && next.protocol == "http") {
                throw IOException("https -> http redirect refused: $from -> $next")
            }
            return next
        }
    }
}
