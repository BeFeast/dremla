package com.befeast.sleepmusic.online

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.AudioTrackType
import java.io.IOException
import java.net.URI

internal data class ExtractedAudio(val type: StreamType, val streams: List<AudioStream>)
internal fun interface Extraction {
    fun fetch(videoId: String, downloader: Downloader): ExtractedAudio
}

class NewPipeResolver internal constructor(private val extraction: Extraction) : StreamResolver {
    constructor() : this(Extraction { videoId, downloader ->
        NewPipe.init(downloader)
        val extractor = ServiceList.YouTube.getStreamExtractor(canonicalUrl(videoId))
        extractor.fetchPage()
        ExtractedAudio(extractor.streamType, extractor.audioStreams)
    })

    override fun resolve(videoId: String, session: SessionProvider): Resolution {
        // This API takes an ID, never a free-form URL or playlist.
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return Failure(FailureReason.LIVE_OR_PLAYLIST)
        // NewPipe owns a process-global downloader. Serialize adapters and discard the session
        // reference afterwards. A shipping integration must own all NewPipe access under this lock.
        try { lock.lockInterruptibly() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Failure(FailureReason.NETWORK)
        }
        return try {
            val audio = extraction.fetch(videoId, UrlConnectionDownloader(session))
            selectAudio(audio).let { result ->
                if (result is Resolved) result.copy(headers = session.headersFor(result.streamUrl)
                .mapValues { (key, values) -> values.joinToString(if (key.equals("Cookie", true)) "; " else ", ") }) else result
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            Failure(reasonFor(error))
        } finally {
            try { NewPipe.init(UrlConnectionDownloader(NoSession)) } finally { lock.unlock() }
        }
    }

    companion object {
        private val lock = java.util.concurrent.locks.ReentrantLock()

        fun canonicalUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

        internal fun reasonFor(error: Throwable): FailureReason {
            // Specific subclasses must precede their ContentNotAvailable/Parsing parents.
            return when (error) {
                is AgeRestrictedContentException -> FailureReason.AGE_RESTRICTED
                is SignInConfirmNotBotException, is ReCaptchaException -> FailureReason.LOGIN_REQUIRED
                // Private/paid/geo restrictions do not imply this account can gain access.
                is ContentNotAvailableException, is ContentNotSupportedException -> FailureReason.UNAVAILABLE
                is HttpFailure -> when (error.status) {
                    401, 429 -> FailureReason.LOGIN_REQUIRED
                    in 300..399, 403, 404, 410 -> FailureReason.UNAVAILABLE
                    else -> FailureReason.NETWORK
                }
                is IOException -> FailureReason.NETWORK
                else -> error.cause?.takeIf { it !== error }?.let(::reasonFor) ?: FailureReason.UNAVAILABLE
            }
        }

        internal fun selectAudio(audio: ExtractedAudio): Resolution {
            if (audio.type !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM)) {
                return Failure(FailureReason.LIVE_OR_PLAYLIST)
            }
            // Minimal direct HTTP audio input: no muxed video, DASH/HLS manifest, or inline data.
            // Prefer original/unspecified audio, then AAC/M4A and bitrate; no loudness claim.
            val stream = audio.streams.filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.format?.mimeType?.startsWith("audio/") == true && secureUrl(it.content)
            }.maxWithOrNull(compareBy<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenBy { it.format?.mimeType == "audio/mp4" }
                .thenBy { it.averageBitrate }) ?: return Failure(FailureReason.UNAVAILABLE)
            val bitrate = stream.bitrate.takeIf { it > 0 }
                ?: stream.averageBitrate.takeIf { it > 0 && it <= Int.MAX_VALUE / 1000 }?.times(1000)
            val seconds = URI(stream.content).rawQuery?.split('&')
                ?.firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()
            val expires = seconds?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000)
            return Resolved(stream.content, stream.format!!.mimeType, bitrate, expires)
        }

        private fun secureUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: Exception) { false }
    }
}
