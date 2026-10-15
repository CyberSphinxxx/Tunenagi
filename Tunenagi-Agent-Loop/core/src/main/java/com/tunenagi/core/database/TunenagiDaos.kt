package com.tunenagi.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAOs — owned.
 *
 * Inserts use [OnConflictStrategy.ABORT] on purpose: a duplicate is a bug in the caller (a repeated
 * import, an id collision), and silently replacing the row would hide the bug while destroying
 * whatever the first insert contained. Where replacement is genuinely intended — caches, favourites
 * — the strategy says `REPLACE` and the comment says why.
 */
@Dao
interface ArtistDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artist: ArtistEntity)

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE mbid = :mbid")
    suspend fun byMbid(mbid: String): ArtistEntity?

    @Query("SELECT * FROM artists ORDER BY sortName, id")
    suspend fun all(): List<ArtistEntity>
}

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(album: AlbumEntity)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: String): AlbumEntity?

    @Query("SELECT * FROM albums ORDER BY sortTitle, id")
    suspend fun all(): List<AlbumEntity>

    @Query("UPDATE albums SET artworkRef = :artworkRef WHERE id = :id")
    suspend fun setArtworkRef(id: String, artworkRef: String?): Int
}

/** The credit rows, kept separate from [ArtistDao] because they belong to the track. */
@Dao
interface TrackCreditDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(credit: TrackArtistEntity)

    @Query("SELECT * FROM track_artists WHERE trackId = :trackId ORDER BY ord")
    suspend fun forTrack(trackId: String): List<TrackArtistEntity>

    @Query("DELETE FROM track_artists WHERE trackId = :trackId")
    suspend fun clearForTrack(trackId: String): Int
}

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE deletedTombstone = 0 ORDER BY sortTitle, id")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE deletedTombstone = 0 AND title LIKE '%' || :term || '%' ORDER BY sortTitle, id")
    suspend fun searchByTitle(term: String): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE deletedTombstone = 0")
    suspend fun count(): Int

    /** Soft removal. `SPEC/PROVIDERS.md`: a track whose source vanished keeps its identity. */
    @Query("UPDATE tracks SET deletedTombstone = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun tombstone(id: String, updatedAt: Long): Int

    /**
     * Refreshes the fields a rescan can legitimately know better: the title, its sort key, the
     * duration and the artwork reference. Everything else about a track (its ids, its credits, its
     * album link) is a user-facing fact that a file's tags do not get to overwrite.
     */
    @Query(
        "UPDATE tracks SET title = :title, sortTitle = :sortTitle, durationMs = :durationMs, " +
            "artworkRef = :artworkRef, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun applyScannedMetadata(
        id: String,
        title: String,
        sortTitle: String,
        durationMs: Long?,
        artworkRef: String?,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun hardDelete(id: String): Int
}

@Dao
interface SourceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: TrackSourceEntity)

    @Query("SELECT * FROM track_sources WHERE id = :id")
    suspend fun byId(id: String): TrackSourceEntity?

    @Query("SELECT * FROM track_sources WHERE trackId = :trackId ORDER BY preferred DESC, providerId, id")
    suspend fun forTrack(trackId: String): List<TrackSourceEntity>

    @Query("SELECT * FROM track_sources WHERE trackId = :trackId ORDER BY preferred DESC, providerId, id")
    fun observeForTrack(trackId: String): Flow<List<TrackSourceEntity>>

    /**
     * The identity lookup behind the unique index. Pass `""` for "no variant": the column stores
     * the empty string, so a plain equality here and the unique index agree (see
     * `TrackSourceEntity`).
     */
    @Query(
        "SELECT * FROM track_sources WHERE providerId = :providerId AND externalId = :externalId " +
            "AND sourceVariant = :variant"
    )
    suspend fun byIdentity(providerId: String, externalId: String, variant: String): TrackSourceEntity?

    /**
     * Every source one discovery owns: a provider *and* its grant. `builtin.local` alone is not
     * enough to attribute a failure, because MediaStore and a SAF folder are two grants that fail
     * independently — losing the folder grant must not mark the on-device library unreadable.
     */
    @Query(
        "SELECT * FROM track_sources WHERE providerId = :providerId AND sourceVariant = :variant " +
            "ORDER BY id"
    )
    suspend fun forProviderVariant(providerId: String, variant: String): List<TrackSourceEntity>

    @Query("UPDATE track_sources SET localUri = :localUri, contentType = :contentType WHERE id = :id")
    suspend fun updateLocation(id: String, localUri: String, contentType: String?): Int

    @Query("UPDATE track_sources SET availability = :availability, lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun updateAvailability(id: String, availability: String, checkedAt: Long): Int

    @Query("UPDATE track_sources SET preferred = 0 WHERE trackId = :trackId")
    suspend fun clearPreferred(trackId: String)

    @Query("UPDATE track_sources SET preferred = 1 WHERE id = :id")
    suspend fun setPreferred(id: String)

    /** Exactly one source per track may be preferred, so the two statements share a transaction. */
    @Transaction
    suspend fun markPreferred(trackId: String, sourceId: String) {
        clearPreferred(trackId)
        setPreferred(sourceId)
    }

    /** A locked source is one the user chose by hand; matching must not move away from it. */
    @Query("UPDATE track_sources SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean): Int

    @Query("SELECT * FROM track_sources WHERE trackId = :trackId AND locked = 1 ORDER BY providerId, id")
    suspend fun lockedFor(trackId: String): List<TrackSourceEntity>

    @Query("DELETE FROM track_sources WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM track_sources WHERE trackId = :trackId")
    suspend fun countForTrack(trackId: String): Int

    @Query("SELECT * FROM track_sources WHERE availability = :availability")
    suspend fun withAvailability(availability: String): List<TrackSourceEntity>
}

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY isSystem DESC, name COLLATE NOCASE, id")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY isSystem DESC, name COLLATE NOCASE, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("UPDATE playlists SET name = :name, description = :description, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, description: String?, updatedAt: Long): Int

    /** System playlists cannot be deleted, which the WHERE clause enforces rather than the caller. */
    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: String): Int

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position, addedAt, id")
    suspend fun entries(playlistId: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position, addedAt, id")
    fun observeEntries(playlistId: String): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE id = :entryId")
    suspend fun entry(entryId: String): PlaylistEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: String): Int

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    suspend fun setEntryPosition(entryId: String, position: Int): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun entryCount(playlistId: String): Int

    /**
     * Replaces the whole ordering in one transaction. Ordering changes are never partial: a
     * half-applied reorder is worse than a failed one, because the user cannot tell what happened.
     */
    @Transaction
    suspend fun applyOrder(orderedEntryIds: List<String>) {
        orderedEntryIds.forEachIndexed { index, entryId -> setEntryPosition(entryId, index) }
    }
}

