package com.tunenagi.core.network

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.provider.audius.AudiusApi
import com.tunenagi.core.provider.audius.AudiusNormalization
import com.tunenagi.core.provider.audius.AudiusPage
import com.tunenagi.core.provider.audius.AudiusStreamResolver
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * The real, rate-respecting Audius smoke — owned (TUN-0306).
 *
 * Everything below drives the **production** classes the app runs with: the same
 * [OkHttpTransport] with the same identity and budgets (`NetworkModule`), the documented
 * [AudiusApi] discovery client (TUN-0301), the entitlement mapping in [AudiusNormalization]
 * (TUN-0302), and the single-attempt [AudiusStreamResolver] (TUN-0303). Nothing here is a mock:
 * the requests leave this machine and the assertions are about what the live service answered.
 *
 * **CI must not call the provider** (ADR-017, `SPEC/PROVIDERS.md`), so the suite is gated on the
 * `audius.live` system property. Without it, the [Before] assumption skips every test and the run
 * stays offline; `core/build.gradle.kts` forwards the property only when the Gradle invocation
 * carries it. The recorded log lines carry the HTTP status for every request with the URL reduced
 * to scheme + host + path (a stream URL's query can carry a signature and is never printed), plus
 * UTC timestamps and an explicit `device-serial=none` line: there is no Android runtime on this
 * host (ADR-008), so the device half of the task's acceptance stays in
 * `OPERATIONS/DEFERRED_VERIFICATION.md` rather than being pretended here.
 *
 * What this smoke proves, and what it does not: it proves the live API answers this app's exact
 * requests, that a currently permitted public track resolves to a fresh URL, and that the URL
 * serves audio bytes over HTTPS. It does not prove a device decoder, an audible track, or a
 * notification — those are the standing deferral, not a gap this test claims to close.
 */
class AudiusLiveSmokeTest {

    /** The production transport; wrapped only to record status lines for the evidence log. */
    private val delegate = OkHttpTransport(HttpTransportConfig(userAgent = "Tunenagi/1.0 (Android)"))

    private val transport = RecordingTransport(delegate)

    /** Same constructors and identity the Hilt graph installs (AudiusProviderModule). */
    private val api = AudiusApi(transport = transport, appName = "Tunenagi")

    private val resolver = AudiusStreamResolver(transport = transport, appName = "Tunenagi")

    /**
     * A player opens media through its own HTTP stack, following redirects and reading bytes;
     * the resolver deliberately does neither. This client is that second half, bounded the same
     * way (finite timeouts) so a hung content node fails the smoke instead of wedging it.
     */
    private val player = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(15))
        .callTimeout(Duration.ofSeconds(20))
        .build()

    @Before
    fun liveOnlyAndPolite() {
        Assume.assumeTrue(
            "live smoke is opt-in: pass -Daudius.live=true (CI must never call the provider)",
            System.getProperty(PROPERTY) == "true"
        )
        // The documented free-plan budget is 10 req/s. This suite makes at most seven requests,
        // and the pause keeps the bursts of separate tests apart as well.
        Thread.sleep(POLITENESS_MS)
        println(
            "LIVE smoke start utc=${Instant.now()} device-serial=none " +
                "(no Android runtime; ADR-008 deferral in OPERATIONS/DEFERRED_VERIFICATION.md)"
        )
    }

    @Test
    fun liveDiscoveryAnswersSearchAndTrending() {
        try {
            runBlocking {
            val searched = api.search(SEARCH_TERM, limit = 10)
            val page = expectSuccess("search", searched)
            assertTrue("live search returned no rows", page.tracks.isNotEmpty())

            val normalized = AudiusNormalization.normalizePage(page)
            println(
                "LIVE search term='$SEARCH_TERM' received=${normalized.received} " +
                    "skipped=${normalized.skipped} playable=${normalized.playableCount}"
            )
            page.tracks.take(3).forEach { row ->
                println("LIVE row id=${row.id} streamable=${row.isStreamable} title=${row.title}")
            }

            val trending = api.trending(limit = 10)
            val top = expectSuccess("trending", trending)
            assertTrue("live trending returned no rows", top.tracks.isNotEmpty())
                println("LIVE trending received=${top.received} skipped=${top.skipped}")
            }
        } finally {
            // Printed even when an assertion fails, so a red run still records the statuses.
            transport.printLog("discovery")
        }
    }

    @Test
    fun livePermittedTrackResolvesAndServesAudioBytes() {
        try {
            runBlocking {
            val row = playableRow()
            println("LIVE chosen id=${row.externalId} artist=${row.primaryArtist} title=${row.title}")

            val track = Track(
                id = TrackId.random(),
                title = row.title,
                durationMs = row.durationMs,
                artworkUrl = row.artworkUrl
            )
            val source = TrackSource(
                id = TrackSourceId.random(),
                trackId = track.id,
                providerId = row.providerId,
                externalId = row.externalId,
                availability = row.availability
            )

            val resolved = resolver.resolve(track, source)
            assertTrue("resolve failed: $resolved", resolved is ResolveResult.Resolved)
            val reference = (resolved as ResolveResult.Resolved).reference
            assertTrue("resolver must answer https, was ${reference.uri.take(24)}", reference.uri.startsWith("https://"))
            assertTrue("fresh reference must not be born expired", !reference.isExpiredAt(Instant.now()))

            // The player half: open the URL the way Media3 would, reading only the head.
            val call = player.newCall(Request.Builder().url(reference.uri).addHeader("Range", "bytes=0-16383").build())
            call.execute().use { response ->
                // The head read below is bounded by the Range header and the source's own
                // buffering, never by the whole track.
                val body = response.body ?: error("stream answered without a body")
                val head = body.source().use { source ->
                    source.request(8_192L)
                    source.buffer.snapshot().toByteArray()
                }
                println(
                    "LIVE stream status=${response.code} content-type=${response.header("Content-Type")} " +
                        "head-bytes=${head.size} host=${response.request.url.host}"
                )
                assertTrue("stream status was ${response.code}", response.code == 200 || response.code == 206)
                assertTrue(
                    "the player must not be sent to clear text",
                    response.request.url.isHttps
                )
                assertTrue("stream served no bytes", head.isNotEmpty())
                val contentType = response.header("Content-Type").orEmpty()
                assertTrue(
                    "neither Content-Type '$contentType' nor the first bytes look like audio",
                    contentType.startsWith("audio/") || looksLikeAudio(head)
                )
            }
            }
        } finally {
            transport.printLog("stream")
        }
    }

    @Test
    fun liveUnknownIdFailsAsNotFoundAndRestrictedIsRefusedBeforeAnyRequest() {
        try {
            runBlocking {
            val track = Track(id = TrackId.random(), title = "A track that does not exist")
            val ghost = TrackSource(
                id = TrackSourceId.random(),
                trackId = track.id,
                providerId = ProviderId.Audius,
                externalId = GHOST_ID,
                availability = SourceAvailability.Unknown
            )
            val failed = resolver.resolve(track, ghost)
            assertTrue("expected a typed failure, got $failed", failed is ResolveResult.Failed)
            val error = (failed as ResolveResult.Failed).error
            assertTrue("live unknown id must map to NotFound, was $error", error is ProviderError.NotFound)

            // The entitlement refusal is pre-socket by design (TUN-0303); a live run must not
            // quietly turn it into a request that could be answered with *some other* copy.
            val callsBefore = transport.log.size
            val restricted = ghost.copy(availability = SourceAvailability.Restricted)
            val refused = resolver.resolve(track, restricted)
            assertTrue("expected Restricted, got $refused", refused is ResolveResult.Failed)
            assertTrue(
                "expected Restricted, was ${(refused as ResolveResult.Failed).error}",
                refused.error is ProviderError.Restricted
            )
            assertEquals(
                "a restricted source must not open a socket",
                callsBefore,
                transport.log.size
            )
            }
        } finally {
            transport.printLog("negative")
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A currently permitted row, from the same two documented reads the UI uses. */
    private suspend fun playableRow(): ExternalTrack {
        val searched = expectSuccess("search", api.search(SEARCH_TERM, limit = 20))
        val searchRows = AudiusNormalization.normalizePage(searched).items.filter { it.availability.isPlayable }
        if (searchRows.isNotEmpty()) return searchRows.first()
        // A search page can legitimately come back all-restricted. Trending is the documented
        // list the Home screen already reads, so the smoke reads it too rather than loosening
        // the test until an unplayable row counts as playable.
        val trending = expectSuccess("trending", api.trending(limit = 20))
        return AudiusNormalization.normalizePage(trending).items.firstOrNull { it.availability.isPlayable }
            ?: throw AssertionError("neither search nor trending served a currently permitted track")
    }

    private fun expectSuccess(what: String, result: ProviderResult<AudiusPage>): AudiusPage =
        when (result) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw AssertionError("live $what failed: ${result.error}")
        }

    /** ID3 tag, an MPEG frame sync, FLAC or Ogg — the containers this app asks a decoder to open. */
    private fun looksLikeAudio(head: ByteArray): Boolean {
        if (head.size < 4) return false
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return true
        if (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return true
        val magic = head.decodeToString(0, 4)
        return magic == "fLaC" || magic.startsWith("OggS")
    }

    /** Delegates for real, recording scheme + host + path only — never the query string. */
    private class RecordingTransport(private val delegate: HttpTransport) : HttpTransport {
        val log = mutableListOf<String>()

        override suspend fun get(request: HttpRequest): HttpResult {
            val result = delegate.get(request)
            val outcome = when (result) {
                is HttpResult.Response -> result.status.toString()
                is HttpResult.Failure -> "no-response:${result.error::class.simpleName}"
            }
            log += "${request.url.substringBefore('?')} -> $outcome"
            return result
        }

        fun printLog(label: String) = log.forEach { println("LIVE $label $it") }
    }

    private companion object {
        /** The opt-in gate; see the class doc and core/build.gradle.kts. */
        const val PROPERTY = "audius.live"
        const val POLITENESS_MS = 600L

        /** A generic term: the smoke needs *a* currently permitted row, not a specific track. */
        const val SEARCH_TERM = "music"

        /** Matches the resolver's id grammar, therefore reaches the provider and answers 404. */
        const val GHOST_ID = "tun0306nosuchtrackid0000"
    }
}
