package com.befeast.sleepmusic.online

import com.befeast.sleepmusic.Track
import com.befeast.sleepmusic.playback.CollectionSource
import com.befeast.sleepmusic.playback.PlayableCollection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Offline source is lazy: an online launch cannot refresh, prune or rewrite its collection. */
class SelectableSource(
    private val scope: CoroutineScope,
    private val preference: OnlinePreference,
    private val offline: () -> CollectionSource,
    private val repository: CatalogRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CollectionSource {
    private val mutableCollection = MutableStateFlow<PlayableCollection?>(null)
    override val collection = mutableCollection.asStateFlow()
    override val online: Boolean get() = preference.enabled
    private var catalog: Catalog? = null
    private var job: Job? = null
    private var generation = 0
    init { if (!online) observeOffline() }
    override fun onlineTrack(id: String) = catalog?.tracks?.find { it.track.id == id }
    override fun fileFor(track: Track) = offline().fileFor(track)
    override fun setOnline(enabled: Boolean) {
        if (enabled == online) return
        generation++; job?.cancel(); preference.enabled = enabled
        mutableCollection.value = null
        if (enabled) onLaunch() else observeOffline()
    }
    private fun observeOffline() {
        job = scope.launch { offline().collection.collect { mutableCollection.value = it } }
    }
    override fun onLaunch() {
        if (!online || job?.isActive == true) return
        // A valid local snapshot grants the launcher start immediately. Refresh is for next launch.
        catalog = repository.load()
        val cached = catalog
        mutableCollection.value = cached?.collection()
        val token = ++generation
        job = scope.launch {
            val next = runInterruptible(io) { repository.refresh() }
            if (token != generation || !online) return@launch
            if (cached == null) {
                catalog = next
                // Empty is a completed attempt, not a pending download/autoplay request.
                mutableCollection.value = next?.collection() ?: PlayableCollection.EMPTY
            }
        }
    }
}
interface OnlinePreference { var enabled: Boolean }
