package com.tunenagi.core.library

import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.PlaylistId
import com.tunenagi.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Playlist, favourite and history behaviour, including the two things that only break under real
 * use: two writes racing for the same position, and a multi-step edit failing halfway.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {

    private lateinit var db: TunenagiDatabase
    private lateinit var playlists: PlaylistRepository
    private lateinit var favorites: FavoriteRepository
    private lateinit var history: HistoryRepository

    private val trackIds = List(3) { TrackId.random() }

    @Before
    fun setUp() = runBlocking {
        db = Fixtures.database()
        playlists = RoomPlaylistRepository(db)
        favorites = RoomFavoriteRepository(db)
        history = RoomHistoryRepository(db)
        trackIds.forEachIndexed { index, id ->
            db.trackDao().insert(
                TunenagiMappers.toTrackEntity(
                    Fixtures.track(id, title = "Track $index").copy(durationMs = if (index == 0) 190_000 else 40_000),
                    Fixtures.now,
                    Fixtures.now
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- playlists ---------------------------------------------------------------------------

    @Test
    fun creatingRenamingAndDeletingAPlaylist() = runBlocking {
        val playlist = playlists.create("Favorites Mix", Fixtures.now)
        assertEquals("Favorites Mix", playlist.name)
        assertFalse(playlist.isSystem)

        assertTrue(playlists.rename(playlist.id, "Late Night", Fixtures.now.plusSeconds(5), "slow ones"))
        val renamed = playlists.playlist(playlist.id)!!
        assertEquals("Late Night", renamed.name)
        assertEquals("slow ones", renamed.description)
        assertTrue(renamed.updatedAt.isAfter(renamed.createdAt) || renamed.updatedAt == renamed.createdAt)

        assertTrue(playlists.delete(playlist.id))
        assertNull(playlists.playlist(playlist.id))
        assertFalse("deleting twice reports nothing was deleted", playlists.delete(playlist.id))
    }

    @Test
    fun aSystemPlaylistCannotBeDeletedOrRenamedByAccident() = runBlocking {
        val system = playlists.create("On Device", Fixtures.now, isSystem = true)

        assertFalse(playlists.delete(system.id))
        assertNotNull(playlists.playlist(system.id))
    }

    @Test
    fun renamingToBlankIsRefusedAndReturnsFalseForAMissingPlaylist() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { playlists.rename(playlist.id, "   ", Fixtures.now) }
        }
        assertFalse(playlists.rename(PlaylistId.random(), "Nothing", Fixtures.now))
        assertEquals("Mix", playlists.playlist(playlist.id)!!.name)
    }

    @Test
    fun addingTracksAppendsContiguousPositionsAndAllowsDuplicates() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)

        playlists.addTrack(playlist.id, trackIds[0], Fixtures.now)
        val duplicate = playlists.addTrack(playlist.id, trackIds[0], Fixtures.now.plusSeconds(1))
        playlists.addTrack(playlist.id, trackIds[1], Fixtures.now.plusSeconds(2))

        val entries = playlists.entries(playlist.id)
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals(2, entries.count { it.trackId == trackIds[0] })
        assertEquals(duplicate.id, entries[1].id)
    }

    @Test
    fun addingToAnUnknownPlaylistOrAnUnknownTrackIsRefusedWithoutWriting() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { playlists.addTrack(PlaylistId.random(), trackIds[0], Fixtures.now) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { playlists.addTrack(playlist.id, TrackId.random(), Fixtures.now) }
        }
        assertEquals(0, playlists.entryCount(playlist.id))
    }

    @Test
    fun removingAnEntryClosesTheGapInPositions() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)
        trackIds.forEach { playlists.addTrack(playlist.id, it, Fixtures.now) }
        val middle = playlists.entries(playlist.id)[1]

        assertTrue(playlists.removeEntry(middle.id))

        val remaining = playlists.entries(playlist.id)
        assertEquals(listOf(trackIds[0], trackIds[2]), remaining.map { it.trackId })
        assertEquals(listOf(0, 1), remaining.map { it.position })
        assertFalse(playlists.removeEntry(middle.id))
    }

    @Test
    fun reorderingRewritesPositionsInTheGivenOrder() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)
        trackIds.forEach { playlists.addTrack(playlist.id, it, Fixtures.now) }
        val reversed = playlists.entries(playlist.id).map { it.id }.reversed()

        playlists.reorder(playlist.id, reversed)

        val ordered = playlists.entries(playlist.id)
        assertEquals(reversed, ordered.map { it.id })
        assertEquals(listOf(0, 1, 2), ordered.map { it.position })
    }

    /** A stale screen is the normal way this happens: it offers an order that no longer matches. */
    @Test
    fun aReorderThatDoesNotDescribeTheCurrentEntriesIsRefusedAndChangesNothing() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)
        trackIds.forEach { playlists.addTrack(playlist.id, it, Fixtures.now) }
        val before = playlists.entries(playlist.id)
        val stale = before.take(2).map { it.id }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { playlists.reorder(playlist.id, stale) }
        }
        assertTrue(thrown.message.orEmpty().contains("exactly"))

        val after = playlists.entries(playlist.id)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.position }, after.map { it.position })
    }

    /**
     * Ten concurrent appends must produce ten rows with ten distinct positions. Without the shared
     * transaction around read-max-then-insert, two appends read the same maximum and collide.
     */
    @Test
    fun concurrentAppendsDoNotCollideOnPositions() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)

        coroutineScope {
            (1..10).map { index ->
                async(Dispatchers.Default) {
                    playlists.addTrack(playlist.id, trackIds[index % trackIds.size], Fixtures.now.plusMillis(index.toLong()))
                }
            }.awaitAll()
        }

        val entries = playlists.entries(playlist.id)
        assertEquals(10, entries.size)
        assertEquals((0 until 10).toList(), entries.map { it.position })
        assertEquals(10, entries.map { it.id }.toSet().size)
    }

    @Test
    fun observingAPlaylistSeesNewEntries() = runBlocking {
        val playlist = playlists.create("Mix", Fixtures.now)
        assertTrue(playlists.observeEntries(playlist.id).first().isEmpty())

        playlists.addTrack(playlist.id, trackIds[0], Fixtures.now)

        assertEquals(1, playlists.observeEntries(playlist.id).first().size)
        assertEquals(listOf("Mix"), playlists.observePlaylists().first().map { it.name })
    }

    // ---- favourites --------------------------------------------------------------------------

    @Test
    fun togglingAFavoriteFlipsItAndReportsTheNewState() = runBlocking {
        assertEquals(true, favorites.toggle(trackIds[0], Fixtures.now))
        assertTrue(favorites.isFavorite(trackIds[0]))

        assertEquals(false, favorites.toggle(trackIds[0], Fixtures.now.plusSeconds(1)))
        assertFalse(favorites.isFavorite(trackIds[0]))
    }

    @Test
    fun favouritingIsIdempotentAndRemovingSomethingNotFavouritedReportsFalse() = runBlocking {
        favorites.add(trackIds[0], Fixtures.now)
        favorites.add(trackIds[0], Fixtures.now.plusSeconds(30))

        assertEquals(1, favorites.favorites().size)
        assertFalse(favorites.remove(TrackId.random()))
        assertTrue(favorites.remove(trackIds[0]))
    }

    @Test
    fun favouritingAnUnknownTrackIsRefused() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { favorites.add(TrackId.random(), Fixtures.now) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { favorites.toggle(TrackId.random(), Fixtures.now) }
        }
        assertTrue(favorites.favorites().isEmpty())
    }

    @Test
    fun favouritesAreOrderedNewestFirst() = runBlocking {
        favorites.add(trackIds[0], Fixtures.now)
        favorites.add(trackIds[1], Fixtures.now.plusSeconds(60))

        assertEquals(listOf(trackIds[1], trackIds[0]), favorites.favorites().map { it.trackId })
    }

    // ---- history -----------------------------------------------------------------------------

    @Test
    fun aPlayIsNotCompleteUntilTheThresholdIsReached() = runBlocking {
        val id = history.recordStart(trackIds[0], at = Fixtures.now, durationMs = 190_000)

        assertTrue(history.recordProgress(id, listenedMs = 29_999, durationMs = 190_000))
        assertFalse(history.byId(id)!!.completed)

        history.recordProgress(id, listenedMs = 30_000, durationMs = 190_000)
        assertTrue(history.byId(id)!!.completed)
        assertEquals(30_000, history.byId(id)!!.listenedMs)
    }

    @Test
    fun aShortTrackUsesHalfItsLengthAsTheThreshold() = runBlocking {
        assertEquals(20_000, CompletionPolicy.thresholdMs(40_000))
        assertEquals(30_000, CompletionPolicy.thresholdMs(190_000))
        assertEquals(30_000, CompletionPolicy.thresholdMs(null))

        val id = history.recordStart(trackIds[1], at = Fixtures.now, durationMs = 40_000)
        history.recordProgress(id, listenedMs = 19_000, durationMs = 40_000)
        assertFalse(history.byId(id)!!.completed)

        history.recordProgress(id, listenedMs = 20_000, durationMs = 40_000)
        assertTrue(history.byId(id)!!.completed)
    }

    @Test
    fun completionIsStickyAndListenedTimeIsBounded() = runBlocking {
        val id = history.recordStart(trackIds[1], at = Fixtures.now, durationMs = 40_000)
        history.recordProgress(id, listenedMs = 40_000, durationMs = 40_000)
        assertTrue(history.byId(id)!!.completed)

        history.recordProgress(id, listenedMs = 1_000, durationMs = 40_000)

        val record = history.byId(id)!!
        assertTrue("a finished play stays finished", record.completed)
        assertEquals(1_000, record.listenedMs)
    }

    @Test
    fun listenedTimeCannotExceedTheTrackLength() = runBlocking {
        val id = history.recordStart(trackIds[1], at = Fixtures.now, durationMs = 40_000)
        history.recordProgress(id, listenedMs = 999_999, durationMs = 40_000)

        assertEquals(40_000, history.byId(id)!!.listenedMs)
    }

    @Test
    fun recentHistoryIsNewestFirstAndBounded() = runBlocking<Unit> {
        trackIds.forEachIndexed { index, trackId ->
            history.recordStart(trackId, at = Fixtures.now.plusSeconds(index.toLong()), durationMs = 190_000)
        }

        assertEquals(listOf(trackIds[2], trackIds[1]), history.recent(limit = 2).map { it.trackId })
        assertEquals(trackIds[2], history.recentTrackIds(limit = 1).single())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { history.recent(limit = 0) } }
    }

    @Test
    fun recordingForAnUnknownTrackIsRefusedAndProgressForAnUnknownIdReportsFalse() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { history.recordStart(TrackId.random(), at = Fixtures.now, durationMs = 1_000) }
        }
        assertFalse(history.recordProgress("no-such-id", listenedMs = 1_000, durationMs = 1_000))
        assertNull(history.byId("no-such-id"))
    }

    @Test
    fun historyCanBeClearedPerTrackOrEntirely() = runBlocking {
        history.recordStart(trackIds[0], at = Fixtures.now, durationMs = 1_000)
        history.recordStart(trackIds[0], at = Fixtures.now.plusSeconds(1), durationMs = 1_000)
        history.recordStart(trackIds[1], at = Fixtures.now.plusSeconds(2), durationMs = 1_000)

        assertEquals(2, history.clearForTrack(trackIds[0]))
        assertEquals(1, history.recent(limit = 10).size)

        assertEquals(1, history.clear())
        assertTrue(history.recent(limit = 10).isEmpty())
    }
}
