package com.tunenagi.core.database

import com.tunenagi.core.model.Album
import com.tunenagi.core.model.AlbumId
import com.tunenagi.core.model.Artist
import com.tunenagi.core.model.ArtistId
import com.tunenagi.core.model.ArtistRole
import com.tunenagi.core.model.Favorite
import com.tunenagi.core.model.LyricsRecord
import com.tunenagi.core.model.MetadataMatch
import com.tunenagi.core.model.Playlist
import com.tunenagi.core.model.PlaylistEntry
import com.tunenagi.core.model.PlaylistEntryId
import com.tunenagi.core.model.PlaylistId
import com.tunenagi.core.model.PluginInstallation
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackArtist
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant

/**
 * Entity <-> domain mapping — owned.
 *
 * Every read calls a parsing constructor or a `fromPersisted`, so a row that has been corrupted
 * (a hand-edited database, a migration bug a future session introduces) raises instead of becoming
 * an object that silently claims to be something else. This is the layer `SKILLS/migration.md`
 * points at when it says a migration test must prove real user data survives: the assertion is made
 * against these models, not against raw columns.
 */
object TunenagiMappers {

    private fun Instant.toEpoch(): Long = toEpochMilli()

    private fun Long?.toInstant(): Instant? = this?.let(Instant::ofEpochMilli)

    private fun encodeDomains(domains: List<String>): String =
        domains.joinToString(separator = ",") { it.trim() }

