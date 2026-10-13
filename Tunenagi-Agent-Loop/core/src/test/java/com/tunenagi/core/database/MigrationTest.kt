package com.tunenagi.core.database

import android.database.Cursor
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Migration tests — owned test code.
 *
 * The database under test is a **real version-1 database created from the committed version-1
 * schema**, seeded with the kind of data a user would already have: playlists with a duplicated
 * track, a favourite, lyrics, an installed plugin and a source that is no longer available. Only
 * then is the production open path used, so what is asserted is what a user's phone would do on
 * upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val databaseName = "migration-test.db"

    private val trackA = "11111111-1111-4111-8111-111111111111"
    private val trackB = "22222222-2222-4222-8222-222222222222"
    private val albumId = "33333333-3333-4333-8333-333333333333"
    private val artistId = "44444444-4444-4444-8444-444444444444"
    private val playlistId = "55555555-5555-4555-8555-555555555555"
    private val localSourceId = "66666666-6666-4666-8666-666666666666"
    private val audiusSourceId = "77777777-7777-4777-8777-777777777777"

    @Before
    fun createOldDatabase() {
        ExportedSchema.createFromExported(databaseName, version = 1) { db -> seed(db) }
    }

    @After
    fun removeDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO albums (id, title, sortTitle, albumArtistId, year, mbid, artworkRef) " +
                "VALUES (?, 'Dawn', 'dawn', NULL, 2026, NULL, NULL)", arrayOf(albumId)
        )
        db.execSQL(
            "INSERT INTO artists (id, name, sortName, mbid) VALUES (?, 'Kaori Ito', 'kaori ito', NULL)",
            arrayOf(artistId)
        )
        listOf(trackA to "The Slow Tide", trackB to "Nightfall").forEachIndexed { index, (id, title) ->
            db.execSQL(
                "INSERT INTO tracks (id, title, sortTitle, durationMs, albumId, isrc, mbid, artworkRef, " +
                    "createdAt, updatedAt, deletedTombstone) " +
                    "VALUES (?, ?, ?, 190000, ?, NULL, NULL, NULL, 1000, 1000, 0)",
                arrayOf(id, title, title.lowercase(), if (index == 0) albumId else null)
            )
        }
        db.execSQL(
            "INSERT INTO track_sources (id, trackId, providerId, externalId, localUri, sourceVariant, " +
                "contentType, availability, lastCheckedAt, preferred, locked) " +
                "VALUES (?, ?, 'builtin.local', 'media/1', 'content://media/external/audio/1', '', 'audio/mpeg', 'Available', 1000, 1, 0)",
            arrayOf(localSourceId, trackA)
        )
        db.execSQL(
            "INSERT INTO track_sources (id, trackId, providerId, externalId, localUri, sourceVariant, " +
                "contentType, availability, lastCheckedAt, preferred, locked) " +
                "VALUES (?, ?, 'builtin.audius', 'D7KyD', NULL, 'stream', NULL, 'Restricted', 1000, 0, 0)",
            arrayOf(audiusSourceId, trackA)
        )
        db.execSQL(
            "INSERT INTO playlists (id, name, description, createdAt, updatedAt, isSystem) " +
                "VALUES (?, 'Favorites Mix', NULL, 1000, 1000, 0)", arrayOf(playlistId)
        )
        // The same track twice: the occurrence identity is what migration must not collapse.
        db.execSQL(
            "INSERT INTO playlist_entries (id, playlistId, trackId, position, addedAt, sourceLockId) " +
                "VALUES ('entry-1', ?, ?, 0, 1000, NULL)", arrayOf(playlistId, trackA)
        )
        db.execSQL(
            "INSERT INTO playlist_entries (id, playlistId, trackId, position, addedAt, sourceLockId) " +
                "VALUES ('entry-2', ?, ?, 1, 2000, ?)", arrayOf(playlistId, trackA, localSourceId)
        )
        db.execSQL("INSERT INTO favorites (trackId, addedAt) VALUES (?, 1500)", arrayOf(trackB))
        db.execSQL(
            "INSERT INTO lyrics_cache (trackId, provider, language, syncedLrc, plainText, " +
                "provenanceUrl, fetchedAt, etag, matchConfidence) " +
                "VALUES (?, 'builtin.lrclib', 'en', '[00:12.30] the slow tide', NULL, 'https://lrclib.net/x', 1600, NULL, 0.92)",
            arrayOf(trackA)
        )
        db.execSQL(
            "INSERT INTO plugin_installations (id, version, manifestHash, enabled, approvedDomainJson, " +
                "installOrigin, installedAt, manifestJson) " +
                "VALUES ('com.example.demo', '1.0.0', 'hash', 1, 'api.audius.co,lrclib.net', 'file:///a.json', 1700, '{}')"
        )
        db.execSQL(
            "INSERT INTO metadata_matches (trackId, provider, externalId, score, manuallyConfirmed, confirmedAt) " +
                "VALUES (?, 'builtin.audius', 'D7KyD', 0.81, 1, 1800)", arrayOf(trackA)
        )
    }

    @Test
    fun everyUserRowSurvivesTheUpgradeToVersionTwo() = runBlocking {
        val database = TunenagiDatabase.open(RuntimeEnvironment.getApplication(), databaseName)
        try {
            assertEquals(2, database.trackDao().count())
            assertEquals(
                "The Slow Tide",
                TunenagiMappers.toTrack(database.trackDao().byId(trackA)!!).title
            )
            assertEquals(albumId, database.trackDao().byId(trackA)!!.albumId)
            assertEquals("Dawn", TunenagiMappers.toAlbum(database.albumDao().byId(albumId)!!).title)

            val sources = database.sourceDao().forTrack(trackA)
            assertEquals(2, sources.size)
            assertEquals(SourceAvailability.Available, TunenagiMappers.toTrackSource(sources.single { it.preferred }).availability)
            assertEquals(
                SourceAvailability.Restricted,
                TunenagiMappers.toTrackSource(sources.single { it.providerId == ProviderId.Audius.value }).availability
            )

            val entries = database.playlistDao().entries(playlistId)
            assertEquals("a duplicated track stays two occurrences", 2, entries.size)
            assertEquals(listOf(trackA, trackA), entries.map { it.trackId })
            assertEquals(localSourceId, entries[1].sourceLockId)

            assertEquals(1, database.favoriteDao().count())
            assertTrue(database.favoriteDao().isFavorite(trackB))
            assertEquals(0.92, database.lyricsDao().forTrackFromProvider(trackA, "builtin.lrclib")!!.matchConfidence!!, 0.0001)
            assertEquals(
                listOf("api.audius.co", "lrclib.net"),
                TunenagiMappers.toPluginInstallation(database.pluginDao().byId("com.example.demo")!!).approvedDomains
            )
            assertEquals("D7KyD", database.metadataMatchDao().forTrack(trackA)!!.externalId)
        } finally {
            database.close()
        }
    }

    @Test
    fun theNewTablesAreUsableAndEnforceTheirOwnRules() = runBlocking<Unit> {
        val database = TunenagiDatabase.open(RuntimeEnvironment.getApplication(), databaseName)
        try {
            assertEquals("an upgrade does not invent playback state", null, database.playbackStateDao().current())

            database.queueDao().insert(
                PlaybackQueueItemEntity(id = "queue-1", trackId = trackA, sourceOverrideId = null, position = 0, addedAt = 1000)
            )
            database.queueDao().insert(
                PlaybackQueueItemEntity(id = "queue-2", trackId = trackA, sourceOverrideId = localSourceId, position = 1, addedAt = 2000)
            )
            database.playbackStateDao().upsert(PlaybackStateEntity.initial(now = 3000).copy(shuffleEnabled = true, shuffleSeed = 42))
            database.historyDao().insert(
                PlaybackHistoryEntity(id = "history-1", trackId = trackB, sourceId = null, startedAt = 4000, listenedMs = 5000)
            )

            assertEquals(2, database.queueDao().count())
            assertEquals(listOf("queue-1", "queue-2"), database.queueDao().all().map { it.id })
            assertEquals(42, database.playbackStateDao().current()!!.shuffleSeed)
            assertEquals(1, database.historyDao().recent(limit = 10).size)

            // The migrated foreign keys are enforced, not merely declared.
            val orphan = PlaybackQueueItemEntity(id = "queue-3", trackId = "nope", sourceOverrideId = null, position = 2, addedAt = 3000)
            assertThrows(Throwable::class.java) { runBlocking { database.queueDao().insert(orphan) } }
        } finally {
            database.close()
        }
    }

    @Test
    fun playNextInsertsImmediatelyAfterTheCurrentItem() = runBlocking {
        val database = TunenagiDatabase.open(RuntimeEnvironment.getApplication(), databaseName)
        try {
            database.queueDao().insertAll(
                listOf(
                    PlaybackQueueItemEntity("queue-1", trackA, null, 0, 1000),
                    PlaybackQueueItemEntity("queue-2", trackB, null, 1, 2000)
                )
            )
            database.queueDao().insertAfter(
                PlaybackQueueItemEntity("queue-next", trackB, null, 0, 3000),
                afterPosition = 0
            )

            assertEquals(listOf("queue-1", "queue-next", "queue-2"), database.queueDao().all().map { it.id })
            assertEquals(listOf(0, 1, 2), database.queueDao().all().map { it.position })
        } finally {
            database.close()
        }
    }

    /**
     * Without a registered migration Room must refuse to open the database, and the users' rows must
     * still be there afterwards. This is the assertion that stands between "the app failed to start"
     * and "the app deleted someone's library to start".
     */
    @Test
    fun aMissingMigrationFailsLoudlyAndDestroysNothing() {
        val context = RuntimeEnvironment.getApplication()
        val unguarded = Room.databaseBuilder(context, TunenagiDatabase::class.java, databaseName).build()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { unguarded.trackDao().count() }
        }
        assertTrue(
            "expected Room to explain the missing migration, got ${thrown.message}",
            thrown.message.orEmpty().contains("migration", ignoreCase = true)
        )
        unguarded.close()

        assertEquals(2, countRows("tracks"))
        assertEquals(2, countRows("playlist_entries"))
        assertEquals(2, countRows("track_sources"))
    }

    /**
     * Adversarial check of the check: a migration that does *not* produce the schema the entities
     * expect must be rejected, so the tests above cannot pass on a migration that silently did the
     * wrong thing.
     */
    @Test
    fun aMigrationThatProducesTheWrongSchemaIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val broken = Room.databaseBuilder(context, TunenagiDatabase::class.java, databaseName)
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Deliberately incomplete: no columns beyond the id, and two tables missing.
                    db.execSQL("CREATE TABLE IF NOT EXISTS `playback_state` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                }
            })
            .build()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { broken.trackDao().count() }
        }
        assertTrue(
            "expected Room to report the schema mismatch, got ${thrown.message}",
            thrown.message.orEmpty().contains("Migration didn't properly handle", ignoreCase = true)
        )
        broken.close()

        assertEquals("the failed migration rolled back", 2, countRows("tracks"))
        assertEquals(2, countRows("playlist_entries"))
    }

    /** Reads the file directly, so a failed Room open cannot hide what is actually on disk. */
    private fun countRows(table: String): Int = ExportedSchema.read(
        databaseName,
        version = 1,
        sql = "SELECT COUNT(*) FROM `$table`"
    ) { cursor: Cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    @Test
    fun theOldAndNewSchemasExportVersionsAndTablesWeCanName() {
        assertEquals(1, ExportedSchema.version(1))
        assertEquals(2, ExportedSchema.version(2))
        assertTrue(ExportedSchema.tableNames(1).containsAll(listOf("tracks", "playlist_entries", "track_sources")))
        assertTrue(!ExportedSchema.tableNames(1).contains("playback_queue_items"))
        assertTrue(ExportedSchema.tableNames(2).containsAll(listOf("playback_queue_items", "playback_state", "playback_history")))
    }
}
