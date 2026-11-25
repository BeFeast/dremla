package com.befeast.dremla.resolver

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.zip.GZIPInputStream

internal class HttpFailure(val status: Int) : IOException("Resolver HTTP failure")

/** No shared cookie store, logging, disk cache or implicit redirect credential forwarding. */
internal class UrlConnectionDownloader(
    private val session: SessionProvider,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        require(url.protocol == "https" && url.userInfo == null) { "Resolver requires HTTPS" }
        val connection = open(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.requestMethod = request.httpMethod()
            val headers = TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
            headers["Accept-Encoding"] = listOf("gzip")
            headers.putAll(request.headers())
            headers.putAll(session.headersFor(url.toString()))
            headers.forEach { (name, values) -> values.forEach { connection.addRequestProperty(name, it) } }
            request.dataToSend()?.let { bytes ->
                connection.doOutput = true
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // Deliberately fail closed on redirects; production redirect policy is a follow-up.
            if (status !in 200..299) throw HttpFailure(status)
            val body = if (request.httpMethod() == "HEAD") "" else {
                val input = connection.inputStream
                val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(input) else input
                decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val responseHeaders = connection.headerFields.entries.filter { it.key != null }
                .associate { it.key!! to it.value }
            return Response(status, connection.responseMessage.orEmpty(), responseHeaders, body, url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
