package com.tunenagi.core.database

import com.tunenagi.core.model.ArtistRole
import com.tunenagi.core.model.Favorite
import com.tunenagi.core.model.LyricsRecord
import com.tunenagi.core.model.MetadataMatch
import com.tunenagi.core.model.Playlist
import com.tunenagi.core.model.PluginInstallation
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behaviour tests for the DAOs and the entity/domain mappers. The round trips matter because the
 * mappers are the only translation between the storage contract and the app's vocabulary: a mapper
 * that loses a field silently would surface much later as "the favourite disappeared after a
 * restart".
 */
@RunWith(RobolectricTestRunner::class)
class DaoAndMapperTest {

    private lateinit var db: TunenagiDatabase

    @Before
    fun setUp() {
        db = Fixtures.database()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun everyModelSurvivesARoundTripThroughItsEntity() = runBlocking {
        val album = Fixtures.album()
        val artist = Fixtures.artist()
        val track = Fixtures.track(albumId = album.id)
        val credit = Fixtures.credit(track.id, artist.id, order = 1, role = ArtistRole.Featured)
        val source = Fixtures.source(track.id, variant = "stream")
        val playlist = Fixtures.playlist()
        val entry = Fixtures.entry(playlist.id, track.id, position = 3)
        val favorite = Favorite(track.id, Fixtures.now)
        val lyrics = LyricsRecord(
            trackId = track.id,
            provider = ProviderId("builtin.lrclib"),
            syncedLrc = "[00:12.30] the slow tide",
            fetchedAt = Fixtures.now,
            matchConfidence = 0.92
        )
        val plugin = PluginInstallation(
            id = "com.example.demo",
            version = "1.0.0",
            manifestHash = "abc123",
            enabled = true,
            approvedDomains = listOf("api.audius.co", "lrclib.net"),
            installOrigin = "file:///sdcard/demo.json",
            installedAt = Fixtures.now,
            manifestJson = "{}"
        )
        val match = MetadataMatch(track.id, ProviderId.Audius, "D7KyD", 0.81, manuallyConfirmed = true, confirmedAt = Fixtures.now)

        db.albumDao().insert(TunenagiMappers.toAlbumEntity(album))
        db.artistDao().insert(TunenagiMappers.toArtistEntity(artist))
        db.trackDao().insert(TunenagiMappers.toTrackEntity(track, Fixtures.now, Fixtures.now))
        db.trackCreditDao().insert(TunenagiMappers.toTrackArtistEntity(credit))
        db.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(source))
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        db.playlistDao().insertEntry(TunenagiMappers.toPlaylistEntryEntity(entry))
        db.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(favorite))
        db.lyricsDao().upsert(TunenagiMappers.toLyricsCacheEntity(lyrics))
        db.pluginDao().upsert(TunenagiMappers.toPluginInstallationEntity(plugin))
        db.metadataMatchDao().upsert(TunenagiMappers.toMetadataMatchEntity(match))

        assertEquals(track, TunenagiMappers.toTrack(db.trackDao().byId(track.id.value)!!))
        assertEquals(album, TunenagiMappers.toAlbum(db.albumDao().byId(album.id.value)!!))
        assertEquals(artist, TunenagiMappers.toArtist(db.artistDao().byId(artist.id.value)!!))
        assertEquals(source, TunenagiMappers.toTrackSource(db.sourceDao().byId(source.id.value)!!))
        assertEquals(playlist, TunenagiMappers.toPlaylist(db.playlistDao().playlist(playlist.id.value)!!))
        assertEquals(entry, TunenagiMappers.toPlaylistEntry(db.playlistDao().entry(entry.id.value)!!))
        assertEquals(favorite, TunenagiMappers.toFavorite(db.favoriteDao().observeAll().first().single()))
        assertEquals(lyrics, TunenagiMappers.toLyricsRecord(db.lyricsDao().forTrack(track.id.value).single()))
        assertEquals(plugin, TunenagiMappers.toPluginInstallation(db.pluginDao().byId(plugin.id)!!))
        assertEquals(match, TunenagiMappers.toMetadataMatch(db.metadataMatchDao().forTrack(track.id.value)!!))

