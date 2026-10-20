package com.befeast.sleepmusic.online

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class NewPipeTest {
    @Test fun pinnedExceptionTypesMapToContractCodes() {
        val cases = listOf(
            AgeRestrictedContentException("fixture") to FailureReason.AGE_RESTRICTED,
            SignInConfirmNotBotException("fixture") to FailureReason.LOGIN_REQUIRED,
            ReCaptchaException("fixture", "https://example.test/") to FailureReason.LOGIN_REQUIRED,
            PrivateContentException("fixture") to FailureReason.UNAVAILABLE,
            PaidContentException("fixture") to FailureReason.UNAVAILABLE,
            GeographicRestrictionException("fixture") to FailureReason.UNAVAILABLE,
            ContentNotAvailableException("fixture") to FailureReason.UNAVAILABLE,
            ContentNotSupportedException("fixture") to FailureReason.UNAVAILABLE,
            ParsingException("fixture") to FailureReason.UNAVAILABLE,
            IOException("fixture") to FailureReason.NETWORK,
            ParsingException("fixture", IOException("fixture")) to FailureReason.NETWORK,
            HttpFailure(401) to FailureReason.LOGIN_REQUIRED,
            HttpFailure(429) to FailureReason.LOGIN_REQUIRED,
            HttpFailure(403) to FailureReason.UNAVAILABLE,
            HttpFailure(404) to FailureReason.UNAVAILABLE,
            HttpFailure(503) to FailureReason.NETWORK,
        )
        for ((error, reason) in cases) {
            // Inject extraction so even resolve() cannot call a real downloader in this test.
            val resolver = NewPipeResolver(Extraction { _, _ -> throw error })
            assertEquals(Failure(reason), resolver.resolve("fxFail00001", NoSession))
        }
        assertEquals(setOf("login-required", "unavailable", "age-restricted", "network", "live-or-playlist"),
            FailureReason.entries.map { it.code }.toSet())
    }

    private fun audio(format: MediaFormat = MediaFormat.M4A, rate: Int = 128,
        content: String = "https://media.example/audio?expire=1800000000",
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP, isUrl: Boolean = true): AudioStream =
        AudioStream.Builder().setId("fixture").setContent(content, isUrl).setMediaFormat(format)
            .setAverageBitrate(rate).setDeliveryMethod(delivery).build()

    @Test fun constructedAudioSelectsDirectAacAndCarriesMetadata() {
        val resolver = NewPipeResolver(Extraction { _, _ -> ExtractedAudio(StreamType.VIDEO_STREAM,
            listOf(audio(MediaFormat.WEBMA, 160), audio(rate = 64), audio(),
                audio(rate = 999, delivery = DeliveryMethod.DASH), audio(rate = 999, isUrl = false))) })
        val resolved = resolver.resolve("fxAmbient01", NoSession) as Resolved
        assertEquals("audio/mp4", resolved.mimeType)
        assertEquals(128_000, resolved.bitrate)
        assertEquals(1_800_000_000_000L, resolved.expiresAtMs)
    }

    @Test fun missingAudioAndInvalidUrlsAreUnavailable() {
        for (streams in listOf(emptyList(), listOf(audio(content = "http://media.example/audio")),
            listOf(audio(content = "https://user:pass@media.example/audio")) )) {
            assertEquals(Failure(FailureReason.UNAVAILABLE),
                NewPipeResolver.selectAudio(ExtractedAudio(StreamType.VIDEO_STREAM, streams)))
        }
        val resolved = NewPipeResolver.selectAudio(ExtractedAudio(StreamType.AUDIO_STREAM,
            listOf(audio(rate = -1, content = "https://media.example/audio?expire=999999999999999999")))) as Resolved
        assertNull(resolved.bitrate)
        assertNull(resolved.expiresAtMs)
    }

    @Test fun allLiveKindsAndPlaylistInputsAreRejected() {
        for (type in StreamType.values().filter { it !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM) }) {
            assertEquals(Failure(FailureReason.LIVE_OR_PLAYLIST), NewPipeResolver.selectAudio(ExtractedAudio(type, listOf(audio()))))
        }
        val resolver = NewPipeResolver(Extraction { _, _ -> error("Must reject before extraction") })
        for (id in listOf("PLfixture", "https://www.youtube.com/watch?v=fxAmbient01&list=fixture", "")) {
            assertEquals(Failure(FailureReason.LIVE_OR_PLAYLIST), resolver.resolve(id, NoSession))
        }
    }

    @Test fun boundaryContainsLinkageErrorsButNotVirtualMachineFailure() {
        assertEquals(Failure(FailureReason.UNAVAILABLE),
            NewPipeResolver(Extraction { _, _ -> throw NoClassDefFoundError("fixture") }).resolve("fxAmbient01", NoSession))
        assertThrows(OutOfMemoryError::class.java) {
            NewPipeResolver(Extraction { _, _ -> throw OutOfMemoryError("fixture") }).resolve("fxAmbient01", NoSession)
        }
    }

    @Test fun originalTrackWinsBeforeCodecAndBitrate() {
        fun track(type: AudioTrackType, rate: Int) = AudioStream.Builder().setId("fixture")
            .setContent("https://media.example/$rate", true).setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(rate).setAudioTrackType(type).build()
        val original = track(AudioTrackType.ORIGINAL, 64)
        val other = AudioTrackType.values().first { it != AudioTrackType.ORIGINAL }
        val result = NewPipeResolver.selectAudio(ExtractedAudio(StreamType.VIDEO_STREAM,
            listOf(track(other, 256), original))) as Resolved
        assertEquals(original.content, result.streamUrl)
    }

    @Test fun cookieMergeIsCaseInsensitiveAndRedirectIsNotRetriedAsNetwork() {
        val connection = FakeConnection(URL("https://example.test/player"))
        UrlConnectionDownloader(SessionProvider { mapOf("cookie" to listOf("session=fixture")) }) { connection }
            .execute(Request.newBuilder().get(connection.url.toString()).setHeader("Cookie", "request=fixture").build())
        assertEquals("request=fixture; session=fixture", connection.getRequestProperty("Cookie"))
        assertEquals(FailureReason.UNAVAILABLE, NewPipeResolver.reasonFor(HttpFailure(302)))
    }

    // A constructed connection: no socket, DNS, local HTTP server or real YouTube request.
    private class FakeConnection(url: URL, private val status: Int = 200) : HttpURLConnection(url) {
        var disconnected = false
        val body = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getResponseMessage() = "fixture"
        override fun getInputStream() = ByteArrayInputStream("fixture body".toByteArray())
        override fun getOutputStream() = body
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Type" to listOf("text/plain"))
    }

    @Test fun downloaderInjectsScopedHeadersAndWritesBodyWithoutNetwork() {
        val url = "https://example.test/player"
        val connection = FakeConnection(URL(url))
        var requested: String? = null
        val downloader = UrlConnectionDownloader(SessionProvider {
            requested = it
            mapOf("Cookie" to listOf("fixture=value"))
        }) { connection }
        val request = Request.newBuilder().post(url, "payload".toByteArray())
            .setHeader("X-Fixture", "present").build()
        val response = downloader.execute(request)
        assertEquals(url, requested)
        assertEquals("fixture=value", connection.getRequestProperty("Cookie"))
        assertEquals("present", connection.getRequestProperty("X-Fixture"))
        assertEquals("payload", connection.body.toString("UTF-8"))
        assertEquals("fixture body", response.responseBody())
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun downloaderDisconnectsOnFailureAndNeverFollowsRedirects() {
        for (status in listOf(302, 401, 403, 429, 503)) {
            val connection = FakeConnection(URL("https://example.test/player"), status)
            val downloader = UrlConnectionDownloader(NoSession) { connection }
            assertThrows(HttpFailure::class.java) { downloader.execute(Request.newBuilder().get(connection.url.toString()).build()) }
            assertTrue(connection.disconnected)
            assertFalse(connection.instanceFollowRedirects)
        }
    }
}
