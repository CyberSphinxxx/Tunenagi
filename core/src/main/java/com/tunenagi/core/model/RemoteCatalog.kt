package com.tunenagi.core.model

/**
 * The remote catalog a user discovers and plays from — owned (TUN-0305).
 *
 * The UI may not name a provider, an HTTP type or a page of foreign JSON. This interface is the
 * whole of what discovery knows: `search`/`trending` hand back rows that have already been mapped
 * into the neutral [ExternalTrack] model, and `persist` turns chosen rows into library tracks the
 * session can play by id. Implementations live below this line, in the provider layer.
 *
 * The offsets are the plain integer pagination every catalog layer keeps, not a provider cursor:
 * a full page means there may be more, so the UI can keep loading without knowing how the provider
 * numbers its pages.
 */
data class RemotePage(
    val items: List<ExternalTrack>,
    /** Present when the provider served a full page; `null` means there is nothing after this one. */
    val nextOffset: Int?,
    /** How many rows the provider actually returned, so "almost empty" stays explainable. */
    val received: Int,
    /** Rows dropped by parsing or normalization, counted rather than silently hidden. */
    val skipped: Int
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Discovery interface.
 *
 * The two reads fail as [ProviderResult] failures, so a screen can render the typed reason instead
 * of guessing from an exception's stack trace. [persist] is a local write, not a network call: it
 * never fails as a provider error, and it does not delete or overwrite user edits — it adds tracks
 * that did not yet exist.
 */
interface RemoteCatalog {

    suspend fun search(
        query: SearchQuery,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): ProviderResult<RemotePage>

    suspend fun trending(
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): ProviderResult<RemotePage>

    /**
     * Puts [items] into the local library so playing them only needs ids, and returns one
     * `TrackId` per item at the same index. A second call for already-persisted rows adds nothing
     * again: identity, not insertion order, is what decides a duplicate.
     */
    suspend fun persist(items: List<ExternalTrack>): List<TrackId>

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}
