package com.tunenagi.core.provider.audius

import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.network.HttpRequest
import com.tunenagi.core.network.HttpResult
import com.tunenagi.core.network.HttpTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The documented public read surface of Audius — owned (TUN-0301).
 *
 * Endpoints, parameters and the authentication model were re-checked against the official docs on
 * 2026-10-20, not taken from memory:
 *
 * - Search: `GET /v1/tracks/search` with `query`, `limit`, `offset`
 *   (<https://docs.audius.co/api/>, and the official example at <https://audius.co/skill.md>).
 * - Trending: `GET /v1/tracks/trending` with `limit`, `offset`
 *   (<https://docs.audius.co/sdk/>).
 * - Canonical API root: `https://api.audius.co` (<https://audius.co/agents.md>); the old
 *   `discoveryprovider.audius.co` host is deprecated.
 * - The current documentation's search example sends `x-api-key`, and describes the read-only key
 *   as safe for client use (<https://audius.co/skill.md>). `app_name` remains the documented client
 *   identification that older examples send.
 *
 * What this class deliberately does **not** do: it never invents a key. No credential exists in
 * this repository (ADR-017), so [apiKey] is null by default and the request is sent with the
 * documented identification that is available (`User-Agent` plus `app_name`). If Audius refuses an
 * anonymous read, the caller receives [ProviderError.Unauthorized] and the UI must say so; it must
 * never fall back to an undocumented route or a scraped source (`SPEC/PROVIDERS.md`).
 *
 * Pagination is the documented offset pair, not a cursor: a full page (`items.size == limit`) means
 * there may be more, and a short or empty page means there is not. The [AudiusPage.received] count
 * is the raw row count, so one malformed row cannot stop the caller from advancing past it.
 *
 * This is a DTO boundary, not normalization: the provider's fields keep their names and units
 * (`duration` in seconds, nullable title/genre/artwork). Mapping to `Track`/`TrackSource`, and the
 * decision about streamability, is TUN-0302's job.
 */
class AudiusApi(
    private val transport: HttpTransport,
    private val appName: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val json: Json = DEFAULT_JSON
) {

    init {
        require(baseUrl.startsWith("https://")) { "Audius must be reached over HTTPS" }
        require(appName.isNotBlank()) { "Audius requires an identifying app name" }
    }

    /** Searches tracks by text. A blank query is refused before a socket is opened. */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): ProviderResult<AudiusPage> {
        if (query.isBlank()) {
            return ProviderResult.Failure(ProviderError.Unknown("blank-query"))
        }
        return page(path = SEARCH_PATH, extra = listOf("query" to query), limit = limit, offset = offset)
    }

    /** The current trending list, as documented; no undocumented sort or time parameter is sent. */
    suspend fun trending(
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): ProviderResult<AudiusPage> = page(TRENDING_PATH, extra = emptyList(), limit = limit, offset = offset)

    private suspend fun page(
        path: String,
        extra: List<Pair<String, String>>,
        limit: Int,
        offset: Int
    ): ProviderResult<AudiusPage> {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be 1..$MAX_PAGE_SIZE, but was $limit" }
        require(offset >= 0) { "offset cannot be negative, but was $offset" }
        val query = buildList {
            addAll(extra)
            add("app_name" to appName)
            add("limit" to limit.toString())
            add("offset" to offset.toString())
        }
        val headers = apiKey?.takeIf { it.isNotBlank() }
            ?.let { mapOf(API_KEY_HEADER to it) }
            ?: emptyMap()
        val request = HttpRequest(
            url = baseUrl.trimEnd('/') + path,
            query = query,
            headers = headers
        )
        return when (val result = transport.get(request)) {
            is HttpResult.Failure -> ProviderResult.Failure(result.error)
            is HttpResult.Response -> when {
                result.status !in 200..299 ->
                    ProviderResult.Failure(ProviderError.fromHttpStatus(result.status, result.retryAfter))
                result.body == null -> ProviderResult.Failure(ProviderError.MalformedPayload)
                else -> parse(result.body, limit = limit, offset = offset)
            }
        }
    }

    private fun parse(body: String, limit: Int, offset: Int): ProviderResult<AudiusPage> {
        val envelope = try {
            json.decodeFromString<TracksEnvelope>(body)
        } catch (malformed: IllegalArgumentException) {
            return ProviderResult.Failure(ProviderError.MalformedPayload)
        }
        // The documented shape always carries `data`; `{}` is not the same thing as an empty page,
        // and treating it as one would turn a provider fault into "no results".
        val rows = envelope.data ?: return ProviderResult.Failure(ProviderError.MalformedPayload)
        val tracks = rows.mapNotNull { it.toTrack() }
        return ProviderResult.Success(
            AudiusPage(
                tracks = tracks,
                nextOffset = if (rows.size == limit) offset + rows.size else null,
                received = rows.size,
                skipped = rows.size - tracks.size
            )
        )
    }

    private fun AudiusTrackJson.toTrack(): AudiusTrack? {
        // An empty id cannot be addressed later, so it is counted as skipped rather than kept as a
        // row whose play button could only fail.
        val trackId = id?.takeIf { it.isNotBlank() } ?: return null
        val artworkUrl = artwork?.let { it.size480 ?: it.size150 ?: it.size1000 }
        return AudiusTrack(
            id = trackId,
            title = title?.takeIf { it.isNotBlank() },
            artist = user?.name?.takeIf { it.isNotBlank() },
            artistHandle = user?.handle?.takeIf { it.isNotBlank() },
            durationMs = duration?.takeIf { it > 0 }?.toLong()?.times(1_000L),
            genre = genre?.takeIf { it.isNotBlank() },
            artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
            permalink = permalink?.takeIf { it.isNotBlank() },
            // Absent is not "yes": a track whose payload omits the flag is not advertised as
            // playable. TUN-0302 decides what to do with that, with the UI's honest state.
            isStreamable = isStreamable ?: false,
            isDeleted = isDeleted ?: false
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.audius.co"
        const val SEARCH_PATH = "/v1/tracks/search"
        const val TRENDING_PATH = "/v1/tracks/trending"
        const val DEFAULT_PAGE_SIZE = 20

        /** A documented maximum is not exposed by the API reference; 100 keeps one page bounded. */
        const val MAX_PAGE_SIZE = 100

        const val API_KEY_HEADER = "x-api-key"

        private val DEFAULT_JSON = Json {
            // The provider grows fields without telling clients; a page must not become unreadable
            // because it learned a new one. The shape this app *requires* is declared by the DTOs.
            ignoreUnknownKeys = true
        }
    }
}

/** One page of foreign tracks, with the two facts pagination needs. */
data class AudiusPage(
    val tracks: List<AudiusTrack>,
    val nextOffset: Int?,
    val received: Int = tracks.size,
    val skipped: Int = 0
) {
    val isEmpty: Boolean get() = tracks.isEmpty()
}

/**
 * A track exactly as the provider describes it.
 *
 * Deliberately not a domain `Track`: the title may be absent, the duration is milliseconds only
 * after conversion, and `isStreamable`/`isDeleted` are the provider's claims rather than this app's
 * entitlements. TUN-0302 turns this into the source-neutral model and decides what is playable.
 */
data class AudiusTrack(
    val id: String,
    val title: String?,
    val artist: String?,
    val artistHandle: String?,
    val durationMs: Long?,
    val genre: String?,
    val artworkUrl: String?,
    val permalink: String?,
    val isStreamable: Boolean,
    val isDeleted: Boolean
)

// ---- wire DTOs -------------------------------------------------------------------------------

@Serializable
private data class TracksEnvelope(
    val data: List<AudiusTrackJson>? = null
)

@Serializable
private data class AudiusTrackJson(
    val id: String? = null,
    val title: String? = null,
    val user: AudiusUserJson? = null,
    /** Seconds in the provider's payload; converted once, in [AudiusApi]. */
    val duration: Int? = null,
    val genre: String? = null,
    val artwork: AudiusArtworkJson? = null,
    val permalink: String? = null,
    @SerialName("is_streamable") val isStreamable: Boolean? = null,
    @SerialName("is_delete") val isDeleted: Boolean? = null
)

@Serializable
private data class AudiusUserJson(
    val name: String? = null,
    val handle: String? = null
)

@Serializable
private data class AudiusArtworkJson(
    @SerialName("150x150") val size150: String? = null,
    @SerialName("480x480") val size480: String? = null,
    @SerialName("1000x1000") val size1000: String? = null
)
