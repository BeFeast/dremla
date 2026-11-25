package com.befeast.sleepmusic.playback

import android.content.Context
import com.befeast.sleepmusic.Library
import com.befeast.sleepmusic.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The collection already in app-private storage (`files/collection`, the layout the ported
 * [Library] reads): the local manifest's tracks whose audio file is present. No network. An absent
 * or unreadable manifest loads as an empty collection, so a launcher start plays nothing and the
 * placeholder stays. #4 replaces this source with the download and refresh.
 */
class LocalCollectionSource(
    context: Context,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CollectionSource {
    private val library = Library(context)
    private val mutableCollection = MutableStateFlow<PlayableCollection?>(null)
    override val collection: StateFlow<PlayableCollection?> = mutableCollection.asStateFlow()

    init {
        scope.launch(ioDispatcher) { mutableCollection.value = read() }
    }

    override fun fileFor(track: Track) = library.localFile(track)

    private fun read(): PlayableCollection {
        val manifest = library.loadLocal() ?: return PlayableCollection.EMPTY
        return PlayableCollection(manifest.tracks.filter(library::isAvailable), manifest.selection)
    }
}
