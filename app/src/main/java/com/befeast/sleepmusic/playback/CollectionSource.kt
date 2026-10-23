package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.Track
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** The tracks that can be played right now: files present on the device, plus the published selection. */
data class PlayableCollection(val tracks: List<Track>, val selection: List<String>?) {
    private val byId: Map<String, Track> = tracks.associateBy { it.id }

    val ids: List<String> get() = tracks.map { it.id }

    fun track(id: String): Track? = byId[id]

    companion object {
        val EMPTY = PlayableCollection(emptyList(), null)
    }
}

/**
 * The collection seam. Production uses DownloadCollectionSource for offline load and refresh.
 * `collection` is null until local load or the first verified track of the first download (#83); a
 * launcher start that arrives earlier waits for it, including through first-run failure and retry.
 */
interface CollectionSource {
    val online: Boolean get() = false
    fun onlineTrack(id: String): com.befeast.sleepmusic.online.CatalogTrack? = null
    fun setOnline(enabled: Boolean) {}
    fun onLaunch() {}
    val collection: StateFlow<PlayableCollection?>
    fun fileFor(track: Track): File
}
