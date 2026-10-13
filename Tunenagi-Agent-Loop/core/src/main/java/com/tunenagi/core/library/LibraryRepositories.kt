package com.tunenagi.core.library

import androidx.room.withTransaction
import com.tunenagi.core.database.FavoriteEntity
import com.tunenagi.core.database.PlaybackHistoryEntity
import com.tunenagi.core.database.PlaylistEntity
import com.tunenagi.core.database.PlaylistEntryEntity
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.Favorite
import com.tunenagi.core.model.Playlist
import com.tunenagi.core.model.PlaylistEntry
import com.tunenagi.core.model.PlaylistEntryId
import com.tunenagi.core.model.PlaylistId
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Library repositories — owned.
 *
 * Every multi-step write happens inside one Room transaction, because the interesting failures here
 * are partial ones: two entries claiming the same position, a delete that half-applied, a favourite
 * added twice. `SKILLS/migration.md` and `SPEC/QUALITY_GATES.md` both ask for concurrency and
 * rollback coverage, and the tests do exactly that against a real database.
 */

interface PlaylistRepository {

    fun observePlaylists(): Flow<List<Playlist>>

    suspend fun playlists(): List<Playlist>

    suspend fun playlist(id: PlaylistId): Playlist?

    suspend fun create(name: String, at: Instant, description: String? = null, isSystem: Boolean = false): Playlist

    suspend fun rename(id: PlaylistId, name: String, at: Instant, description: String? = null): Boolean

    /** Refuses to delete a system playlist; returns false rather than throwing. */
    suspend fun delete(id: PlaylistId): Boolean

    fun observeEntries(playlistId: PlaylistId): Flow<List<PlaylistEntry>>

    suspend fun entries(playlistId: PlaylistId): List<PlaylistEntry>

    /** Appends one occurrence. The same track may appear many times. */
    suspend fun addTrack(
        playlistId: PlaylistId,
        trackId: TrackId,
        at: Instant,
        sourceLockId: TrackSourceId? = null
    ): PlaylistEntry

    suspend fun removeEntry(entryId: PlaylistEntryId): Boolean

    /**
     * Applies a complete ordering. Rejected unless it names exactly the entries that playlist
     * currently holds, so a stale screen cannot silently drop or duplicate rows.
     */
    suspend fun reorder(playlistId: PlaylistId, orderedEntryIds: List<PlaylistEntryId>)

    suspend fun entryCount(playlistId: PlaylistId): Int
}

interface FavoriteRepository {

    fun observe(): Flow<List<Favorite>>

    suspend fun favorites(): List<Favorite>

    suspend fun isFavorite(trackId: TrackId): Boolean

    suspend fun add(trackId: TrackId, at: Instant): Favorite

    suspend fun remove(trackId: TrackId): Boolean

    /** Returns the state after the toggle, which is what an optimistic UI needs to settle on. */
    suspend fun toggle(trackId: TrackId, at: Instant): Boolean
}

/** One listening event as the UI shows it. */
data class HistoryRecord(
    val id: String,
    val trackId: TrackId,
    val startedAt: Instant,
    val listenedMs: Long,
    val completed: Boolean
)

/**
 * When a play counts as finished. `SPEC/DATA_MODEL.md` stores the flag rather than deriving it, so
 * this policy can change without rewriting history; the cap at 30 seconds is the usual "listened to"
 * threshold, and half the track is used for anything shorter than a minute.
 */
object CompletionPolicy {

    const val MIN_LISTENED_MS = 30_000L
    const val RATIO = 0.5

    fun thresholdMs(durationMs: Long?): Long =
        if (durationMs == null || durationMs <= 0) MIN_LISTENED_MS
        else minOf(MIN_LISTENED_MS, (durationMs * RATIO).toLong())

    fun isComplete(listenedMs: Long, durationMs: Long?): Boolean =
        listenedMs >= thresholdMs(durationMs)
}

interface HistoryRepository {

    fun observeRecent(): Flow<List<HistoryRecord>>

    suspend fun recordStart(
        trackId: TrackId,
        sourceId: TrackSourceId? = null,
        at: Instant,
        durationMs: Long? = null
    ): String

