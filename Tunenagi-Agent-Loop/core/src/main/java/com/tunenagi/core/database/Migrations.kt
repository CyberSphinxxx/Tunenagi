package com.tunenagi.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change this app has ever shipped — owned.
 *
 * The rules, and why they are rules:
 *
 * - **No destructive fallback.** `fallbackToDestructiveMigration` is never called anywhere in this
 *   project. A missing migration must throw, because the alternative is deleting a user's library to
 *   make a build start.
 * - **The SQL is written out, not inferred.** Room validates the result of a migration against the
 *   entities and throws `Migration didn't properly handle ...` if a column, type, default, foreign
 *   key or index is missing, so a mistake here fails a test instead of corrupting data on a phone.
 * - **Additive where possible.** Version 2 only creates tables; nothing user-visible is rewritten,
 *   which is the cheapest kind of migration to prove correct.
 */
object TunenagiMigrations {

    /**
     * Version 1 -> 2: the playback persistence tables.
     *
     * Separate statements on purpose: if the second one fails, the first has already run inside the
     * same Room upgrade transaction, so the database is rolled back rather than left half-migrated.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_queue_items` (" +
                    "`id` TEXT NOT NULL, " +
                    "`trackId` TEXT NOT NULL, " +
                    "`sourceOverrideId` TEXT, " +
                    "`position` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`sourceOverrideId`) REFERENCES `track_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_queue_items_position_addedAt` " +
                    "ON `playback_queue_items` (`position`, `addedAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_queue_items_trackId` " +
                    "ON `playback_queue_items` (`trackId`)"
            )
            // Room's own advice, taken rather than suppressed: a foreign key without an index on
            // the child column makes every parent write scan this table.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_queue_items_sourceOverrideId` " +
                    "ON `playback_queue_items` (`sourceOverrideId`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_state` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`currentQueueItemId` TEXT, " +
                    "`progressMs` INTEGER NOT NULL, " +
                    "`shuffleEnabled` INTEGER NOT NULL, " +
                    "`shuffleSeed` INTEGER, " +
                    "`repeatMode` TEXT NOT NULL, " +
                    "`lastUpdatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_history` (" +
                    "`id` TEXT NOT NULL, " +
                    "`trackId` TEXT NOT NULL, " +
                    "`sourceId` TEXT, " +
                    "`startedAt` INTEGER NOT NULL, " +
                    "`listenedMs` INTEGER NOT NULL, " +
                    "`completed` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`sourceId`) REFERENCES `track_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_startedAt` ON `playback_history` (`startedAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_trackId` ON `playback_history` (`trackId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_completed` ON `playback_history` (`completed`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_sourceId` ON `playback_history` (`sourceId`)"
            )
        }
    }

    /** In ascending order; a reviewer reads this list to see the whole schema history at a glance. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
