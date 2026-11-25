package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.Track

/** Contract-shaped tracks (ids like the published manifest's, no audio). */
object Tracks {
    fun track(id: String, durationSec: Long = 300, iLufs: Double? = null) = Track(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        category = if (id.startsWith("pno")) "piano" else "ambient",
        durationSec = durationSec,
        file = "audio/$id.opus",
        bytes = 1,
        sha256 = "",
        license = "CC0-1.0",
        sourceUrl = "https://example.org/$id",
        iLufs = iLufs,
        flags = emptyList(),
    )

    fun many(n: Int, durationSec: Long = 300): List<Track> =
        (1..n).map { track("amb-%03d".format(it), durationSec) }
}
