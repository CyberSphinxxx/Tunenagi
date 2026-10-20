package com.tunenagi.core.network

import com.tunenagi.core.model.PlayableReference
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.provider.audius.AudiusStreamResolver
import com.tunenagi.core.provider.audius.STREAM_REFERENCE_LIFETIME
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The public stream contract — the endpoint, the entitlement gate and the URL that comes back.
 *
 * The requests are made against a real `MockWebServer` on loopback with the production
 * [OkHttpTransport], so the things a fake cannot show are covered too: the actual request line and
 * query, the identifying header, the transport's refusal to follow a redirect, and its handling of
 * `Retry-After`. The one test-only concession is `allowInsecureLoopback`, which production leaves
 * false; the two tests that must exercise the production policy use a recording transport instead.
 *
 * It lives in the network package, not beside the adapter it exercises, because boundary rule B11
 * forbids `core.provider.*` from importing an HTTP client: `MockWebServer` is exactly such an
 * import, and the checker applies the rule to test sources on purpose.
 *
 * Per ADR-017 no fixture here is captured from the live service: this repository holds no Audius
 * credential, and `SPEC/PROVIDERS.md` forbids provider traffic in CI. The shapes come from the
 * provider's own OpenAPI document (`/tracks/{track_id}/stream`, `no_redirect`, `stream_url_response`),
 * read on 2026-10-21. The rate-respecting live smoke is TUN-0306 and remains outstanding.
 */
class AudiusStreamResolverTest {

    private lateinit var server: MockWebServer
    private var running = false

    /** Fixed so the reference's expiry can be asserted exactly rather than approximately. */
    private val now: Instant = Instant.parse("2026-10-21T06:00:00Z")

