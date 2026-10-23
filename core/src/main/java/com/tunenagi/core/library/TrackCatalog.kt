package com.tunenagi.core.library

import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface TrackCatalog {
    data class TrackView(
        val trackId: TrackId,
        val title: String,
        val sortTitle: String,
        val durationMs: Long?,
        val artworkRef: String?,
        val source: TrackSourceView,
        val playable: Boolean,
        val artistCount: Int
    ) {
        val providerIdentity: String get() = source.providerIdentity
        val sourceExternalId: String get() = source.externalId
        val sourceVariant: String? get() = source.variant
        val sourceAvailability: SourceAvailability get() = source.availability
    }

    data class TrackSourceView(
        val id: TrackSourceId,
        val providerId: ProviderId,
        val externalId: String,
        val variant: String?,
        val contentType: String?,
        val availability: SourceAvailability,
        val preferred: Boolean,
        val locked: Boolean
    ) {
        val providerIdentity: String get() = providerId.value
    }

    data class PagedTracks(
        val items: List<TrackView>,
        val page: Int,
        val pageSize: Int,
        val total: Int,
        val hasMore: Boolean
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    suspend fun pagedTracks(term: String?, page: Int, pageSize: Int): PagedTracks
    fun observeAll(): Flow<List<TrackView>>
}

@Singleton
class RoomTrackCatalog @Inject constructor(
    private val database: TunenagiDatabase
) : TrackCatalog {
    override suspend fun pagedTracks(term: String?, page: Int, pageSize: Int): TrackCatalog.PagedTracks {
        require(page >= 0) { "page must be >= 0, but was $page" }
        require(pageSize > 0) { "pageSize must be > 0, but was $pageSize" }

        val tracks: List<com.tunenagi.core.database.TrackEntity> = if (term.isNullOrBlank()) {
            database.trackDao().observeAll().first()
        } else {
            database.trackDao().searchByTitle(term)
        }

        val total = tracks.size
        val start = page * pageSize
        val pageTracks: List<com.tunenagi.core.database.TrackEntity> = if (start >= total) {
            emptyList()
        } else {
            tracks.slice(start.coerceAtMost(total - 1)..(start + pageSize - 1).coerceAtMost(total - 1))
        }

        val items = pageTracks.map { entity: com.tunenagi.core.database.TrackEntity ->
            val track = TunenagiMappers.toTrack(entity)
            val rawSources: List<com.tunenagi.core.database.TrackSourceEntity> = database.sourceDao().forTrack(track.id.value)
            val sources: List<TrackSource> = rawSources.map(TunenagiMappers::toTrackSource)
            val best = pickSource(sources, track.id)
            TrackCatalog.TrackView(
                trackId = track.id,
                title = track.title,
                sortTitle = track.sortTitle,
                durationMs = track.durationMs,
                artworkRef = track.artworkRef,
                source = TrackCatalog.TrackSourceView(
                    id = best.source.id,
                    providerId = best.source.providerId,
                    externalId = best.source.externalId,
                    variant = best.source.sourceVariant,
                    contentType = best.source.contentType,
                    availability = best.source.availability,
                    preferred = best.source.preferred,
                    locked = best.source.locked
                ),
                playable = best.playable,
                artistCount = database.trackCreditDao().forTrack(track.id.value).size
            )
        }

        return TrackCatalog.PagedTracks(
            items = items,
            page = page,
            pageSize = pageSize,
            total = total,
            hasMore = (start + pageSize) < total
        )
    }

    override fun observeAll(): Flow<List<TrackCatalog.TrackView>> =
        database.trackDao().observeAll().map { entities: List<com.tunenagi.core.database.TrackEntity> ->
            entities.map { entity: com.tunenagi.core.database.TrackEntity ->
                val track = TunenagiMappers.toTrack(entity)
                val rawSources: List<com.tunenagi.core.database.TrackSourceEntity> = database.sourceDao().forTrack(track.id.value)
                val sources: List<TrackSource> = rawSources.map(TunenagiMappers::toTrackSource)
                val best = pickSource(sources, track.id)
                TrackCatalog.TrackView(
                    trackId = track.id,
                    title = track.title,
                    sortTitle = track.sortTitle,
                    durationMs = track.durationMs,
                    artworkRef = track.artworkRef,
                    source = TrackCatalog.TrackSourceView(
                        id = best.source.id,
                        providerId = best.source.providerId,
                        externalId = best.source.externalId,
                        variant = best.source.sourceVariant,
                        contentType = best.source.contentType,
                        availability = best.source.availability,
                        preferred = best.source.preferred,
                        locked = best.source.locked
                    ),
                    playable = best.playable,
                    artistCount = database.trackCreditDao().forTrack(track.id.value).size
                )
            }
        }

    private data class SelectedSource(
        val source: TrackSource,
        val playable: Boolean
    )

    private fun pickSource(sources: List<TrackSource>, trackId: TrackId): SelectedSource {
        if (sources.isEmpty()) {
            return SelectedSource(
                source = TrackSource(
                    id = TrackSourceId.random(),
                    trackId = trackId,
                    providerId = ProviderId.Local,
                    externalId = "",
                    availability = SourceAvailability.Unavailable
                ),
                playable = false
            )
        }

        val preferred = sources.firstOrNull { it.preferred || it.locked }
            ?: sources.firstOrNull()
            ?: sources.first()

        return SelectedSource(
            source = preferred,
            playable = preferred.availability.isPlayable
        )
    }
}
