package com.tunenagi.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Queue, playback state and history DAOs — owned.
 *
 * The ordering rules from `SPEC/DATA_MODEL.md` live in the SQL rather than in the callers:
 * `(position, addedAt)` is the deterministic order, and a queue edit is one transaction so a
 * half-applied reorder cannot exist.
 */
@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: PlaybackQueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<PlaybackQueueItemEntity>)

    @Query("SELECT * FROM playback_queue_items ORDER BY position, addedAt, id")
    suspend fun all(): List<PlaybackQueueItemEntity>

    @Query("SELECT * FROM playback_queue_items ORDER BY position, addedAt, id")
    fun observeAll(): Flow<List<PlaybackQueueItemEntity>>

    @Query("SELECT * FROM playback_queue_items WHERE id = :id")
    suspend fun byId(id: String): PlaybackQueueItemEntity?

    @Query("SELECT COUNT(*) FROM playback_queue_items")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM playback_queue_items")
    suspend fun maxPosition(): Int

    @Query("DELETE FROM playback_queue_items WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM playback_queue_items")
    suspend fun clear(): Int

    @Query("UPDATE playback_queue_items SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int): Int

    /** Moves every row after [afterPosition] down one slot to make room for an insert. */
    @Query("UPDATE playback_queue_items SET position = position + 1 WHERE position > :afterPosition")
    suspend fun shiftFrom(afterPosition: Int): Int

    @Transaction
    suspend fun applyOrder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> setPosition(id, index) }
    }

    /**
     * Insert directly after the current item ("Play Next"). The shift and the insert are one
     * transaction, so no other reader can observe two items claiming the same position.
     */
    @Transaction
    suspend fun insertAfter(item: PlaybackQueueItemEntity, afterPosition: Int) {
        shiftFrom(afterPosition)
        insert(item.copy(position = afterPosition + 1))
    }
}

@Dao
interface PlaybackStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PlaybackStateEntity)

    // The id is written as a literal rather than a string template of SINGLETON_ID: Room resolves
    // the query at compile time, and a constant it cannot see would be a build failure on someone
    // else's machine. `SINGLETON_ID` stays the single source for the value in Kotlin code.
    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun current(): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE id = 1")
    fun observe(): Flow<PlaybackStateEntity?>

    @Query("DELETE FROM playback_state")
    suspend fun clear(): Int
}

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: PlaybackHistoryEntity)

    @Query("SELECT * FROM playback_history ORDER BY startedAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history ORDER BY startedAt DESC, id DESC")
    fun observeRecent(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE id = :id")
    suspend fun byId(id: String): PlaybackHistoryEntity?

    /** The threshold that marks a track as listened lives here, not in the caller. */
    @Query("UPDATE playback_history SET listenedMs = :listenedMs, completed = :completed WHERE id = :id")
    suspend fun updateProgress(id: String, listenedMs: Long, completed: Boolean): Int

    @Query("DELETE FROM playback_history WHERE trackId = :trackId")
    suspend fun clearForTrack(trackId: String): Int

    @Query("DELETE FROM playback_history")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun count(): Int
}
