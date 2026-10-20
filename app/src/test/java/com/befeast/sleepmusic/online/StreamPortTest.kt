package com.befeast.sleepmusic.online

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.*
import com.befeast.sleepmusic.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamPortTest {
    @Test fun exoPortCarriesStreamUriAndMapsActualPlayerErrorCallback() {
        val player = com.befeast.sleepmusic.playback.testing.FakeMediaPlayer()
        val port = ExoPlayerPort { player }
        var reason: String? = null
        port.setListener(object : PlayerPort.Listener {
            override fun onPlaying() = Unit
            override fun onAdvanced() = Unit
            override fun onEnded() = Unit
            override fun onPausedBySystem() = Unit
            override fun onError(code: String) { reason = code }
        })
        val stream = Resolved("https://media.example/audio", "audio/mp4")
        port.load(QueueItem("yt-fxAmbient01", null, "Title", "Artist", stream, 42_000), null)
        val sessionPlayer = SessionPlayer(player, com.befeast.sleepmusic.playback.testing.RecordingControls())
        val exposed = sessionPlayer.currentMediaItem!!
        assertEquals("dremla-stream", exposed.localConfiguration!!.uri.scheme)
        assertFalse(exposed.toBundle().toString().contains(stream.streamUrl))
        val first = exposed.localConfiguration!!.uri
        port.load(QueueItem("yt-fxAmbient01", null, "Title", "Artist", stream), null)
        assertNotEquals(first, player.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(stream.mimeType, player.currentMediaItem!!.localConfiguration!!.mimeType)
        player.failWith(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals("network", reason)
        port.stopAndClear()
        assertEquals(0, player.mediaItemCount)
    }

    @Test fun resolverHeadersReachEnginePortAndDataSpecAndAreClearedBetweenSessions() {
        var opened: DataSpec? = null
        val factory = DataSource.Factory {
            object : DataSource {
                override fun open(dataSpec: DataSpec): Long { opened = dataSpec; return 0 }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
                override fun getUri() = opened?.uri
                override fun close() = Unit
                override fun addTransferListener(transferListener: TransferListener) = Unit
            }
        }
        val headers = StreamHeaders()
        val stream = Resolved("https://media.example/audio", "audio/mp4", headers = mapOf("Authorization" to "fixture-only", "User-Agent" to "fixture"))
        val player = com.befeast.sleepmusic.playback.testing.FakeMediaPlayer()
        val port = ExoPlayerPort(headers) { player }
        val h = OnlineHarness(resolverOverride = StreamResolver { _, _ -> stream })
        try {
            h.resolve()
            val item = h.port.current!!
            assertEquals(stream.headers, item.stream!!.headers)
            port.load(item, null)
            val ds = headers.factory(factory).createDataSource()
            val spec = DataSpec.Builder().setUri(player.currentMediaItem!!.localConfiguration!!.uri).setPosition(123).setHttpRequestHeaders(mapOf("Accept" to "audio/*")).build()
            ds.open(spec)
            assertEquals(stream.streamUrl, opened!!.uri.toString())
            assertEquals(123L, opened!!.position)
            assertEquals(stream.headers + mapOf("Accept" to "audio/*"), opened!!.httpRequestHeaders)
            port.stopAndClear()
            assertThrows(java.io.IOException::class.java) { ds.open(spec) }
            ds.open(DataSpec.Builder().setUri("file:///fixture.opus").build())
            assertTrue(opened!!.httpRequestHeaders.isEmpty())
        } finally { h.close() }
    }

    @Test fun httpExpiryAndTransportErrorsMapToRetryButOtherErrorsDoNot() {
        for ((status, reason) in mapOf(403 to "network", 410 to "network", 503 to "network", 401 to "login-required", 429 to "login-required", 404 to "unavailable", 302 to "unavailable")) {
            val cause = HttpDataSource.InvalidResponseCodeException(status, "fixture", null, emptyMap(), DataSpec(Uri.parse("https://media.example/audio")), byteArrayOf())
            assertEquals(reason, StreamErrors.reason(PlaybackException("fixture", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        }
        assertEquals("network", StreamErrors.reason(PlaybackException("fixture", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals("unavailable", StreamErrors.reason(PlaybackException("fixture", null, PlaybackException.ERROR_CODE_DECODING_FAILED)))
    }
}
