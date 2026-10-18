package com.befeast.sleepmusic.playback

import android.content.Context
import com.befeast.sleepmusic.Library
import com.befeast.sleepmusic.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CollectionPhase { Loading, Downloading, Ready, FirstRunError, RefreshError }
data class CollectionStatus(val phase: CollectionPhase, val progress: Library.Progress? = null)

/** Null collection preserves a launcher request through first-download failure and explicit retry. */
class DownloadCollectionSource internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = Library.BASE_URL,
    private val keepId: () -> String? = { null },
    private val onError: () -> Unit = {},
    private val library: Library = Library(context),
) : CollectionSource {
    private val mutex = Mutex()
    private val mutableCollection = MutableStateFlow<PlayableCollection?>(null)
    override val collection = mutableCollection.asStateFlow()
    private val mutableStatus = MutableStateFlow(CollectionStatus(CollectionPhase.Loading))
    val status = mutableStatus.asStateFlow()

    init {
        scope.launch(io) {
            mutex.withLock {
                val local = library.loadLocal()
                library.pruneBeforePublication(local)
                val tracks = local?.tracks?.filter { it.id in (local.selection ?: emptyList()) &&
                    runCatching { library.isAvailable(it) }.getOrDefault(false) }
                if (!tracks.isNullOrEmpty()) {
                    mutableCollection.value = PlayableCollection(tracks, local.selection)
                    mutableStatus.value = CollectionStatus(CollectionPhase.Ready)
                } else download()
            }
        }
    }

    /** Also the retry action. Serialized with startup; an in-flight refresh is not duplicated. */
    fun refresh() {
        scope.launch(io) {
            if (!mutex.tryLock()) return@launch
            try { download() } finally { mutex.unlock() }
        }
    }

    override fun fileFor(track: Track) = library.localFile(track)

    private fun download() {
        mutableStatus.value = CollectionStatus(CollectionPhase.Downloading)
        try {
            val result = library.refresh(baseUrl, keepId(), selectionOnly = true, playingId = keepId) {
                mutableStatus.value = CollectionStatus(CollectionPhase.Downloading, it)
            }
            mutableCollection.value = PlayableCollection(
                result.manifest.tracks.filter { it.id in result.manifest.selection.orEmpty() && library.isAvailable(it) },
                result.manifest.selection,
            )
            mutableStatus.value = CollectionStatus(CollectionPhase.Ready)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError()
            mutableStatus.value = CollectionStatus(
                if (mutableCollection.value == null) CollectionPhase.FirstRunError else CollectionPhase.RefreshError,
            )
        }
    }
}
