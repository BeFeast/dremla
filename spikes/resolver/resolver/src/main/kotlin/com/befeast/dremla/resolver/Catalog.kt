package com.befeast.dremla.resolver

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Catalog(val version: Long, val selection: List<String>) {
    companion object {
        fun parse(text: String): Catalog {
            val tokener = JSONTokener(text)
            val c = readValue(tokener) as? JSONObject ?: error("Catalog must be an object")
            require(tokener.nextClean() == '\u0000') { "Trailing JSON" }
            c.fields(setOf("schema", "version", "generated", "tracks", "playlists", "excluded", "active"))
            require(c.string("schema") == "dremla.catalog/1")
            val version = c.integer("version"); require(version >= 1)
            require(c.string("generated").endsWith("Z"))
            val ids = mutableSetOf<String>()
            for (value in c.array("tracks")) {
                val t = value as? JSONObject ?: error("Track must be an object")
                t.fields(setOf("id", "source", "title", "artist", "category", "durationSec"), setOf("gainDb"))
                val s = t.get("source") as? JSONObject ?: error("Source must be an object")
                s.fields(setOf("kind", "videoId", "url"))
                val video = s.string("videoId")
                require(s.string("kind") == "youtube" && Regex("[A-Za-z0-9_-]{11}").matches(video))
                require(t.string("id") == "yt-$video" && ids.add(t.string("id")))
                require(s.string("url") == "https://www.youtube.com/watch?v=$video")
                require(t.string("title").isNotBlank() && t.string("artist").isNotBlank())
                require(t.string("category") in setOf("ambient", "piano"))
                require(t.integer("durationSec") > 0)
                if (t.has("gainDb")) {
                    val gain = t.get("gainDb") as? Number ?: error("gainDb must be numeric")
                    require(gain.toDouble() in -40.0..0.0)
                }
            }
            val playlists = mutableMapOf<String, List<String>>()
            for (value in c.array("playlists")) {
                val p = value as? JSONObject ?: error("Playlist must be an object")
                p.fields(setOf("id", "title", "trackIds"))
                val id = p.string("id")
                require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id) && id !in playlists)
                require(p.string("title").isNotBlank())
                playlists[id] = p.references("trackIds", ids)
            }
            val excluded = c.references("excluded", ids).toSet()
            val selection = (playlists[c.string("active")] ?: error("Unknown active playlist"))
                .filterNot { it in excluded }
            require(selection.isNotEmpty())
            return Catalog(version, selection)
        }

        // JSONObject on Android replaces duplicate keys, unlike the reference checker.
        // Build objects explicitly so host tests and Android both reject duplicates.
        private fun readValue(t: JSONTokener): Any {
            return when (val first = t.nextClean()) {
                '{' -> {
                    val obj = JSONObject()
                    if (t.nextClean() == '}') return obj
                    t.back()
                    while (true) {
                        require(t.nextClean() == '"') { "Object key must be quoted" }
                        val key = t.nextString('"')
                        require(!obj.has(key)) { "Duplicate JSON key" }
                        require(t.nextClean() == ':')
                        obj.put(key, readValue(t))
                        when (t.nextClean()) { '}' -> return obj; ',' -> Unit; else -> error("Object delimiter") }
                    }
                    @Suppress("UNREACHABLE_CODE") obj
                }
                '[' -> {
                    val array = JSONArray()
                    if (t.nextClean() == ']') return array
                    t.back()
                    while (true) {
                        array.put(readValue(t))
                        when (t.nextClean()) { ']' -> return array; ',' -> Unit; else -> error("Array delimiter") }
                    }
                    @Suppress("UNREACHABLE_CODE") array
                }
                '"' -> t.nextString('"')
                else -> {
                    require(first in "-0123456789tfn") { "JSON value" }
                    t.back()
                    t.nextValue().also { require(it !is String) { "Unquoted string" } }
                }
            }
        }

        private fun JSONObject.fields(required: Set<String>, optional: Set<String> = emptySet()) {
            val keys = keys().asSequence().toSet()
            require(keys.containsAll(required) && (keys - required - optional).isEmpty()) { "Unexpected fields" }
        }
        private fun JSONObject.string(key: String): String = get(key) as? String ?: error("Expected string")
        private fun JSONObject.integer(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long) { "Expected integer" }
            return (value as Number).toLong()
        }
        private fun JSONObject.array(key: String): List<Any> {
            val value = get(key) as? JSONArray ?: error("Expected array")
            return (0 until value.length()).map(value::get)
        }
        private fun JSONObject.references(key: String, known: Set<String>): List<String> {
            val refs = array(key).map { it as? String ?: error("Expected track id") }
            require(refs.distinct().size == refs.size && refs.all { it in known })
            return refs
        }
    }
}