    suspend fun recordProgress(historyId: String, listenedMs: Long, durationMs: Long?): Boolean

    suspend fun byId(historyId: String): HistoryRecord?

    suspend fun recent(limit: Int): List<HistoryRecord>

    suspend fun recentTrackIds(limit: Int): List<TrackId>

    suspend fun clearForTrack(trackId: TrackId): Int

    suspend fun clear(): Int
}

class RoomPlaylistRepository(private val database: TunenagiDatabase) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        database.playlistDao().observePlaylists().map { rows -> rows.map(TunenagiMappers::toPlaylist) }

    override suspend fun playlists(): List<Playlist> =
        database.playlistDao().all().map(TunenagiMappers::toPlaylist)

    override suspend fun playlist(id: PlaylistId): Playlist? =
        database.playlistDao().playlist(id.value)?.let(TunenagiMappers::toPlaylist)

    override suspend fun create(
        name: String,
        at: Instant,
        description: String?,
        isSystem: Boolean
    ): Playlist {
        val playlist = Playlist(
            id = PlaylistId.random(),
            name = name,
            description = description,
            createdAt = at,
            updatedAt = at,
            isSystem = isSystem
        )
        database.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        return playlist
    }

    override suspend fun rename(id: PlaylistId, name: String, at: Instant, description: String?): Boolean {
        val existing = database.playlistDao().playlist(id.value) ?: return false
        require(name.isNotBlank()) { "A playlist name must not be blank" }
        return database.playlistDao().rename(id.value, name, description ?: existing.description, at.toEpochMilli()) > 0
    }

    override suspend fun delete(id: PlaylistId): Boolean =
        database.playlistDao().deletePlaylist(id.value) > 0

    override fun observeEntries(playlistId: PlaylistId): Flow<List<PlaylistEntry>> =
        database.playlistDao().observeEntries(playlistId.value).map { rows -> rows.map(TunenagiMappers::toPlaylistEntry) }

    override suspend fun entries(playlistId: PlaylistId): List<PlaylistEntry> =
        database.playlistDao().entries(playlistId.value).map(TunenagiMappers::toPlaylistEntry)

    /**
     * The append reads the current maximum and writes the next position. Those two statements must
     * not interleave with another append, so they share a transaction — otherwise two concurrent
     * adds both read position 3 and one of them is lost behind the other's row.
     */
    override suspend fun addTrack(
        playlistId: PlaylistId,
        trackId: TrackId,
        at: Instant,
        sourceLockId: TrackSourceId?
    ): PlaylistEntry = database.withTransaction {
        require(database.playlistDao().playlist(playlistId.value) != null) {
            "Cannot add to a playlist that does not exist: $playlistId"
        }
        require(database.trackDao().byId(trackId.value) != null) {
            "Cannot add an unknown track to a playlist: $trackId"
        }
        val entry = PlaylistEntry(
            id = PlaylistEntryId.random(),
            playlistId = playlistId,
            trackId = trackId,
            position = database.playlistDao().maxPosition(playlistId.value) + 1,
            addedAt = at,
            sourceLockId = sourceLockId
        )
        database.playlistDao().insertEntry(TunenagiMappers.toPlaylistEntryEntity(entry))
        // Adding a track is an edit, so the playlist's own updatedAt moves with it (the library
        // screen sorts "recently changed" by this).
        val playlist = database.playlistDao().playlist(playlistId.value)!!
        database.playlistDao().rename(playlistId.value, playlist.name, playlist.description, at.toEpochMilli())
        entry
    }

    override suspend fun removeEntry(entryId: PlaylistEntryId): Boolean = database.withTransaction {
        val entry = database.playlistDao().entry(entryId.value) ?: return@withTransaction false
        val removed = database.playlistDao().deleteEntry(entryId.value) > 0
        val remaining = database.playlistDao().entries(entry.playlistId)
        database.playlistDao().applyOrder(remaining.map { it.id })
        removed
    }

    override suspend fun reorder(playlistId: PlaylistId, orderedEntryIds: List<PlaylistEntryId>) =
        database.withTransaction {
            val current = database.playlistDao().entries(playlistId.value).map { it.id }
            require(orderedEntryIds.map { it.value }.toSet() == current.toSet() && orderedEntryIds.size == current.size) {
                "The new order must name exactly the ${current.size} entries this playlist holds"
            }
            database.playlistDao().applyOrder(orderedEntryIds.map { it.value })
        }

    override suspend fun entryCount(playlistId: PlaylistId): Int =
        database.playlistDao().entryCount(playlistId.value)
}

