package com.tunenagi.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities — owned.
 *
 * Two deliberate choices, both recorded because they are the kind of thing a reviewer would
 * otherwise have to reverse-engineer:
 *
 * 1. **Columns are primitives, not domain value classes.** The domain types in
 *    `com.tunenagi.core.model` are the vocabulary of the app; the entities are the on-disk
 *    contract. Keeping them separate means the SQL schema is exactly what Room exports under
 *    `core/schemas/`, and every conversion goes through `TunenagiMappers`, where a corrupt row
 *    (an unknown enum, a malformed UUID) fails loudly instead of becoming a wrong object.
 * 2. **Ids stay `String` in the database.** `SPEC/DATA_MODEL.md` requires app-generated UUIDs, and
 *    the mappers are where that is enforced on read as well as on write.
 *
 * `deletedTombstone` exists because `SPEC/PROVIDERS.md` forbids deleting a track when its source
 * disappears: the row stays, the source row's availability changes.
 */

@Entity(
    tableName = "albums",
    indices = [Index("title"), Index("albumArtistId")]
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortTitle: String,
    val albumArtistId: String?,
    val year: Int?,
    val mbid: String?,
    val artworkRef: String?
)

@Entity(
    tableName = "artists",
    indices = [Index("name"), Index("sortName"), Index("mbid")]
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val mbid: String?
)

@Entity(
    tableName = "tracks",
    indices = [Index("title"), Index("sortTitle"), Index("albumId"), Index("mbid"), Index("isrc")],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortTitle: String,
    val durationMs: Long?,
    val albumId: String?,
    val isrc: String?,
    val mbid: String?,
    val artworkRef: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deletedTombstone: Boolean = false
)

/**
 * The credit rows. The primary key includes `ord` rather than being `(trackId, artistId)`, because
 * the same artist can legitimately appear twice on one track in two roles.
 */
@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistId", "role", "ord"],
    indices = [Index("artistId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackArtistEntity(
    val trackId: String,
    val artistId: String,
    val role: String,
    val ord: Int
)

/**
 * One playable version of a track. The unique index is the identity contract from
 * `SPEC/DATA_MODEL.md` — `(providerId, externalId, sourceVariant)` — and it is what stops the same
 * provider row being imported twice.
 */
@Entity(
    tableName = "track_sources",
    indices = [
        Index(value = ["providerId", "externalId", "sourceVariant"], unique = true),
        Index("trackId"),
        Index("availability"),
        Index("localUri")
    ],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackSourceEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val providerId: String,
    val externalId: String,
    val localUri: String?,
    // Stored as an empty string rather than NULL. SQLite treats every NULL as distinct for the
    // purposes of a unique index, so a nullable variant would let the same provider row be imported
    // twice while the unique index below still reported no violation (BUG-class found while
    // writing the uniqueness test; recorded in decisions.md).
    @ColumnInfo(defaultValue = "") val sourceVariant: String,
    val contentType: String?,
    val availability: String,
    val lastCheckedAt: Long?,
    @ColumnInfo(defaultValue = "0") val preferred: Boolean = false,
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false
)

@Entity(
    tableName = "playlists",
    indices = [Index("name"), Index("isSystem")]
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isSystem: Boolean = false
)

/**
 * A playlist entry is an *occurrence*, so the same track may appear twice and each copy can be
 * reordered or removed on its own. Ordering is `(position, addedAt)`: an integer position matches
 * what the drag-reorder UI actually produces, and `addedAt` is the tie-breaker the spec asks for so
 * two entries can never compare as "equal order" and sort non-deterministically.
 */
@Entity(
    tableName = "playlist_entries",
    indices = [Index(value = ["playlistId", "position", "addedAt"]), Index("trackId")],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaylistEntryEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAt: Long,
    val sourceLockId: String?
)

/**
 * A favourite is a property of a track, so deleting the track takes the favourite with it
 * (`SPEC/DATA_MODEL.md` declares this foreign key). The cascade is asserted by
 * `DatabaseConstraintTest`, which is how the missing constraint was found in the first place.
 */
@Entity(
    tableName = "favorites",
    indices = [Index("addedAt")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val addedAt: Long
)

/**
 * Cached lyrics. The primary key is `(trackId, provider)` so a second provider can be added later
 * without a migration, and `matchConfidence` is stored rather than recomputed because the UI shows
 * the provenance the user approved.
 */
@Entity(
    tableName = "lyrics_cache",
    primaryKeys = ["trackId", "provider"],
    indices = [Index("fetchedAt")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LyricsCacheEntity(
    val trackId: String,
    val provider: String,
    val language: String?,
    val syncedLrc: String?,
    val plainText: String?,
    val provenanceUrl: String?,
    val fetchedAt: Long,
    val etag: String?,
    val matchConfidence: Double?
)

/**
 * An installed declarative plugin. `manifestHash` is stored so a changed manifest can be detected
 * rather than trusted, and `approvedDomainJson` records exactly which domains the user consented
 * to — the host gateway checks that list, not the manifest, at request time.
 */
@Entity(
    tableName = "plugin_installations",
    indices = [Index("enabled"), Index("installOrigin")]
)
data class PluginInstallationEntity(
    @PrimaryKey val id: String,
    val version: String,
    val manifestHash: String,
    @ColumnInfo(defaultValue = "0") val enabled: Boolean = false,
    val approvedDomainJson: String,
    val installOrigin: String,
    val installedAt: Long,
    val manifestJson: String
)

/**
 * A confirmed metadata match. `manuallyConfirmed` exists so a user decision outranks any heuristic
 * later — `SPEC/DATA_MODEL.md`: manual overrides trump scoring.
 */
@Entity(
    tableName = "metadata_matches",
    indices = [Index("provider"), Index(value = ["provider", "externalId"])],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MetadataMatchEntity(
    @PrimaryKey val trackId: String,
    val provider: String,
    val externalId: String,
    val score: Double,
    @ColumnInfo(defaultValue = "0") val manuallyConfirmed: Boolean = false,
    val confirmedAt: Long
)