@Dao
interface FavoriteDao {

    /** Favouriting twice is idempotent from the user's point of view, so this replaces. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun remove(trackId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavorite(trackId: String): Boolean

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC, trackId")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC, trackId")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}

@Dao
interface LyricsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: LyricsCacheEntity)

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId ORDER BY fetchedAt DESC")
    suspend fun forTrack(trackId: String): List<LyricsCacheEntity>

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId AND provider = :provider")
    suspend fun forTrackFromProvider(trackId: String, provider: String): LyricsCacheEntity?

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun clearForTrack(trackId: String): Int
}

@Dao
interface PluginDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(installation: PluginInstallationEntity)

    @Query("SELECT * FROM plugin_installations WHERE id = :id")
    suspend fun byId(id: String): PluginInstallationEntity?

    @Query("SELECT * FROM plugin_installations ORDER BY installedAt DESC, id")
    suspend fun all(): List<PluginInstallationEntity>

    @Query("SELECT * FROM plugin_installations WHERE enabled = 1 ORDER BY installedAt DESC, id")
    suspend fun enabled(): List<PluginInstallationEntity>

    @Query("UPDATE plugin_installations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    @Query("DELETE FROM plugin_installations WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface MetadataMatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(match: MetadataMatchEntity)

    @Query("SELECT * FROM metadata_matches WHERE trackId = :trackId")
    suspend fun forTrack(trackId: String): MetadataMatchEntity?

    @Query("SELECT * FROM metadata_matches WHERE provider = :provider AND externalId = :externalId")
    suspend fun byExternalId(provider: String, externalId: String): List<MetadataMatchEntity>

    @Query("SELECT * FROM metadata_matches WHERE manuallyConfirmed = 1")
    suspend fun manuallyConfirmed(): List<MetadataMatchEntity>

    @Query("DELETE FROM metadata_matches WHERE trackId = :trackId")
    suspend fun delete(trackId: String): Int
}
