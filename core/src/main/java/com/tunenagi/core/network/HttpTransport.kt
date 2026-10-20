package com.tunenagi.core.network

import com.tunenagi.core.model.ProviderError
import java.time.Duration

/**
 * The one HTTP surface providers use — owned.
 *
 * `SPEC/ARCHITECTURE.md` draws the boundary at the host gateway: a provider maps a foreign payload
 * into domain types, but it does not own sockets, timeouts, redirects or byte budgets. Those are
 * shared policy, they are identical for every provider, and they are the part a provider adapter is
 * forbidden to reach directly (rule B11: no `okhttp3`, `retrofit2` or `java.net` in
 * `core.provider`). This interface is that gateway's contract; [OkHttpTransport] is its one
 * implementation.
 *
 * The surface is deliberately narrow: a GET with query parameters and headers, returning either a
 * bounded response or a typed [ProviderError]. There is no `PUT`/`DELETE` because this app reads
 * public catalogs; there is no raw response body handed out unchecked, because a provider must not
 * be able to turn a 2 GB response into memory.
 */
interface HttpTransport {

    suspend fun get(request: HttpRequest): HttpResult
}

/**
 * What a request needs, without naming an HTTP library.
 *
 * [url] is the full base plus path and [query] is added by the transport with proper encoding, so a
 * provider never has to implement RFC 3986 escaping (and never has to import `java.net` to do it).
 */
data class HttpRequest(
    val url: String,
    val query: List<Pair<String, String>> = emptyList(),
    val headers: Map<String, String> = emptyMap()
)

/** The bounded answer a provider sees. */
sealed interface HttpResult {

    /**
     * A response that arrived. The body is already size-checked; [retryAfter] is parsed from the
     * header so callers do not have to remember that a `429` may answer with seconds *or* an
     * HTTP-date.
     */
    data class Response(
        val status: Int,
        val body: String?,
        val retryAfter: Duration? = null,
        val location: String? = null
    ) : HttpResult

    /** No response: the request never produced one, or produced one this app refuses to accept. */
    data class Failure(val error: ProviderError) : HttpResult
}

/**
 * Timeouts, identity, byte budget and redirect policy in one place.
 *
 * The defaults are the ones `SPEC/PROVIDERS.md` requires of a provider client: finite connect,
 * read and call timeouts; an identifying `User-Agent`; a bounded body; and redirects that are
 * followed only over HTTPS to hosts the caller has explicitly allowed. An empty allowlist means
 * "follow none", which is the safe default: a provider that needs redirects (the stream resolver
 * will) opts in with the host it trusts.
 */
data class HttpTransportConfig(
    val userAgent: String,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(15),
    val callTimeout: Duration = Duration.ofSeconds(20),
    /** One mebibyte: enough for a page of JSON, far too little for a body that would exhaust memory. */
    val maxBodyBytes: Int = 1 shl 20,
    val maxRedirects: Int = 3,
    val allowedRedirectHosts: Set<String> = emptySet(),
    /**
     * Allows plain HTTP to a loopback host, for `MockWebServer` contract tests only.
     *
     * Production never sets it: the flag is checked against a closed loopback list in
     * [OkHttpTransport], so it cannot be used to reach a real remote host over HTTP.
     */
    val allowInsecureLoopback: Boolean = false
) {
    init {
        require(userAgent.isNotBlank()) { "A provider client must identify itself" }
        require(maxBodyBytes > 0) { "A body budget must be positive" }
        require(maxRedirects >= 0) { "Redirect budget cannot be negative" }
        requireDuration(connectTimeout, "connect")
        requireDuration(readTimeout, "read")
        requireDuration(callTimeout, "call")
    }

    private fun requireDuration(value: Duration, name: String) {
        require(!value.isNegative && !value.isZero) { "The $name timeout must be positive" }
    }
}
