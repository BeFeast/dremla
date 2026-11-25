package com.befeast.sleepmusic.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.befeast.sleepmusic.online.FailureReason

/** A per-graph, memory-only handoff. Never shared with collection downloads or storage. */
@UnstableApi
class StreamHeaders {
    private data class Entry(val uri: String, val headers: Map<String, String>)
    @Volatile private var entries: Map<String, Entry> = emptyMap()
    private var attempt = 0L
    private var mediaUris: Map<String, String> = emptyMap()
    fun replace(items: List<QueueItem>) {
        val generation = ++attempt
        mediaUris = items.filter { it.stream != null }.associate {
            it.trackId to "dremla-stream:${it.trackId}:$generation"
        }
        entries = items.filter { it.stream != null }.associate {
            mediaUris.getValue(it.trackId) to Entry(it.stream!!.streamUrl, it.stream.headers.toMap())
        }
    }
    fun mediaUri(trackId: String): String = mediaUris.getValue(trackId)
    fun factory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val delegate = upstream.createDataSource()
        object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long {
                if (dataSpec.uri.scheme != "dremla-stream") return delegate.open(dataSpec)
                val entry = entries[dataSpec.uri.toString()] ?: throw java.io.IOException("Expired stream attempt")
                return delegate.open(dataSpec.buildUpon().setUri(entry.uri)
                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders + entry.headers).build())
            }
        }
    }
}

@UnstableApi
object StreamErrors {
    /** Before first audio this becomes resolve(network); during playback it becomes drop. */
    fun reason(error: PlaybackException): String {
        var cause: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return when (cause.responseCode) {
                403, 410 -> FailureReason.NETWORK.code // expired media: re-resolve using the same retry budget
                401, 429 -> FailureReason.LOGIN_REQUIRED.code
                else -> if (cause.responseCode >= 500) FailureReason.NETWORK.code else FailureReason.UNAVAILABLE.code
            }
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> FailureReason.NETWORK.code
            else -> FailureReason.UNAVAILABLE.code
        }
    }
}
