package com.tunenagi.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Playback persistence — the tables schema version 2 adds.
 *
 * They arrive in a migration rather than in version 1 because the library tables were already
 * shipped and used; adding tables the queue needs is exactly the kind of change the project must
 * be able to make without losing a user's playlists (`SKILLS/migration.md`).
 *
 * One queue row is one *occurrence*: `SPEC/DATA_MODEL.md` allows the same track to sit in the queue
 * twice, so the identity is the row id, never the track id.
 */

@Entity(
    tableName = "playback_queue_items",
    indices = [Index(value = ["position", "addedAt"]), Index("trackId"), Index("sourceOverrideId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceOverrideId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PlaybackQueueItemEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val sourceOverrideId: String?,
    val position: Int,
    val addedAt: Long
)

/**
 * The singleton playback state row.
 *
 * `shuffleSeed` is persisted next to `shuffleEnabled` because `SPEC/UX_DESIGN.md` requires shuffle
 * ordering to be deterministic: without the seed, restoring a queue after process death would
 * produce a different order from the one the user was listening to.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int,
    val currentQueueItemId: String?,
    val progressMs: Long,
    val shuffleEnabled: Boolean,
    val shuffleSeed: Int?,
    val repeatMode: String,
    val lastUpdatedAt: Long
) {
    companion object {
        /** There is exactly one row, and this is its primary key. */
        const val SINGLETON_ID = 1

        /** What the row looks like before anything has ever played. */
        fun initial(now: Long) = PlaybackStateEntity(
            id = SINGLETON_ID,
            currentQueueItemId = null,
            progressMs = 0L,
            shuffleEnabled = false,
            shuffleSeed = null,
            repeatMode = "Off",
            lastUpdatedAt = now
        )
    }
}

/**
 * A listening event. `completed` is stored rather than derived from `listenedMs` because the
 * threshold that counts as "listened" can change without rewriting history.
 */
@Entity(
    tableName = "playback_history",
    indices = [Index("startedAt"), Index("trackId"), Index("completed"), Index("sourceId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val sourceId: String?,
    val startedAt: Long,
    val listenedMs: Long,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false
)
