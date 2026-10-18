package com.befeast.sleepmusic.playback

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter

/** App-private, bounded journal. Never records exception messages, paths, URIs or device details. */
class NightLog(context: Context, private val now: () -> Long = System::currentTimeMillis) : SessionEvents {
    private val file = AtomicFile(File(context.filesDir, "night-log.json"))
    private var nights = read()
    private var current: JSONObject? = null
    private var writeFailed = false

    init {
        // A persisted open night belongs to a previous process, never a resumable session.
        for (i in 0 until nights.length()) {
            val night = nights.getJSONObject(i)
            if (!night.has("end")) {
                night.put("end", now()).put("reason", "ProcessInterrupted")
            }
        }
        if (nights.length() > 0) save()
    }

    @Synchronized
    override fun sessionStarted(sessionId: Int, timerMinutes: Int, startVolume: StartVolumeResult) {
        val night = JSONObject().put("start", now()).put("timerMinutes", timerMinutes).put("events", JSONArray())
        night.put("volume", JSONObject().put("before", startVolume.before).put("after", startVolume.after)
            .put("mutedAfter", startVolume.mutedAfter).put("fixed", startVolume.fixed))
        if (startVolume.error != null) night.getJSONArray("events").put(event("error", "StartVolumeFailed"))
        nights.put(night)
        while (nights.length() > 14) nights.remove(0)
        current = night
        save()
    }

    @Synchronized
    override fun trackStarted(sessionId: Int, trackId: String) = append("track", trackId)

    @Synchronized
    override fun playbackError(sessionId: Int, code: String) {
        // Media3 error names are fixed tokens; never persist arbitrary caller text.
        append("error", if (code.matches(Regex("ERROR_CODE_[A-Z0-9_]{1,80}"))) code else "PlaybackFailed")
    }

    @Synchronized
    fun collectionError() = append("error", "CollectionRefreshFailed")

    @Synchronized
    override fun sessionEnded(sessionId: Int, reason: EndReason) {
        current?.put("end", now())?.put("reason", reason.name)
        save()
        current = null
    }

    private fun event(kind: String, value: String) = JSONObject().put("at", now()).put("kind", kind)
        .put("value", value.take(160).replace(Regex("[\\r\\n\\t]"), " "))

    private fun append(kind: String, value: String) {
        val night = current ?: return
        val events = night.getJSONArray("events")
        if (events.length() < 4096) events.put(event(kind, value)) else night.put("truncated", true)
        save()
    }

    private fun read(): JSONArray = try {
        file.openRead().bufferedReader().use { input ->
            val parsed = JSONArray(input.readText())
            // Corrupt private storage must not turn a launcher start or dumpsys into a crash.
            for (i in 0 until parsed.length()) {
                val n = parsed.getJSONObject(i)
                n.getLong("start")
                n.getInt("timerMinutes")
                val events = n.getJSONArray("events")
                for (j in 0 until events.length()) {
                    val e = events.getJSONObject(j)
                    e.getLong("at"); e.getString("kind"); e.getString("value")
                }
            }
            while (parsed.length() > 14) parsed.remove(0)
            parsed
        }
    } catch (_: Exception) { JSONArray() }

    private fun save() {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(nights.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            writeFailed = false
        } catch (_: Exception) {
            file.failWrite(stream)
            writeFailed = true
        }
    }

    @Synchronized
    fun dump(writer: PrintWriter) {
        writer.println("Dremla night log v1 (last 14 nights; timestamps epoch-ms)")
        writer.println("storage=${if (writeFailed) "error" else "ok"} nights=${nights.length()}")
        for (i in 0 until nights.length()) {
            val n = nights.getJSONObject(i)
            writer.println("night start=${n.getLong("start")} end=${n.opt("end") ?: "open"} reason=${n.optString("reason", "Running")} timerMinutes=${n.getInt("timerMinutes")}")
            writer.println("  volume=${n.optJSONObject("volume")}")
            val events = n.getJSONArray("events")
            for (j in 0 until events.length()) {
                val e = events.getJSONObject(j)
                writer.println("  ${e.getLong("at")} ${e.getString("kind")}=${e.getString("value")}")
            }
            if (n.optBoolean("truncated")) writer.println("  events truncated")
        }
    }
}