    private val track = Track(id = TrackId.random(), title = "Slow Tide")

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        running = true
    }

    @After
    fun stopServer() {
        if (running) server.shutdown()
    }

    private fun config() = HttpTransportConfig(
        userAgent = "Tunenagi/0.1 (stream-contract-test)",
        readTimeout = Duration.ofSeconds(2),
        callTimeout = Duration.ofSeconds(5),
        // Empty on purpose: this resolver asks for JSON, so a redirect is a failure, not a detour.
        allowedRedirectHosts = emptySet(),
        allowInsecureLoopback = true
    )

    private fun resolver(allowInsecureLoopback: Boolean = true) = AudiusStreamResolver(
        transport = OkHttpTransport(config()),
        appName = "tunenagi-tests",
        baseUrl = server.url("/").toString().trimEnd('/'),
        allowInsecureLoopback = allowInsecureLoopback,
        clock = { now }
    )

    private fun source(
        externalId: String = "D7KyD",
        availability: SourceAvailability = SourceAvailability.Available,
        providerId: ProviderId = ProviderId.Audius,
        localUri: String? = null
    ) = TrackSource(
        id = TrackSourceId.random(),
        trackId = track.id,
        providerId = providerId,
        externalId = externalId,
        localUri = localUri,
        availability = availability
    )

    private fun resolved(result: ResolveResult): PlayableReference =
        (result as ResolveResult.Resolved).reference

    private fun failure(result: ResolveResult): ProviderError =
        (result as ResolveResult.Failed).error

    private fun streamUrl(path: String = "/content/D7KyD/stream.mp3") =
        server.url(path).toString()

    @Test
    fun aPublicTrackResolvesTheDocumentedStreamUrlWithAFreshnessBudget() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":"${streamUrl()}"}"""))

        val result = resolver().resolve(track, source())

        val reference = resolved(result)
        assertEquals(streamUrl(), reference.uri)
        // The bytes were never fetched, so no container can be claimed.
        assertNull(reference.mimeType)
        assertEquals(now.plus(STREAM_REFERENCE_LIFETIME), reference.expiresAt)
        assertFalse(reference.isExpiredAt(now.plus(STREAM_REFERENCE_LIFETIME).minusMillis(1)))
        assertTrue(reference.isExpiredAt(now.plus(STREAM_REFERENCE_LIFETIME)))
    }

    @Test
    fun theRequestUsesTheDocumentedPathAndOnlyTheDocumentedParameters() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":"${streamUrl()}"}"""))

        resolver().resolve(track, source(externalId = "Q2mZb"))

        val request = server.takeRequest()
        assertEquals("tunenagi-tests", request.requestUrl!!.queryParameter("app_name"))
        assertEquals("true", request.requestUrl!!.queryParameter("no_redirect"))
        assertEquals("/v1/tracks/Q2mZb/stream", request.requestUrl!!.encodedPath)
        // No signature, no access token, no preview and no second endpoint: a gated track must fail
        // rather than be unlocked with a parameter this app has no right to send.
        assertEquals(setOf("app_name", "no_redirect"), request.requestUrl!!.queryParameterNames)
        assertEquals("Tunenagi/0.1 (stream-contract-test)", request.getHeader("User-Agent"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRestrictedSourceIsAnsweredWithoutOpeningASocket() = runBlocking {
        val result = resolver().resolve(track, source(availability = SourceAvailability.Restricted))

        assertEquals(ProviderError.Restricted("the creator does not allow public streaming"), failure(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aSourceFromAnotherProviderIsRefusedWithoutANetworkCall() = runBlocking {
        val result = resolver().resolve(
            track,
            source(providerId = ProviderId.Local, localUri = "content://media/external/audio/1")
        )

        assertEquals(ProviderError.Unknown("not-an-audius-source"), failure(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aProviderIdThatCouldEscapeThePathIsRefusedWithoutANetworkCall() = runBlocking {
        val result = resolver().resolve(track, source(externalId = "../me"))

        assertEquals(ProviderError.MalformedPayload, failure(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a404IsNotFoundNotAGenericFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no track"}"""))

        val result = resolver().resolve(track, source())

        assertEquals(ProviderError.NotFound, failure(result))
    }

    @Test
    fun a403IsReportedAsRestricted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(ProviderError.Restricted(), failure(resolver().resolve(track, source())))
    }

    @Test
    fun a401IsReportedAsUnauthorized() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ProviderError.Unauthorized, failure(resolver().resolve(track, source())))
    }

    @Test
    fun a429KeepsItsRetryAfterInsteadOfBecomingPermanent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "9")
                .setBody("not json, which must not matter")
        )

        val error = failure(resolver().resolve(track, source()))

        assertEquals(ProviderError.Throttled(Duration.ofSeconds(9)), error)
        assertTrue("a throttled resolve is retryable", error.retryable)
    }

    @Test
    fun aBodyWithoutADataUrlIsAMalformedPayload() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":null}"""))
        assertEquals(ProviderError.MalformedPayload, failure(resolver().resolve(track, source())))

        server.enqueue(MockResponse().setBody("""{"data":"   "}"""))
        assertEquals(ProviderError.MalformedPayload, failure(resolver().resolve(track, source())))

        server.enqueue(MockResponse().setBody("""not-json"""))
        assertEquals(ProviderError.MalformedPayload, failure(resolver().resolve(track, source())))
    }

    @Test
    fun a302IsNotFollowedToWhateverHostItNames() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/content/other.mp3"))
        )
        // Present so a resolver that ignored `no_redirect` would "succeed" on the second hop.
        server.enqueue(MockResponse().setBody("""{"data":"${streamUrl("/content/other.mp3")}"}"""))

        val result = resolver().resolve(track, source())

        assertTrue("expected a typed failure, got $result", result is ResolveResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aDowngradedOrForeignSchemeStreamUrlIsRefusedInProductionPolicy() = runBlocking {
        val bodies = listOf(
            """{"data":"http://content.example/stream.mp3"}""",
            """{"data":"javascript:alert(1)"}""",
            """{"data":"https://127.0.0.1@evil.example/stream.mp3"}"""
        )
        for (body in bodies) {
            // A recording transport lets this test use the production base URL (HTTPS, no loopback
            // exception) while handing back exactly the URL that must be refused.
            val transport = RecordingTransport(HttpResult.Response(200, body))
            val result = AudiusStreamResolver(transport = transport, appName = "tunenagi-tests")
                .resolve(track, source())

            assertEquals(
                "body $body should have been refused",
                ProviderError.Unknown("stream-url-rejected"),
                failure(result)
            )
        }
    }

    /** Answers one canned response and remembers the request, for the no-socket policy tests. */
    private class RecordingTransport(private val result: HttpResult) : HttpTransport {
        var last: HttpRequest? = null

        override suspend fun get(request: HttpRequest): HttpResult {
            last = request
            return result
        }
    }
}
