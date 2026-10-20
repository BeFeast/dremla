package com.befeast.dremla.resolver

enum class FailureReason(val code: String) {
    LOGIN_REQUIRED("login-required"), UNAVAILABLE("unavailable"),
    AGE_RESTRICTED("age-restricted"), NETWORK("network"), LIVE_OR_PLAYLIST("live-or-playlist");
}

sealed interface Resolution
// bitrate is bits/second; URLs are transient and must never be logged or persisted.
data class Resolved(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int? = null,
    val expiresAtMs: Long? = null,
) : Resolution
data class Failure(val reason: FailureReason) : Resolution

/** Future sign-in supplies headers (including Cookie), scoped to this exact HTTPS URL. */
fun interface SessionProvider {
    fun headersFor(url: String): Map<String, List<String>>
}
object NoSession : SessionProvider {
    override fun headersFor(url: String): Map<String, List<String>> = emptyMap()
}

/** Blocking: a future player must call this off the UI thread. */
fun interface StreamResolver {
    fun resolve(videoId: String, session: SessionProvider): Resolution
}

class FixtureResolver(private val table: Map<String, Resolution>) : StreamResolver {
    override fun resolve(videoId: String, session: SessionProvider): Resolution =
        table[videoId] ?: Failure(FailureReason.UNAVAILABLE)
}