        assertEquals(credit, TunenagiMappers.toTrackArtist(db.trackCreditDao().forTrack(track.id.value).single()))
        assertNull("an artist without an mbid is not found by one", db.artistDao().byMbid("never"))
    }

    @Test
    fun observingTracksEmitsNewRowsAndProjectsTombstones() = runBlocking {
        val first = Fixtures.track(title = "Alpha")
        val second = Fixtures.track(title = "Beta")
        db.trackDao().insert(TunenagiMappers.toTrackEntity(first, Fixtures.now, Fixtures.now))

        assertEquals(1, db.trackDao().observeAll().first().size)

        db.trackDao().insert(TunenagiMappers.toTrackEntity(second, Fixtures.now, Fixtures.now))
        val titles = db.trackDao().observeAll().first().map { it.title }
        assertEquals(listOf("Alpha", "Beta"), titles)

        db.trackDao().tombstone(second.id.value, Fixtures.now.toEpochMilli())
        assertEquals(listOf("Alpha"), db.trackDao().observeAll().first().map { it.title })
        assertEquals(1, db.trackDao().count())
    }

    @Test
    fun applyOrderRewritesPositionsInOneTransaction() = runBlocking {
        val playlist = Fixtures.playlist()
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        val trackIds = List(3) { TrackId.random() }
        trackIds.forEach { db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(it), Fixtures.now, Fixtures.now)) }
        val entries = trackIds.mapIndexed { index, trackId ->
            Fixtures.entry(playlist.id, trackId, position = index)
        }
        entries.forEach { db.playlistDao().insertEntry(TunenagiMappers.toPlaylistEntryEntity(it)) }
        assertEquals(2, db.playlistDao().maxPosition(playlist.id.value))

        val reversed = entries.reversed().map { it.id.value }
        db.playlistDao().applyOrder(reversed)

        val ordered = db.playlistDao().entries(playlist.id.value)
        assertEquals(reversed, ordered.map { it.id })
        assertEquals(listOf(0, 1, 2), ordered.map { it.position })
    }

    @Test
    fun anEmptyPlaylistHasNoPositionToAppendAfter() = runBlocking {
        val playlist = Fixtures.playlist()
        db.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        assertEquals(-1, db.playlistDao().maxPosition(playlist.id.value))
    }

    @Test
    fun favoritingTwiceIsIdempotentAndRemovalIsExplicit() = runBlocking {
        val trackId = TrackId.random()
        db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(trackId), Fixtures.now, Fixtures.now))

        db.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(Favorite(trackId, Fixtures.now)))
        db.favoriteDao().upsert(TunenagiMappers.toFavoriteEntity(Favorite(trackId, Fixtures.now.plusSeconds(60))))

        assertEquals(1, db.favoriteDao().count())
        assertTrue(db.favoriteDao().isFavorite(trackId.value))
        assertEquals(1, db.favoriteDao().remove(trackId.value))
        assertFalse(db.favoriteDao().isFavorite(trackId.value))
        assertEquals(0, db.favoriteDao().remove(trackId.value))
    }

    @Test
    fun pluginsCanBeDisabledWithoutBeingUninstalled() = runBlocking {
        val plugin = PluginInstallation("com.example.demo", "1.0.0", "hash", true, emptyList(), "file:///a.json", Fixtures.now, "{}")
        db.pluginDao().upsert(TunenagiMappers.toPluginInstallationEntity(plugin))
        assertEquals(1, db.pluginDao().enabled().size)

        db.pluginDao().setEnabled(plugin.id, false)
        assertEquals(0, db.pluginDao().enabled().size)
        assertNotNull("installation survives disabling", db.pluginDao().byId(plugin.id))
        assertEquals(1, db.pluginDao().delete(plugin.id))
    }

    @Test
    fun lyricsCanBeClearedPerTrack() = runBlocking {
        val trackId = TrackId.random()
        db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(trackId), Fixtures.now, Fixtures.now))
        db.lyricsDao().upsert(
            TunenagiMappers.toLyricsCacheEntity(
                LyricsRecord(trackId, ProviderId("builtin.lrclib"), plainText = "line", fetchedAt = Fixtures.now)
            )
        )
        assertEquals(1, db.lyricsDao().clearForTrack(trackId.value))
        assertEquals(0, db.lyricsDao().clearForTrack(trackId.value))
        assertNull(db.lyricsDao().forTrackFromProvider(trackId.value, "builtin.lrclib"))
    }

    @Test
    fun searchFindsTitlesCaseInsensitively() = runBlocking {
        listOf("The Slow Tide", "Tidal Drift", "Nightfall").forEach {
            db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(title = it), Fixtures.now, Fixtures.now))
        }
        assertEquals(listOf("The Slow Tide"), db.trackDao().searchByTitle("tide").map { it.title })
        val lower = db.trackDao().searchByTitle("tidal").map { it.title }
        assertEquals("a lowercase term finds a capitalised title", listOf("Tidal Drift"), lower)
        val upper = db.trackDao().searchByTitle("THE SLOW").map { it.title }
        assertEquals("a capitalised term finds a lowercase match", listOf("The Slow Tide"), upper)
        assertEquals(0, db.trackDao().searchByTitle("nothing").size)
    }

    /**
     * Corrupt rows must fail loudly. Both forms here are things a future migration bug really can
     * produce: an enum value written by a newer version, and a column that is not a UUID.
     */
    @Test
    fun aCorruptRowIsRejectedInsteadOfBecomingAWrongObject() = runBlocking<Unit> {
        val trackId = TrackId.random()
        db.trackDao().insert(TunenagiMappers.toTrackEntity(Fixtures.track(trackId), Fixtures.now, Fixtures.now))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_sources (id, trackId, providerId, externalId, localUri, sourceVariant, " +
                "contentType, availability, lastCheckedAt, preferred, locked) VALUES (?, ?, ?, ?, NULL, '', NULL, ?, NULL, 0, 0)",
            arrayOf(TrackSourceId.random().value, trackId.value, ProviderId.Audius.value, "D7KyD", "maybe")
        )
        val row = db.sourceDao().forTrack(trackId.value).single()

        val enumFailure = assertThrows(IllegalArgumentException::class.java) { TunenagiMappers.toTrackSource(row) }
        assertTrue(enumFailure.message.orEmpty().contains("maybe"))

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tracks (id, title, sortTitle, durationMs, albumId, isrc, mbid, artworkRef, " +
                "createdAt, updatedAt, deletedTombstone) " +
                "VALUES ('provider-track-1', 'T', 't', NULL, NULL, NULL, NULL, NULL, 0, 0, 0)"
        )
        val corruptTrack = db.trackDao().byId("provider-track-1")!!
        assertThrows(IllegalArgumentException::class.java) { TunenagiMappers.toTrack(corruptTrack) }
    }
}
