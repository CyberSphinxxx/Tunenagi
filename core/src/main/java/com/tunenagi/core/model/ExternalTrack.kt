package com.tunenagi.core.model

/**
 * Provider-neutral search surface — owned.
 *
 * `SPEC/ARCHITECTURE.md` requires provider adapters to map foreign data into stable internal
 * models, so nothing below mentions Audius, JSON field names or HTTP. A provider adapter's whole
 * job is to turn its own response into these types, and the tests for that mapping stop at this
 * boundary rather than asserting on someone else's schema shapes.
 */

/** What a search is asking for. */
enum class TrackSearchFilter { All, Device, Audius }

/**
 * A search request.
 *
 * The query is trimmed and length-bounded here because both the provider's terms and the app's own
 * rate limiting depend on never sending an empty or enormous term.
 */
data class SearchQuery(
    val text: String,
    val filter: TrackSearchFilter = TrackSearchFilter.All
) {
    init {
        require(text.isNotBlank()) { "SearchQuery text must not be blank" }
        require(text.length <= MAX_LENGTH) { "SearchQuery text must be at most $MAX_LENGTH characters" }
    }

    /** The term actually sent to a provider. */
    val normalized: String get() = text.trim().replace(Regex("\\s+"), " ")

    companion object {
        const val MAX_LENGTH = 200
    }
}

/** Opaque provider cursor. Kept opaque so no layer outside the adapter depends on its shape. */
@JvmInline
value class PageKey(val value: String) {
    init {
        require(value.isNotBlank()) { "PageKey must not be blank" }
    }
}

/**
 * One page of results plus what the caller needs to ask for the next one. [total] is nullable
 * because not every provider reports a count, and inventing one would be a lie in the UI.
 */
data class Page<T>(
    val items: List<T>,
    val nextKey: PageKey? = null,
    val total: Int? = null
) {
    init {
        require(total == null || total >= items.size) {
            "Page total $total is smaller than the ${items.size} items it contains"
        }
    }

    val hasMore: Boolean get() = nextKey != null
}

/** A track as a provider describes it, before it is matched to (or becomes) a local track. */
data class ExternalTrack(
    val providerId: ProviderId,
    val externalId: String,
    val title: String,
    val artists: List<String>,
    val albumName: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    /** Reporting identifiers, when the provider gives them. They make a match verifiable. */
    val isrc: String? = null,
    val mbid: String? = null,
    /** What the provider says about playability, when it says anything at all. */
    val availability: SourceAvailability = SourceAvailability.Unknown,
    /** True when the provider served the row itself, which the UI shows as attribution. */
    val providerAttribution: String? = null
) {
    init {
        require(externalId.isNotBlank()) { "ExternalTrack externalId must not be blank" }
        require(title.isNotBlank()) { "ExternalTrack title must not be blank" }
        require(artists.all { it.isNotBlank() }) { "ExternalTrack artists must not contain blanks" }
        require(durationMs == null || durationMs > 0) {
            "ExternalTrack duration must be positive when known, but was $durationMs"
        }
        require(isrc == null || isrc.isNotBlank()) { "ExternalTrack isrc must not be blank when present" }
        require(mbid == null || mbid.isNotBlank()) { "ExternalTrack mbid must not be blank when present" }
    }

    /** The identity used to detect a duplicate of this provider row. */
    val identity: SourceIdentity get() = SourceIdentity(providerId, externalId)

    val primaryArtist: String? get() = artists.firstOrNull()
}
