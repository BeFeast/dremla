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
 * The collection seam. #3 reads the local app-private collection; #4 replaces the source with the
 * download and refresh. `collection` is null until the first load finishes; a launcher start
 * that arrives earlier waits for it.
 */
interface CollectionSource {
    val collection: StateFlow<PlayableCollection?>
    fun fileFor(track: Track): File
}
