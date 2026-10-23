package com.befeast.sleepmusic.playback

import android.content.Context
import com.befeast.sleepmusic.Library
import com.befeast.sleepmusic.Manifest
import com.befeast.sleepmusic.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** Pauses before the automatic retries after a failed download (#83); empty means no automatic retry. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    /** False while the listener plays the online catalog: a scheduled retry then downloads nothing. */
    private val active: () -> Boolean = { true },
) : CollectionSource {
    private val mutex = Mutex()
    /** The published collection is the verified part of an unfinished first download (#83). */
    private var partial = false
    /** Bumped by every download; a scheduled retry runs only if no download started since (guarded by [mutex]). */
    private var generation = 0
    private val mutableCollection = MutableStateFlow<PlayableCollection?>(null)
    override val collection = mutableCollection.asStateFlow()
    private val mutableStatus = MutableStateFlow(CollectionStatus(CollectionPhase.Loading))
    val status = mutableStatus.asStateFlow()

    init {
        scope.launch(io) {
            mutex.withLock {
                val local = library.loadLocal()
                library.pruneBeforePublication(local)
                if (publish(local)) {
                    mutableStatus.value = CollectionStatus(CollectionPhase.Ready)
                } else {
                    // An unfinished first download plays its verified tracks while the rest is fetched (#83).
                    if (local == null && publish(library.loadPending())) partial = true
                    download()
                }
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

    /** Publishes the manifest's selected tracks that are on disk; false when there are none. */
    private fun publish(manifest: Manifest?): Boolean {
        val tracks = manifest?.tracks?.filter { it.id in manifest.selection.orEmpty() &&
            runCatching { library.isAvailable(it) }.getOrDefault(false) }
        if (tracks.isNullOrEmpty()) return false
        mutableCollection.value = PlayableCollection(tracks, manifest.selection)
        return true
    }

    /**
     * Under [mutex]. A first download publishes each verified track at once, so a launcher start plays
     * while the rest downloads; a refresh keeps the old collection until the new one is complete (#83).
     * A failure is reported at once and retried after the next pause in [retryDelaysMs].
     */
    private fun download(retry: Int = 0) {
        val run = ++generation
        val firstRun = mutableCollection.value == null || partial
        mutableStatus.value = CollectionStatus(CollectionPhase.Downloading)
        try {
            val result = library.refresh(baseUrl, keepId(), selectionOnly = true, playingId = keepId,
                onVerified = { if (firstRun && publish(it)) partial = true }) {
                mutableStatus.value = CollectionStatus(CollectionPhase.Downloading, it)
            }
            publish(result.manifest)
            partial = false
            mutableStatus.value = CollectionStatus(CollectionPhase.Ready)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError()
            mutableStatus.value = CollectionStatus(
                if (mutableCollection.value == null) CollectionPhase.FirstRunError else CollectionPhase.RefreshError,
            )
            val pause = retryDelaysMs.getOrNull(retry) ?: return
            scope.launch(io) {
                delay(pause)
                if (!mutex.tryLock()) return@launch
                try { if (generation == run && active()) download(retry + 1) } finally { mutex.unlock() }
            }
        }
    }

    companion object {
        /**
         * Five automatic retries over about 8.7 minutes after the first failure; a first download that is
         * still unfinished then resumes at the next process start, a refresh at the menu's refresh.
         */
        val RETRY_DELAYS_MS = listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}
