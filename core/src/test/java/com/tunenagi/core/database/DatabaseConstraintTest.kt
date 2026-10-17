package com.tunenagi.core.database

import com.tunenagi.core.model.Favorite
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Constraint tests. A schema that is only asserted to "contain tables" proves nothing: each test
 * below tries to violate one rule and requires the database to refuse.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseConstraintTest {

    private lateinit var db: TunenagiDatabase

    @Before
    fun setUp() {
        db = Fixtures.database()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Runs a suspend block and returns the exception it must throw. */
    private fun failure(block: suspend () -> Unit): Throwable =
        assertThrows(Throwable::class.java) { runBlocking { block() } }

    @Test
    fun insertingAPlaylistEntryForAMissingTrackIsRefused() = runBlocking {
        val playlist = Fixtures.playlist()
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))

        val orphan = TunenagiMappers.toPlaylistEntryEntity(
            Fixtures.entry(playlist.id, TrackId.random(), position = 0)
        )
        val thrown = failure { db.playlistDao().insertEntry(orphan) }

        assertTrue(
            "expected a foreign key violation, got ${thrown.message}",
            thrown.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true)
        )
        assertEquals(0, db.playlistDao().entryCount(playlist.id.value))
    }

    @Test
    fun theSourceIdentityIndexIsUnique() = runBlocking {
        val trackId = TrackId.random()
        registerTrack(trackId)

        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId, variant = "stream")))
        val duplicate = TunenagiMappers.toTrackSourceEntity(
            Fixtures.source(trackId, variant = "stream", availability = SourceAvailability.Unavailable)
        )

        val thrown = failure { db.sourceDao().insert(duplicate) }
        assertTrue(
            "expected a unique violation, got ${thrown.message}",
            thrown.message.orEmpty().contains("UNIQUE", ignoreCase = true)
        )
        assertEquals(1, db.sourceDao().countForTrack(trackId.value))
    }

    /**
     * Regression for a real trap: SQLite treats every NULL as distinct inside a unique index, so a
     * nullable `sourceVariant` would let the same provider row be imported twice while the index
     * reported no conflict. The column stores `""` for "no variant" instead, and this test fails if
     * anyone changes it back.
     */
    @Test
    fun aSecondSourceWithNoVariantIsAlsoRefused() = runBlocking {
        val trackId = TrackId.random()
        registerTrack(trackId)

        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId)))
        assertEquals("", db.sourceDao().byIdentity(ProviderId.Audius.value, "D7KyD", "")?.sourceVariant)

        failure { db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId))) }
        assertEquals(1, db.sourceDao().countForTrack(trackId.value))
    }

    /** The same external id at a different provider is a different source and stays insertable. */
    @Test
    fun theSameExternalIdAtAnotherProviderIsAllowed() = runBlocking {
        val trackId = TrackId.random()
        registerTrack(trackId)

        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId)))
        db.sourceDao().insert(
            TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId, providerId = ProviderId.Local))
        )
        assertEquals(2, db.sourceDao().countForTrack(trackId.value))
    }

    @Test
    fun deletingATrackCascadesItsDependentsAndKeepsTheAlbum() = runBlocking {
        val album = Fixtures.album()
        db.albumDao().insert(TunenagiMappers.toAlbumEntity(album))

        val trackId = TrackId.random()
        val track = Fixtures.track(trackId, albumId = album.id)
        db.trackDao().insert(TunenagiMappers.toTrackEntity(track, Fixtures.now, Fixtures.now))
        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(Fixtures.source(trackId)))
        db.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(Favorite(trackId, Fixtures.now)))

        assertEquals(1, db.favoriteDao().count())
        assertEquals(1, db.sourceDao().countForTrack(trackId.value))

        db.trackDao().hardDelete(trackId.value)

        assertEquals(0, db.sourceDao().countForTrack(trackId.value))
        assertEquals(0, db.favoriteDao().count())
        assertNull(db.trackDao().byId(trackId.value))
        assertEquals("the album belongs to the library, not to the track", 1, countAlbums())
    }

    /**
     * The named trap of phase 01: "accidental deletion after source loss". A source that is removed
     * (a file deleted, a creator restricting a track) must take nothing with it - not the track, not
     * its credit, not its place in a playlist. Only the availability of the source changes, and this
     * test removes the row entirely to prove the foreign keys point the other way.
     */
    @Test
    fun deletingASourceLeavesTheTrackCreditsAndPlaylistsIntact() = runBlocking {
        val playlist = Fixtures.playlist()
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        val artist = Fixtures.artist()
        db.artistDao().insert(TunenagiMappers.toArtistEntity(artist))

        val trackId = TrackId.random()
        db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(trackId), Fixtures.now, Fixtures.now))
        db.trackCreditDao().insert(TunenagiMappers.toTrackArtistEntity(Fixtures.credit(trackId, artist.id)))
        val source = Fixtures.source(trackId)
        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(source))
        db.playlistDao().insertEntry(
            TunenagiMappers.toPlaylistEntryEntity(Fixtures.entry(playlist.id, trackId, position = 0))
        )
        db.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(Favorite(trackId, Fixtures.now)))

        assertEquals(1, db.sourceDao().delete(source.id.value))

        assertNotNull("the track survives its source", db.trackDao().byId(trackId.value))
        assertEquals(0, db.sourceDao().countForTrack(trackId.value))
        assertEquals(1, db.trackCreditDao().forTrack(trackId.value).size)
        assertEquals(1, db.playlistDao().entryCount(playlist.id.value))
        assertTrue("a favourite is about the track, not the source", db.favoriteDao().isFavorite(trackId.value))
    }

    @Test
    fun deletingAnAlbumClearsTheReferenceOnItsTracks() = runBlocking {
        val album = Fixtures.album()
        db.albumDao().insert(TunenagiMappers.toAlbumEntity(album))
        val trackId = TrackId.random()
        db.trackDao().insert(
            TunenagiMappers.toTrackEntity(Fixtures.track(trackId, albumId = album.id), Fixtures.now, Fixtures.now)
        )

        db.openHelper.writableDatabase.execSQL("DELETE FROM albums WHERE id = ?", arrayOf(album.id.value))

        assertNotNull("the track survives", db.trackDao().byId(trackId.value))
        assertNull("without an album reference", db.trackDao().byId(trackId.value)?.albumId)
    }

    @Test
    fun systemPlaylistsCannotBeDeleted() = runBlocking {
        val system = Fixtures.playlist(name = "On Device", isSystem = true)
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(system))

        assertEquals(0, db.playlistDao().deletePlaylist(system.id.value))
        assertNotNull(db.playlistDao().playlist(system.id.value))

        val user = Fixtures.playlist(name = "Favorites Mix")
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(user))
        assertEquals(1, db.playlistDao().deletePlaylist(user.id.value))
    }

    @Test
    fun theIndicesTheQueriesDependOnExistAndAreUniqueWhereRequired() = runBlocking {
        val expected = mapOf(
            "tracks" to setOf("index_tracks_sortTitle", "index_tracks_title", "index_tracks_albumId"),
            "track_sources" to setOf(
                "index_track_sources_providerId_externalId_sourceVariant",
                "index_track_sources_trackId"
            ),
            "playlist_entries" to setOf("index_playlist_entries_playlistId_position_addedAt"),
            "favorites" to setOf("index_favorites_addedAt")
        )
        expected.forEach { (table, names) ->
            val found = mutableSetOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA index_list('$table')").use { cursor ->
                while (cursor.moveToNext()) found += cursor.getString(1)
            }
            assertTrue("$table is missing ${names - found}", found.containsAll(names))
        }
        assertTrue("the source identity index must be unique", isUnique("index_track_sources_providerId_externalId_sourceVariant"))
        assertTrue("the playlist order index must not be unique", !isUnique("index_playlist_entries_playlistId_position_addedAt"))
    }

    @Test
    fun onlyOnePreferredSourcePerTrackSurvivesTheTransaction() = runBlocking {
        val trackId = TrackId.random()
        registerTrack(trackId)
        val first = Fixtures.source(trackId, variant = "a")
        val second = Fixtures.source(trackId, variant = "b")
        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(first))
        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(second))

        db.sourceDao().markPreferred(trackId.value, first.id.value)
        assertEquals(first.id.value, db.sourceDao().forTrack(trackId.value).single { it.preferred }.id)

        db.sourceDao().markPreferred(trackId.value, second.id.value)
        val preferred = db.sourceDao().forTrack(trackId.value).filter { it.preferred }
        assertEquals("exactly one source stays preferred", 1, preferred.size)
        assertEquals(second.id.value, preferred.single().id)
    }

    private suspend fun registerTrack(trackId: TrackId) {
        db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(trackId), Fixtures.now, Fixtures.now))
    }

    private fun countAlbums(): Int {
        var count = 0
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM albums").use { cursor ->
            if (cursor.moveToFirst()) count = cursor.getInt(0)
        }
        return count
    }

    private fun isUnique(indexName: String): Boolean {
        var unique = false
        db.openHelper.readableDatabase.query("PRAGMA index_list('track_sources')").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == indexName) unique = cursor.getInt(2) == 1
            }
        }
        return unique
    }
}
