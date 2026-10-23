package com.tunenagi.core.provider.audius

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.SourceAvailability

/**
 * Turns the provider DTOs from [AudiusApi] into the provider-neutral [ExternalTrack] (TUN-0302).
 *
 * The parser keeps Audius' own fields and units; this layer decides what they *mean* for the app:
 * the entitlement state, the attribution, and the provider identity the rest of the app stores.
 * Nothing below the adapter sees an Audius field name, a JSON shape or an Audius-only id.
 *
 * The entitlement is encoded in [ExternalTrack.availability] rather than in the row's presence:
 *
 * - A deleted track is refused with [ProviderError.NotFound]. It cannot be matched, resolved or
 *   played, and pretending it is merely "unavailable" would let a stale search result reach the
 *   matcher.
 * - A track the provider does not stream publicly is kept, with
 *   [SourceAvailability.Restricted]. `SPEC/PROVIDERS.md` requires a gated/paid/region-restricted
 *   track to be shown as unplayable rather than hidden or replaced with another copy, so a search
 *   surface can render it disabled and say why. Callers must check
 *   [SourceAvailability.isPlayable] before resolving: a successful normalization is a *mapping*, not
 *   a promise that a stream exists.
 * - A track the provider advertises as streamable is [SourceAvailability.Available]. That is the
 *   provider's claim, not a resolved URL; TUN-0303 still resolves late and can fail.
 *
 * Missing metadata is not invented. A title-less row gets an explicit placeholder, an absent
 * display name keeps the creator's handle as attribution, and absent artwork/duration stay absent.
 */
object AudiusNormalization {

    /** Shown when the provider supplies no usable title; never a fabricated track name. */
    const val UNTITLED_TITLE = "Untitled"

    /** The source the row came from, shown by the UI as attribution. */
    const val PROVIDER_ATTRIBUTION = "Audius"

    /**
     * Normalize one provider track into an [ExternalTrack].
     *
     * The returned track's availability is authoritative for entitlement: [SourceAvailability.Available]
     * means the provider claims a public stream, [SourceAvailability.Restricted] means it exists but
     * is not playable here. An empty provider id is [ProviderError.MalformedPayload] rather than a
     * thrown invariant, so one bad row cannot abort a page.
     */
    fun normalize(track: AudiusTrack): ProviderResult<ExternalTrack> {
        // An empty id cannot be matched or resolved later, and ExternalTrack refuses a blank
        // external id; surface it as a typed failure instead of an exception at the boundary.
        val externalId = track.id.takeIf { it.isNotBlank() }
            ?: return ProviderResult.Failure(ProviderError.MalformedPayload)

        // Deleted takes priority: a deleted track that also lost streaming is gone, not restricted.
        if (track.isDeleted) {
            return ProviderResult.Failure(ProviderError.NotFound)
        }

        return ProviderResult.Success(
            ExternalTrack(
                providerId = ProviderId.Audius,
                externalId = externalId,
                title = track.title?.takeIf { it.isNotBlank() } ?: UNTITLED_TITLE,
                artists = attributionFor(track),
                durationMs = track.durationMs?.takeIf { it > 0 },
                artworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() },
                // The provider's claim. No stream has been resolved yet; TUN-0303 owns that and
                // may still refuse or fail.
                availability = if (track.isStreamable) {
                    SourceAvailability.Available
                } else {
                    SourceAvailability.Restricted
                },
                providerAttribution = PROVIDER_ATTRIBUTION
            )
        )
    }

    /**
     * Normalize a page of provider rows.
     *
     * A page is never collapsed into "no results": rows this layer refuses are dropped and counted,
     * so the caller can distinguish "nothing matched" from "the provider sent rows we cannot use".
     * The count is added to whatever the parser already skipped, while [NormalizedPage.received]
     * keeps the raw row count the pagination logic needs.
     */
    fun normalizePage(page: AudiusPage): NormalizedPage {
        val items = ArrayList<ExternalTrack>(page.tracks.size)
        var rejected = 0
        for (row in page.tracks) {
            when (val result = normalize(row)) {
                is ProviderResult.Success -> items += result.value
                is ProviderResult.Failure -> rejected++
            }
        }
        return NormalizedPage(
            items = items,
            received = page.received,
            skipped = page.skipped + rejected,
            nextOffset = page.nextOffset
        )
    }

    /**
     * The creator credit for a row.
     *
     * The provider's display name is used when it has one. When it has only a handle, the handle is
     * credited as `@handle`: that is the identity Audius actually published, not a display name this
     * app invented. A row with neither stays unattributed rather than being credited to "Unknown".
     */
    private fun attributionFor(track: AudiusTrack): List<String> {
        val name = track.artist?.takeIf { it.isNotBlank() }
        if (name != null) return listOf(name)
        val handle = track.artistHandle?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf("@$handle")
    }
}

/** A page of normalized provider rows, with the diagnostics pagination needs. */
data class NormalizedPage(
    val items: List<ExternalTrack>,
    val received: Int,
    val skipped: Int,
    val nextOffset: Int?
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Rows the provider says are playable; a restricted row is shown but never resolved. */
    val playableCount: Int get() = items.count { it.availability.isPlayable }
}
