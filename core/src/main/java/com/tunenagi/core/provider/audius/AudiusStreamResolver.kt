package com.tunenagi.core.provider.audius

import com.tunenagi.core.model.PlayableReference
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.network.HttpRequest
import com.tunenagi.core.network.HttpResult
import com.tunenagi.core.network.HttpTransport
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolves an Audius source to a stream URL, as late as possible — owned (TUN-0303).
 *
 * The endpoint and its parameters were read from the provider's own OpenAPI document
 * (`https://api.audius.co/v1/swagger.yaml`, fetched 2026-10-21), not from an older example:
 *
 * - `GET /tracks/{track_id}/stream` (the server root is `/v1`), documented as "the streamable MP3
 *   file of a track";
 * - `no_redirect=true` is documented as "will not return a 302 and instead will return the stream
 *   url in JSON", and the `200` response schema `stream_url_response` is `{ "data": "<url>" }`;
 * - the security list is `[{}, OAuth2 read]`: an empty requirement first means a public track can be
 *   read without a credential, which is why [resolve] sends no signature of any kind.
 *
 * Four rules are enforced here rather than left to a caller:
 *
 * 1. **Entitlement comes first.** `SPEC/PROVIDERS.md` forbids replacing a gated track with another
 *    copy, and TUN-0302 encodes the provider's own refusal as
 *    [SourceAvailability.Restricted]. A restricted source is answered with
 *    [ProviderError.Restricted] *without opening a socket*; nothing is attempted, retried or
 *    re-routed. Every other state is attempted, because an expired claim is exactly what a late
 *    resolve is for.
 * 2. **The URL is resolved now, never stored.** A stream URL is not durable identity
 *    (`SPEC/DATA_MODEL.md`), so this class has no cache and no way to return an older URL: a media
 *    item carries a source id and is re-resolved on the next playback. The returned
 *    [PlayableReference.expiresAt] is this app's re-resolution budget
 *    ([STREAM_REFERENCE_LIFETIME]), not a lifetime the provider documents — the API reference
 *    promises no expiry, so treating the URL as indefinitely fresh would be the invention here.
 * 3. **A redirect is not followed.** The API is asked for JSON precisely so the client, not an HTTP
 *    redirect chain, decides which host it will hand to the player; a `3xx` therefore surfaces as a
 *    typed failure from the transport instead of silently becoming another URL.
 * 4. **The returned URL is checked, not trusted.** It must be `https` (plain `http` only for the
 *    loopback `MockWebServer` contract tests), free of whitespace/control characters and bounded in
 *    length. Anything else is [ProviderError.Unknown], which carries no URL, no token and no body.
 *
 * Failures keep the taxonomy's distinctions instead of collapsing into "cannot play": `404` is
 * [ProviderError.NotFound], `401` is [ProviderError.Unauthorized], `403` is
 * [ProviderError.Restricted], `429` keeps its `Retry-After` in [ProviderError.Throttled], and a body
 * that is not the documented shape is [ProviderError.MalformedPayload]. Bounded retry for the
 * transient ones is TUN-0304's subject; this class makes exactly one attempt per resolve.
 *
 * [contentType] is deliberately not filled in: the bytes were never fetched, so the container is
 * unknown, and declaring `audio/mpeg` from the endpoint's summary would be a format claim the app
 * cannot back. Media3 sniffs progressive audio from the response itself.
 */
