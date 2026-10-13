package com.tunenagi.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The library database — owned.
 *
 * `exportSchema = true` is not decoration: the exported JSON under `core/schemas/` is the committed
 * record of what version 1 actually created, and `TunenagiMigrationsTest` builds its "old database"
 * from it. Without the export, a migration test can only test the migration against the *current*
 * entities, which is how migrations that only work on an empty install slip through.
 *
 * There is no `fallbackToDestructiveMigration` call anywhere in this project, and
 * `MigrationsSmokeTest` asserts that the builder path used in production does not gain one: a
 * migration that is missing must fail loudly rather than delete a user's playlists.
 */
@Database(
    entities = [
        AlbumEntity::class,
        ArtistEntity::class,
        TrackEntity::class,
        TrackArtistEntity::class,
        TrackSourceEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        FavoriteEntity::class,
        LyricsCacheEntity::class,
        PluginInstallationEntity::class,
        MetadataMatchEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TunenagiDatabase : RoomDatabase() {

    abstract fun artistDao(): ArtistDao

    abstract fun albumDao(): AlbumDao

    abstract fun trackCreditDao(): TrackCreditDao

    abstract fun trackDao(): TrackDao

    abstract fun sourceDao(): SourceDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun lyricsDao(): LyricsDao

    abstract fun pluginDao(): PluginDao

    abstract fun metadataMatchDao(): MetadataMatchDao

    companion object {
        const val DATABASE_NAME = "tunenagi.db"

        /**
         * The only supported way to open the database in application code. Every migration in
         * [TunenagiMigrations.ALL] is registered here; adding a schema version without adding its
         * migration therefore breaks at runtime instead of erasing data.
         */
        fun open(context: Context, name: String = DATABASE_NAME): TunenagiDatabase =
            Room.databaseBuilder(context.applicationContext, TunenagiDatabase::class.java, name)
                .addMigrations(*TunenagiMigrations.ALL)
                .build()
    }
}

/**
 * The explicit migration list.
 *
 * Empty at version 1 — version 2 adds the playback persistence tables and is defined in
 * `TunenagiMigrations`. Keeping the list as a single array means a reviewer can see every schema
 * change this app has ever shipped in one place, in order.
 */
object TunenagiMigrations {
    val ALL: Array<Migration> = arrayOf()
}