class RoomFavoriteRepository(private val database: TunenagiDatabase) : FavoriteRepository {

    override fun observe(): Flow<List<Favorite>> =
        database.favoriteDao().observeAll().map { rows -> rows.map(TunenagiMappers::toFavorite) }

    override suspend fun favorites(): List<Favorite> =
        database.favoriteDao().all().map(TunenagiMappers::toFavorite)

    override suspend fun isFavorite(trackId: TrackId): Boolean =
        database.favoriteDao().isFavorite(trackId.value)

    override suspend fun add(trackId: TrackId, at: Instant): Favorite = database.withTransaction {
        require(database.trackDao().byId(trackId.value) != null) {
            "Cannot favourite an unknown track: $trackId"
        }
        val favorite = Favorite(trackId = trackId, addedAt = at)
        database.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(favorite))
        favorite
    }

    override suspend fun remove(trackId: TrackId): Boolean =
        database.favoriteDao().remove(trackId.value) > 0

    override suspend fun toggle(trackId: TrackId, at: Instant): Boolean = database.withTransaction {
        if (database.favoriteDao().isFavorite(trackId.value)) {
            database.favoriteDao().remove(trackId.value)
            false
        } else {
            require(database.trackDao().byId(trackId.value) != null) {
                "Cannot favourite an unknown track: $trackId"
            }
            database.favoriteDao().upsert(
                TunenagiMappers.toFavoriteEntity(Favorite(trackId, at))
            )
            true
        }
    }
}

class RoomHistoryRepository(private val database: TunenagiDatabase) : HistoryRepository {

    override fun observeRecent(): Flow<List<HistoryRecord>> =
        database.historyDao().observeRecent().map { rows -> rows.map(::toRecord) }

    override suspend fun recordStart(
        trackId: TrackId,
        sourceId: TrackSourceId?,
        at: Instant,
        durationMs: Long?
    ): String = database.withTransaction {
        require(database.trackDao().byId(trackId.value) != null) {
            "Cannot record history for an unknown track: $trackId"
        }
        val id = java.util.UUID.randomUUID().toString()
        database.historyDao().insert(
            PlaybackHistoryEntity(
                id = id,
                trackId = trackId.value,
                sourceId = sourceId?.value,
                startedAt = at.toEpochMilli(),
                listenedMs = 0,
                completed = false
            )
        )
        id
    }

    override suspend fun recordProgress(historyId: String, listenedMs: Long, durationMs: Long?): Boolean {
        val row = database.historyDao().byId(historyId) ?: return false
        val bounded = listenedMs.coerceIn(0, maxOf(durationMs ?: Long.MAX_VALUE, 0))
        val completed = row.completed || CompletionPolicy.isComplete(bounded, durationMs)
        return database.historyDao().updateProgress(historyId, bounded, completed) > 0
    }

    override suspend fun byId(historyId: String): HistoryRecord? =
        database.historyDao().byId(historyId)?.let(::toRecord)

    override suspend fun recent(limit: Int): List<HistoryRecord> {
        require(limit > 0) { "A history limit must be positive, but was $limit" }
        return database.historyDao().recent(limit).map(::toRecord)
    }

    override suspend fun recentTrackIds(limit: Int): List<TrackId> =
        recent(limit).map { it.trackId }

    override suspend fun clearForTrack(trackId: TrackId): Int =
        database.historyDao().clearForTrack(trackId.value)

    override suspend fun clear(): Int = database.historyDao().clear()

    private fun toRecord(entity: PlaybackHistoryEntity) = HistoryRecord(
        id = entity.id,
        trackId = TrackId.parse(entity.trackId),
        startedAt = Instant.ofEpochMilli(entity.startedAt),
        listenedMs = entity.listenedMs,
        completed = entity.completed
    )
}
