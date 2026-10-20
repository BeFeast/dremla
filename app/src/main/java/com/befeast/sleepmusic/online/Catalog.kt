package com.befeast.sleepmusic.online

import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.playback.PlayableCollection
import kotlin.math.pow

data class CatalogTrack(val track: Track, val videoId: String, val gainDb: Double) {
    val gain: Float get() = 10.0.pow(gainDb / 20.0).toFloat().coerceIn(0f, 1f)
}
data class Catalog(val version: Long, val tracks: List<CatalogTrack>, val selection: List<String>) {
    fun collection() = PlayableCollection(tracks.map { it.track }, selection)
    companion object {
        fun parse(text: String): Catalog {
            val c = obj(StrictJson(text).parse())
            c.fields(setOf("schema", "version", "generated", "tracks", "playlists", "excluded", "active"))
            require(c.string("schema") == "dremla.catalog/1")
            val version = c.integer("version"); require(version >= 1)
            require(c.string("generated").endsWith("Z"))
            val ids = mutableSetOf<String>()
            val tracks = c.array("tracks").map { value ->
                val t = obj(value)
                t.fields(setOf("id", "source", "title", "artist", "category", "durationSec"), setOf("gainDb"))
                val s = obj(t["source"]); s.fields(setOf("kind", "videoId", "url"))
                val video = s.string("videoId")
                require(s.string("kind") == "youtube" && Regex("[A-Za-z0-9_-]{11}").matches(video))
                val id = t.string("id"); require(id == "yt-$video" && ids.add(id))
                require(s.string("url") == NewPipeResolver.canonicalUrl(video))
                require(t.string("title").hasCatalogText() && t.string("artist").hasCatalogText())
                require(t.string("category") in setOf("ambient", "piano"))
                val duration = t.integer("durationSec"); require(duration > 0 && duration <= Long.MAX_VALUE / 1000)
                val gain = if ("gainDb" in t) (t["gainDb"] as? Number ?: error("Gain")).toDouble() else 0.0
                require(gain in -40.0..0.0)
                CatalogTrack(Track(id, t.string("title"), t.string("artist"), t.string("category"), duration,
                    "", 0, "", "", "", null, emptyList()), video, gain)
            }
            val playlists = mutableMapOf<String, List<String>>()
            for (value in c.array("playlists")) {
                val p = obj(value); p.fields(setOf("id", "title", "trackIds"))
                val id = p.string("id")
                require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id) && id !in playlists)
                require(p.string("title").hasCatalogText()); playlists[id] = p.references("trackIds", ids)
            }
            val excluded = c.references("excluded", ids).toSet()
            val selection = (playlists[c.string("active")] ?: error("Active")).filterNot { it in excluded }
            require(selection.isNotEmpty())
            return Catalog(version, tracks, selection)
        }
        // Python str.strip whitespace, including NEXT LINE (U+0085).
        private fun String.hasCatalogText() = any { ch ->
            ch.code !in 0x09..0x0d && ch.code !in 0x1c..0x20 &&
                ch.code !in 0x2000..0x200a && ch.code !in setOf(0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
        }
        private fun obj(value: Any?): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String })
            @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
        }
        private fun Map<String, Any?>.fields(required: Set<String>, optional: Set<String> = emptySet()) {
            require(keys.containsAll(required) && (keys - required - optional).isEmpty())
        }
        private fun Map<String, Any?>.string(key: String) = get(key) as? String ?: error("String")
        private fun Map<String, Any?>.integer(key: String) = get(key) as? Long ?: error("Integer")
        private fun Map<String, Any?>.array(key: String) = get(key) as? List<*> ?: error("Array")
        private fun Map<String, Any?>.references(key: String, known: Set<String>): List<String> {
            val refs = array(key).map { it as? String ?: error("Reference") }
            require(refs.distinct().size == refs.size && refs.all { it in known }); return refs
        }
    }
}
