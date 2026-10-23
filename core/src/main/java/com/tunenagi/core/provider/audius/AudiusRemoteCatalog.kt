package com.tunenagi.core.provider.audius

import com.tunenagi.core.library.RemoteLibraryWriter
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.RemoteCatalog
import com.tunenagi.core.model.RemotePage
import com.tunenagi.core.model.SearchQuery
import com.tunenagi.core.model.TrackId

/**
 * The Audius implementation of [RemoteCatalog] — owned (TUN-0305).
 *
 * It is deliberately three existing pieces and no new policy: [AudiusApi] reads and bounds the
 * pages, [AudiusNormalization] maps each row into the neutral model and decides entitlement, and
 * [RemoteLibraryWriter] persists a tapped row into the library. Discovery carries no state of its
 * own, so nothing here can disagree with what a rescan or a playlist later sees in the database.
 */
class AudiusRemoteCatalog(
    private val api: AudiusApi,
    private val writer: RemoteLibraryWriter
) : RemoteCatalog {

    override suspend fun search(
        query: SearchQuery,
        limit: Int,
        offset: Int
    ): ProviderResult<RemotePage> = when (val page = api.search(query.normalized, limit, offset)) {
        is ProviderResult.Failure -> page
        is ProviderResult.Success -> ProviderResult.Success(audiusPage(AudiusNormalization.normalizePage(page.value)))
    }

    override suspend fun trending(limit: Int, offset: Int): ProviderResult<RemotePage> =
        when (val page = api.trending(limit, offset)) {
            is ProviderResult.Failure -> page
            is ProviderResult.Success -> ProviderResult.Success(audiusPage(AudiusNormalization.normalizePage(page.value)))
        }

    override suspend fun persist(items: List<ExternalTrack>): List<TrackId> =
        writer.persist(items)

    private fun audiusPage(normalized: NormalizedPage) = RemotePage(
        items = normalized.items,
        nextOffset = normalized.nextOffset,
        received = normalized.received,
        skipped = normalized.skipped
    )
}
