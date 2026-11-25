package com.befeast.sleepmusic.playback.testing

import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.playback.CollectionSource
import com.befeast.sleepmusic.playback.PlayableCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class FakeCollectionSource(initial: PlayableCollection?) : CollectionSource {
    private val flow = MutableStateFlow(initial)
    override val collection: StateFlow<PlayableCollection?> = flow

    override fun fileFor(track: Track): File = File("collection/audio/${track.id}.opus")

    fun emit(collection: PlayableCollection?) {
        flow.value = collection
    }
}
