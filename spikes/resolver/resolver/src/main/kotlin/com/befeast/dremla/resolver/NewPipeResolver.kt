package com.befeast.dremla.resolver

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.net.URI

internal data class ExtractedAudio(val type: StreamType, val streams: List<AudioStream>)
internal fun interface Extraction {
    fun fetch(videoId: String, downloader: Downloader): ExtractedAudio
}

class NewPipeResolver internal constructor(private val extraction: Extraction) : StreamResolver {
    constructor() : this(Extraction { videoId, downloader ->
        NewPipe.init(downloader)
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        ExtractedAudio(extractor.streamType, extractor.audioStreams)
    })

    override fun resolve(videoId: String, session: SessionProvider): Resolution {
        // This API takes an ID, never a free-form URL or playlist.
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return Failure(FailureReason.LIVE_OR_PLAYLIST)
        // NewPipe owns a process-global downloader. Serialize adapters and discard the session
        // reference afterwards. A shipping integration must own all NewPipe access under this lock.
        return synchronized(lock) {
            try {
                val audio = extraction.fetch(videoId, UrlConnectionDownloader(session))
                selectAudio(audio)
            } catch (error: Exception) {
                Failure(reasonFor(error))
            } finally {
                NewPipe.init(UrlConnectionDownloader(NoSession))
            }
        }
    }

    companion object {
        private val lock = Any()

        internal fun reasonFor(error: Throwable): FailureReason {
            // Specific subclasses must precede their ContentNotAvailable/Parsing parents.
            return when (error) {
                is AgeRestrictedContentException -> FailureReason.AGE_RESTRICTED
                is SignInConfirmNotBotException, is ReCaptchaException -> FailureReason.LOGIN_REQUIRED
                // Private/paid/geo restrictions do not imply this account can gain access.
                is ContentNotAvailableException, is ContentNotSupportedException -> FailureReason.UNAVAILABLE
                is HttpFailure -> when (error.status) {
                    401, 429 -> FailureReason.LOGIN_REQUIRED
                    403, 404, 410 -> FailureReason.UNAVAILABLE
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
            // Prefer AAC/M4A, then the highest reported bitrate; this is not a loudness policy.
            val stream = audio.streams.filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.format?.mimeType?.startsWith("audio/") == true && secureUrl(it.content)
            }.maxWithOrNull(compareBy<AudioStream> { it.format?.mimeType == "audio/mp4" }
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