    private fun decodeDomains(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // ---- artists, albums, tracks -------------------------------------------------------------

    fun toArtist(entity: ArtistEntity) = Artist(
        id = ArtistId.parse(entity.id),
        name = entity.name,
        sortName = entity.sortName,
        mbid = entity.mbid
    )

    fun toArtistEntity(artist: Artist) = ArtistEntity(
        id = artist.id.value,
        name = artist.name,
        sortName = artist.sortName,
        mbid = artist.mbid
    )

    fun toAlbum(entity: AlbumEntity) = Album(
        id = AlbumId.parse(entity.id),
        title = entity.title,
        sortTitle = entity.sortTitle,
        albumArtistId = entity.albumArtistId?.let(ArtistId::parse),
        year = entity.year,
        mbid = entity.mbid,
        artworkRef = entity.artworkRef
    )

    fun toAlbumEntity(album: Album) = AlbumEntity(
        id = album.id.value,
        title = album.title,
        sortTitle = album.sortTitle,
        albumArtistId = album.albumArtistId?.value,
        year = album.year,
        mbid = album.mbid,
        artworkRef = album.artworkRef
    )

    fun toTrack(entity: TrackEntity) = Track(
        id = TrackId.parse(entity.id),
        title = entity.title,
        sortTitle = entity.sortTitle,
        durationMs = entity.durationMs,
        albumId = entity.albumId?.let(AlbumId::parse),
        isrc = entity.isrc,
        mbid = entity.mbid,
        artworkRef = entity.artworkRef
    )

    fun toTrackEntity(track: Track, createdAt: Instant, updatedAt: Instant, tombstone: Boolean = false) =
        TrackEntity(
            id = track.id.value,
            title = track.title,
            sortTitle = track.sortTitle,
            durationMs = track.durationMs,
            albumId = track.albumId?.value,
            isrc = track.isrc,
            mbid = track.mbid,
            artworkRef = track.artworkRef,
            createdAt = createdAt.toEpoch(),
            updatedAt = updatedAt.toEpoch(),
            deletedTombstone = tombstone
        )

    fun toTrackArtist(entity: TrackArtistEntity) = TrackArtist(
        trackId = TrackId.parse(entity.trackId),
        artistId = ArtistId.parse(entity.artistId),
        order = entity.ord,
        role = ArtistRole.fromPersisted(entity.role)
    )

    fun toTrackArtistEntity(credit: TrackArtist) = TrackArtistEntity(
        trackId = credit.trackId.value,
        artistId = credit.artistId.value,
        role = credit.role.name,
        ord = credit.order
    )

    // ---- sources -----------------------------------------------------------------------------

    fun toTrackSource(entity: TrackSourceEntity) = TrackSource(
        id = TrackSourceId.parse(entity.id),
        trackId = TrackId.parse(entity.trackId),
        providerId = ProviderId(entity.providerId),
        externalId = entity.externalId,
        localUri = entity.localUri,
        sourceVariant = entity.sourceVariant.ifEmpty { null },
        contentType = entity.contentType,
        availability = SourceAvailability.fromPersisted(entity.availability),
        lastCheckedAt = entity.lastCheckedAt.toInstant(),
        preferred = entity.preferred,
        locked = entity.locked
    )

    fun toTrackSourceEntity(source: TrackSource) = TrackSourceEntity(
        id = source.id.value,
        trackId = source.trackId.value,
        providerId = source.providerId.value,
        externalId = source.externalId,
        localUri = source.localUri,
        // The empty string is the stored form of "no variant" — see TrackSourceEntity.
        sourceVariant = source.sourceVariant.orEmpty(),
        contentType = source.contentType,
        availability = source.availability.name,
        lastCheckedAt = source.lastCheckedAt?.toEpoch(),
        preferred = source.preferred,
        locked = source.locked
    )

    // ---- playlists, favourites, lyrics, plugins, matches -------------------------------------

    fun toPlaylist(entity: PlaylistEntity) = Playlist(
        id = PlaylistId.parse(entity.id),
        name = entity.name,
        description = entity.description,
        createdAt = Instant.ofEpochMilli(entity.createdAt),
        updatedAt = Instant.ofEpochMilli(entity.updatedAt),
        isSystem = entity.isSystem
    )

    fun toPlaylistEntity(playlist: Playlist) = PlaylistEntity(
        id = playlist.id.value,
        name = playlist.name,
        description = playlist.description,
        createdAt = playlist.createdAt.toEpoch(),
        updatedAt = playlist.updatedAt.toEpoch(),
        isSystem = playlist.isSystem
    )

    fun toPlaylistEntry(entity: PlaylistEntryEntity) = PlaylistEntry(
        id = PlaylistEntryId.parse(entity.id),
        playlistId = PlaylistId.parse(entity.playlistId),
        trackId = TrackId.parse(entity.trackId),
        position = entity.position,
        addedAt = Instant.ofEpochMilli(entity.addedAt),
        sourceLockId = entity.sourceLockId?.let(TrackSourceId::parse)
    )

    fun toPlaylistEntryEntity(entry: PlaylistEntry) = PlaylistEntryEntity(
        id = entry.id.value,
        playlistId = entry.playlistId.value,
        trackId = entry.trackId.value,
        position = entry.position,
        addedAt = entry.addedAt.toEpoch(),
        sourceLockId = entry.sourceLockId?.value
    )

    fun toFavorite(entity: FavoriteEntity) = Favorite(
        trackId = TrackId.parse(entity.trackId),
        addedAt = Instant.ofEpochMilli(entity.addedAt)
    )

    fun toFavoriteEntity(favorite: Favorite) = FavoriteEntity(
        trackId = favorite.trackId.value,
        addedAt = favorite.addedAt.toEpoch()
    )

    fun toLyricsRecord(entity: LyricsCacheEntity) = LyricsRecord(
        trackId = TrackId.parse(entity.trackId),
        provider = ProviderId(entity.provider),
        language = entity.language,
        syncedLrc = entity.syncedLrc,
        plainText = entity.plainText,
        provenanceUrl = entity.provenanceUrl,
        fetchedAt = Instant.ofEpochMilli(entity.fetchedAt),
        etag = entity.etag,
        matchConfidence = entity.matchConfidence
    )

    fun toLyricsCacheEntity(record: LyricsRecord) = LyricsCacheEntity(
        trackId = record.trackId.value,
        provider = record.provider.value,
        language = record.language,
        syncedLrc = record.syncedLrc,
        plainText = record.plainText,
        provenanceUrl = record.provenanceUrl,
        fetchedAt = record.fetchedAt.toEpoch(),
        etag = record.etag,
        matchConfidence = record.matchConfidence
    )

    fun toPluginInstallation(entity: PluginInstallationEntity) = PluginInstallation(
        id = entity.id,
        version = entity.version,
        manifestHash = entity.manifestHash,
        enabled = entity.enabled,
        approvedDomains = decodeDomains(entity.approvedDomainJson),
        installOrigin = entity.installOrigin,
        installedAt = Instant.ofEpochMilli(entity.installedAt),
        manifestJson = entity.manifestJson
    )

    fun toPluginInstallationEntity(installation: PluginInstallation) = PluginInstallationEntity(
        id = installation.id,
        version = installation.version,
        manifestHash = installation.manifestHash,
        enabled = installation.enabled,
        approvedDomainJson = encodeDomains(installation.approvedDomains),
        installOrigin = installation.installOrigin,
        installedAt = installation.installedAt.toEpoch(),
        manifestJson = installation.manifestJson
    )

    fun toMetadataMatch(entity: MetadataMatchEntity) = MetadataMatch(
        trackId = TrackId.parse(entity.trackId),
        provider = ProviderId(entity.provider),
        externalId = entity.externalId,
        score = entity.score,
        manuallyConfirmed = entity.manuallyConfirmed,
        confirmedAt = Instant.ofEpochMilli(entity.confirmedAt)
    )

    fun toMetadataMatchEntity(match: MetadataMatch) = MetadataMatchEntity(
        trackId = match.trackId.value,
        provider = match.provider.value,
        externalId = match.externalId,
        score = match.score,
        manuallyConfirmed = match.manuallyConfirmed,
        confirmedAt = match.confirmedAt.toEpoch()
    )
}
