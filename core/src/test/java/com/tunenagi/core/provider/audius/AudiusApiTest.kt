package com.tunenagi.core.provider.audius

import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.network.HttpRequest
import com.tunenagi.core.provider.ProviderRetry
import com.tunenagi.core.network.HttpResult
import com.tunenagi.core.network.HttpTransport
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Audius discovery contract — endpoints, identification, pagination and failure mapping.
 *
 * The fixtures are written from the shape the official documentation and a documented example show
 * (`{"data": [...]}` with `id`, `user`, `duration`, `artwork`, `is_streamable`, `is_delete`), checked
 * on 2026-10-20 (see `AudiusApi`'s note and ADR-017). They are **not** captured live: this project
 * holds no Audius credential, and `SPEC/PROVIDERS.md` forbids provider traffic in CI. The real,
 * rate-respecting live smoke is TUN-0306 and remains outstanding under ADR-008.
 *
 * The transport is a recording fake here so the *request* can be asserted exactly; the real HTTP
 * behaviour (identity header, budgets, timeouts, redirects, `Retry-After`) is covered separately by
 * `OkHttpTransportTest` against `MockWebServer`.
 */
class AudiusApiTest {

    private class RecordingTransport(var result: HttpResult) : HttpTransport {
        var last: HttpRequest? = null
        var calls = 0

        override suspend fun get(request: HttpRequest): HttpResult {
            calls++
            last = request
            return result
        }
    }

    /** Answers a sequence, repeating the last result, so a retry can be observed. */
    private class SequencedTransport(vararg results: HttpResult) : HttpTransport {
        private val remaining = ArrayDeque(results.toList())
        var calls = 0

        override suspend fun get(request: HttpRequest): HttpResult {
            calls++
            return if (remaining.size > 1) remaining.removeFirst() else remaining.first()
        }
    }

    /**
     * The real client with a policy that does not wait, so the *decisions* are tested without the
     * wall clock. [ProviderRetryTest] covers the real timing schedule.
     */
    private fun api(
        transport: HttpTransport,
        key: String? = null,
        appName: String = "tunenagi-tests",
        retry: ProviderRetry = ProviderRetry(maxAttempts = 3, sleeper = { }, jitter = { 0 })
    ) = AudiusApi(transport = transport, appName = appName, apiKey = key, retry = retry)

    private fun ok(body: String) = RecordingTransport(HttpResult.Response(200, body))

    private val fullPage = """
        {
          "data": [
            {
              "id": "D7KyD",
              "title": "Slow Tide",
              "user": { "id": "U1", "name": "Kaori Ito", "handle": "kaori" },
              "duration": 190,
              "genre": "Electronic",
              "artwork": {
                "150x150": "https://images.audius.co/150.jpg",
                "480x480": "https://images.audius.co/480.jpg",
                "1000x1000": "https://images.audius.co/1000.jpg"
              },
              "permalink": "/kaori/slow-tide",
              "is_streamable": true,
              "is_delete": false,
              "play_count": 42,
              "unexpected_future_field": { "nested": true }
            },
            {
              "id": "Q2mZb",
              "title": "Harbour Lights",
              "user": { "name": "Nao", "handle": "nao" },
              "duration": 245,
              "artwork": { "150x150": "https://images.audius.co/q150.jpg" },
              "is_streamable": true
            }
          ]
        }
    """.trimIndent()

    // ---- request shape -----------------------------------------------------------------------

    @Test
    fun searchUsesTheDocumentedPathAndSendsIdentificationAndPaging() = runBlocking {
        val transport = ok("""{"data":[]}""")

        api(transport).search(query = "slow tide", limit = 2, offset = 10)

        assertEquals("https://api.audius.co/v1/tracks/search", transport.last!!.url)
        assertEquals(
            listOf(
                "query" to "slow tide",
                "app_name" to "tunenagi-tests",
                "limit" to "2",
                "offset" to "10"
            ),
            transport.last!!.query
        )
        assertTrue("no credential is configured, so none may be sent", transport.last!!.headers.isEmpty())
    }

    @Test
    fun anApiKeyIsSentAsTheDocumentedHeaderOnlyWhenConfigured() = runBlocking {
        val withKey = ok("""{"data":[]}""")
        api(withKey, key = "read-only-key").search("audius")
        assertEquals(mapOf("x-api-key" to "read-only-key"), withKey.last!!.headers)

        val withoutKey = ok("""{"data":[]}""")
        api(withoutKey, key = "  ").search("audius")
        assertTrue("a blank key is not a credential", withoutKey.last!!.headers.isEmpty())
    }

    @Test
    fun trendingUsesItsOwnPathAndSendsNoSearchTerm() = runBlocking {
        val transport = ok("""{"data":[]}""")

        api(transport).trending(limit = 5, offset = 5)

        assertEquals("https://api.audius.co/v1/tracks/trending", transport.last!!.url)
        assertEquals(
            listOf("app_name" to "tunenagi-tests", "limit" to "5", "offset" to "5"),
            transport.last!!.query
        )
    }

    @Test
    fun aBlankQueryIsRefusedBeforeTheNetwork() = runBlocking {
        val transport = ok(fullPage)

        val result = api(transport).search("   ")

        assertEquals(ProviderResult.Failure(ProviderError.Unknown("blank-query")), result)
        assertEquals("a blank query must not become a request", 0, transport.calls)
    }

    // ---- parsing -----------------------------------------------------------------------------

    @Test
    fun aDocumentedTrackIsMappedWithItsUnitsAndAttribution() = runBlocking {
        val result = api(ok(fullPage)).search("slow", limit = 2) as ProviderResult.Success<AudiusPage>

        val first = result.value.tracks.first()
        assertEquals("D7KyD", first.id)
        assertEquals("Slow Tide", first.title)
        assertEquals("Kaori Ito", first.artist)
        assertEquals("kaori", first.artistHandle)
        assertEquals("seconds are converted once, to milliseconds", 190_000L, first.durationMs)
        assertEquals("Electronic", first.genre)
        assertEquals("the 480px artwork is preferred", "https://images.audius.co/480.jpg", first.artworkUrl)
        assertEquals("/kaori/slow-tide", first.permalink)
        assertTrue(first.isStreamable)
        assertFalse(first.isDeleted)
        assertEquals("an unknown future field must not break the page", 2, result.value.tracks.size)
    }

    @Test
    fun absentOptionalFieldsStayAbsentInsteadOfBeingInvented() = runBlocking {
        val body = """{"data":[{"id":"abc","user":{"handle":"someone"}}]}"""

        val track = (api(ok(body)).search("x") as ProviderResult.Success<AudiusPage>).value.tracks.single()

        assertNull(track.title)
        assertNull(track.artist)
        assertEquals("someone", track.artistHandle)
        assertNull(track.durationMs)
        assertNull(track.genre)
        assertNull(track.artworkUrl)
        // Missing is not "yes": a payload without the flag must not advertise a playable track.
        assertFalse(track.isStreamable)
        assertFalse(track.isDeleted)
    }

    @Test
    fun aRowWithoutAnAddressableIdIsCountedRatherThanSilentlyKeptOrLost() = runBlocking {
        val body = """{"data":[{"id":"","title":"No id"},{"id":"ok","title":"Fine"}]}"""

        val page = (api(ok(body)).search("x", limit = 2) as ProviderResult.Success<AudiusPage>).value

        assertEquals(1, page.tracks.size)
        assertEquals("ok", page.tracks.single().id)
        assertEquals(2, page.received)
        assertEquals(1, page.skipped)
        assertEquals("pagination follows the raw rows, not the mapped ones", 2, page.nextOffset)
    }

    // ---- pagination --------------------------------------------------------------------------

    @Test
    fun aFullPageOffersTheNextOffsetAndAShortPageEndsPaging() = runBlocking {
        val full = (api(ok(fullPage)).search("x", limit = 2, offset = 20) as ProviderResult.Success<AudiusPage>).value
        assertEquals(22, full.nextOffset)

        val short = (api(ok(fullPage)).search("x", limit = 5, offset = 20) as ProviderResult.Success<AudiusPage>).value
        assertNull("a page shorter than the limit is the last one", short.nextOffset)

        val empty = (api(ok("""{"data":[]}""")).search("x") as ProviderResult.Success<AudiusPage>).value
        assertTrue(empty.isEmpty)
        assertNull(empty.nextOffset)
    }

    /**
     * A 200 with a body that is not the documented envelope is a provider fault, not "no results".
     * Treating `{}` as an empty page would hide a broken response behind a legitimate empty state.
     */
    @Test
    fun aPayloadWithoutTheDocumentedEnvelopeIsMalformed() = runBlocking {
        listOf("{", "{}", """{"data":null}""", "not json at all").forEach { body ->
            val result = api(ok(body)).search("x")
            assertEquals(
                "body '$body' must be MalformedPayload",
                ProviderResult.Failure(ProviderError.MalformedPayload),
                result
            )
        }
    }

    // ---- error taxonomy ----------------------------------------------------------------------

    @Test
    fun httpFailuresUseTheDocumentedTaxonomy() = runBlocking {
        suspend fun failureOf(status: Int, retryAfter: Duration? = null): ProviderError {
            val transport = RecordingTransport(HttpResult.Response(status, """{"error":"x"}""", retryAfter))
            return (api(transport).search("x") as ProviderResult.Failure).error
        }

        assertEquals(ProviderError.Unauthorized, failureOf(401))
        assertEquals(ProviderError.Restricted(), failureOf(403))
        assertEquals(ProviderError.NotFound, failureOf(404))
        assertEquals(ProviderError.Throttled(Duration.ofSeconds(9)), failureOf(429, Duration.ofSeconds(9)))
        // The taxonomy maps a server fault to Timeout so a bounded retry may help (TUN-0304 owns it).
        assertEquals(ProviderError.Timeout, failureOf(500))
    }

    @Test
    fun aTransportFailureIsPassedThroughUnchanged() = runBlocking {
        val transport = RecordingTransport(HttpResult.Failure(ProviderError.NetworkUnavailable))

        val result = api(transport).trending()

        assertEquals(ProviderResult.Failure(ProviderError.NetworkUnavailable), result)
        assertEquals("the bounded policy really tried, rather than the failure being special-cased", 3, transport.calls)
    }

    @Test
    fun aServerFaultIsRetriedOnceAndTheRetryCanSucceed() = runBlocking {
        val transport = SequencedTransport(
            HttpResult.Response(500, """{"error":"server"}"""),
            HttpResult.Response(200, """{"data":[]}""")
        )

        val result = api(transport).search("slow tide")

        assertTrue("expected the retry to produce an empty page, got $result", result is ProviderResult.Success)
        assertEquals(2, transport.calls)
    }

    @Test
    fun aPermanentRefusalCostsOneRequestNotThree() = runBlocking {
        val transport = SequencedTransport(HttpResult.Response(403, """{"error":"gated"}"""))

        val result = api(transport).search("slow tide")

        assertEquals(ProviderResult.Failure(ProviderError.Restricted()), result)
        assertEquals("403 is permanent: retrying would hammer a provider that already said no", 1, transport.calls)
    }

    @Test
    fun pagingArgumentsAreValidatedAtTheBoundary() = runBlocking {
        val transport = ok("""{"data":[]}""")

        listOf(0, -1, AudiusApi.MAX_PAGE_SIZE + 1).forEach { limit ->
            val thrown = runCatching { api(transport).trending(limit = limit) }.exceptionOrNull()
            assertTrue("limit $limit must be refused, got $thrown", thrown is IllegalArgumentException)
        }
        assertEquals("a refused argument must not hit the network", 0, transport.calls)

        val negativeOffset = runCatching { api(transport).trending(offset = -1) }.exceptionOrNull()
        assertTrue(negativeOffset is IllegalArgumentException)
    }

    @Test
    fun aBaseUrlThatIsNotHttpsIsRefused() {
        val thrown = runCatching {
            AudiusApi(transport = ok("""{"data":[]}"""), appName = "x", baseUrl = "http://api.audius.co")
        }.exceptionOrNull()

        assertTrue("plain HTTP must never be the production base URL", thrown is IllegalArgumentException)
    }
}