class AudiusStreamResolver(
    private val transport: HttpTransport,
    private val appName: String,
    private val baseUrl: String = AudiusApi.DEFAULT_BASE_URL,
    /**
     * Allows a plain-`http` loopback base and stream URL, for `MockWebServer` contract tests only.
     * Production leaves it false, so a downgraded URL is refused rather than played in clear text.
     */
    private val allowInsecureLoopback: Boolean = false,
    private val clock: () -> Instant = Instant::now,
    private val json: Json = DEFAULT_JSON
) : PlaybackSourceResolver {

    init {
        require(baseUrl.startsWith("https://") || (allowInsecureLoopback && hostOf(baseUrl) in LOOPBACK_HOSTS)) {
            "Audius must be reached over HTTPS"
        }
        require(appName.isNotBlank()) { "Audius requires an identifying app name" }
    }

    override val providerId: ProviderId = ProviderId.Audius

    override suspend fun resolve(track: Track, source: TrackSource): ResolveResult {
        // A resolver answers only for its own provider. Guessing which stream was meant would be the
        // silent substitution `SPEC/PROVIDERS.md` forbids.
        if (source.providerId != providerId) {
            return ResolveResult.Failed(ProviderError.Unknown(NOT_THIS_PROVIDER))
        }
        // The provider already said this track is not streamable here. Do not ask again with a
        // signature, an access token or a different route: that is the bypass the spec names.
        if (source.availability == SourceAvailability.Restricted) {
            return ResolveResult.Failed(ProviderError.Restricted(RESTRICTED_REASON))
        }
        // A provider id is addressed as one path segment. Refusing anything that could escape that
        // segment keeps a malformed row from turning into a request for a different resource.
        if (!EXTERNAL_ID.matches(source.externalId)) {
            return ResolveResult.Failed(ProviderError.MalformedPayload)
        }
        return when (val resolved = streamUrl(source.externalId)) {
            is ProviderResult.Failure -> ResolveResult.Failed(resolved.error)
            is ProviderResult.Success -> ResolveResult.Resolved(
                PlayableReference(
                    uri = resolved.value,
                    mimeType = null,
                    sourceId = source.id,
                    expiresAt = clock().plus(STREAM_REFERENCE_LIFETIME)
                )
            )
        }
    }

    /** One documented request, one attempt, and a URL that has been checked before it is returned. */
    private suspend fun streamUrl(externalId: String): ProviderResult<String> {
        val request = HttpRequest(
            url = baseUrl.trimEnd('/') + AudiusApi.STREAM_PATH_PREFIX + externalId +
                AudiusApi.STREAM_PATH_SUFFIX,
            // `no_redirect=true` is what makes the answer JSON, and `app_name` is the documented
            // client identification. Nothing else is sent: no signature, no preview, no play-count
            // suppression, and no second endpoint to try if this one refuses.
            query = listOf("app_name" to appName, NO_REDIRECT to "true")
        )
        val response = when (val result = transport.get(request)) {
            is HttpResult.Failure -> return ProviderResult.Failure(result.error)
            is HttpResult.Response -> result
        }
        if (response.status !in 200..299) {
            // Observed live by the TUN-0306 smoke on 2026-10-23: an id that is well formed but
            // resolves to no track is answered `400 Bad Request`, not the `404` the provider's
            // OpenAPI document suggests. The request this class builds cannot itself be malformed
            // - the id is regex-checked above and the parameters are fixed - so the only thing a
            // `400` can mean here is "no such track", and that is what the user is told instead
            // of "the provider's answer could not be read". Every other status keeps the
            // taxonomy's own mapping.
            val error = if (response.status == 400) {
                ProviderError.NotFound
            } else {
                ProviderError.fromHttpStatus(response.status, response.retryAfter)
            }
            return ProviderResult.Failure(error)
        }
        val body = response.body ?: return ProviderResult.Failure(ProviderError.MalformedPayload)
        val envelope = try {
            json.decodeFromString<StreamUrlEnvelope>(body)
        } catch (malformed: IllegalArgumentException) {
            return ProviderResult.Failure(ProviderError.MalformedPayload)
        }
        val url = envelope.data?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProviderResult.Failure(ProviderError.MalformedPayload)
        if (!isSafeStreamUrl(url)) {
            return ProviderResult.Failure(ProviderError.Unknown(UNSAFE_STREAM_URL))
        }
        return ProviderResult.Success(url)
    }

    /**
     * HTTPS anywhere, plain HTTP only to a loopback host and only when the test flag is set.
     *
     * The loopback exception mirrors [com.tunenagi.core.network.OkHttpTransport]'s closed list: it
     * names three spellings and nothing else, so it cannot be widened by a hostname that merely
     * contains "localhost".
     */
    private fun isSafeStreamUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        return when (url.substringBefore("://", missingDelimiterValue = "").lowercase()) {
            // HTTPS anywhere, but the authority still has to be a host this app can name: a URL
            // with userinfo (`https://127.0.0.1@evil.example/`) has no trustworthy host at all.
            "https" -> hostOf(url) != null
            "http" -> allowInsecureLoopback && hostOf(url) in LOOPBACK_HOSTS
            else -> false
        }
    }

    private companion object {
        const val NOT_THIS_PROVIDER = "not-an-audius-source"
        const val RESTRICTED_REASON = "the creator does not allow public streaming"
        const val UNSAFE_STREAM_URL = "stream-url-rejected"
        const val NO_REDIRECT = "no_redirect"
        const val MAX_URL_LENGTH = 2048

        /** Audius ids are short base58-ish tokens; a path, query or traversal is not an id. */
        val EXTERNAL_ID = Regex("[A-Za-z0-9_-]{1,64}")

        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

        private val DEFAULT_JSON = Json {
            // The provider grows fields without telling clients; the shape this class requires is
            // `data`, and an extra key must not make a playable track unreadable.
            ignoreUnknownKeys = true
        }
    }
}

/**
 * How long this app treats a resolved stream URL as fresh, and therefore how long a
 * [PlayableReference] built from it is usable.
 *
 * Not a provider fact: the Audius API reference documents no stream-URL lifetime. The budget is
 * short on purpose — the URL is resolved from the source id on every playback anyway, so a caller
 * that finds a reference older than this re-resolves instead of playing a URL of unknown age.
 */
val STREAM_REFERENCE_LIFETIME: Duration = Duration.ofMinutes(10)

/** The documented `stream_url_response` schema: exactly one `data` string. */
@Serializable
private data class StreamUrlEnvelope(val data: String? = null)

/**
 * The host of an absolute URL, without userinfo, port or brackets around an IPv6 literal.
 *
 * Written by hand because `core.provider` may not import `java.net` or OkHttp (boundary rule B11):
 * the gateway owns URL handling, and the one thing a provider needs back is the host it was already
 * given.
 */
private fun hostOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val rest = url.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
    // Userinfo is never expected from this provider, and treating it as part of the host is how a
    // `https://127.0.0.1@evil.example/` URL gets mistaken for loopback.
    if (authority.contains('@')) return null
    return when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        else -> authority.substringBefore(':')
    }.takeIf { it.isNotEmpty() }
}
