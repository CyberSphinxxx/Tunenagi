package com.tunenagi.core.library

import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
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
 * The persisted half of matching: a confirmation and a lock have to survive a restart and beat any
 * score, otherwise "manual overrides trump heuristics" is only true inside one process.
 */
@RunWith(RobolectricTestRunner::class)
class SourceMatchRepositoryTest {

    private lateinit var db: TunenagiDatabase
    private lateinit var repository: SourceMatchRepository
    private val clock = { Fixtures.now }

    private val trackId = TrackId.random()

    @Before
    fun setUp() = runBlocking {
        db = Fixtures.database()
        repository = RoomSourceMatchRepository(db, clock)
        db.trackDao().insert(
            TunenagiMappers.toTrackEntity(
                Track(id = trackId, title = "The Slow Tide", durationMs = 190_000),
                Fixtures.now,
                Fixtures.now
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun candidate(externalId: String, durationMs: Long? = 190_000) = ExternalTrack(
        providerId = ProviderId.Audius,
        externalId = externalId,
        title = "The Slow Tide",
        artists = listOf("Kaori Ito"),
        albumName = "Dawn",
        durationMs = durationMs
    )

    private suspend fun addSource(
        externalId: String,
        preferred: Boolean = false,
        availability: SourceAvailability = SourceAvailability.Available
    ) =
        TunenagiMappers.toTrackSourceEntity(
            Fixtures.source(trackId, externalId = externalId, availability = availability).copy(preferred = preferred)
        ).also { db.sourceDao().insert(it) }

    @Test
    fun aConfirmationIsPersistedAsManualAndSurvivesAReload() = runBlocking {
        repository.confirmMatch(trackId, ProviderId.Audius, "chosen", score = 0.72, at = Fixtures.now)

        val stored = repository.confirmedMatch(trackId)
        assertNotNull(stored)
        assertTrue("a user decision is manual, not a suggestion", stored!!.manuallyConfirmed)
        assertEquals("chosen", stored.externalId)

        // A fresh repository over the same database sees the same confirmation.
        val reloaded = RoomSourceMatchRepository(db, clock).confirmedMatch(trackId)
        assertEquals(stored, reloaded)
    }

    @Test
    fun confirmingAMatchAlsoLocksTheStoredSourceForThatProviderRow() = runBlocking {
        addSource("chosen")

        repository.confirmMatch(trackId, ProviderId.Audius, "chosen", score = 0.7, at = Fixtures.now)

        val locked = repository.lockedSource(trackId)
        assertNotNull("the chosen source is locked", locked)
        assertEquals("chosen", locked!!.externalId)
        assertTrue(locked.locked)
    }

    /** The whole point of a lock: a better-scoring alternative cannot move the user's choice. */
    @Test
    fun aLockedSourceOutranksAPreferredOne() = runBlocking {
        addSource("machine-pick", preferred = true)
        val chosen = addSource("user-pick")

        repository.setSourceLocked(com.tunenagi.core.model.TrackSourceId.parse(chosen.id), true)

        val preferred = repository.preferredSource(trackId)
        assertEquals("user-pick", preferred!!.externalId)
        assertTrue(preferred.locked)
    }

    @Test
    fun withNoLockTheStoredPreferenceIsUsed() = runBlocking {
        addSource("first")
        addSource("second", preferred = true)

        assertEquals("second", repository.preferredSource(trackId)!!.externalId)
    }

    @Test
    fun aTrackWithNoSourcesHasNoPreferredSource() = runBlocking {
        assertNull(repository.preferredSource(trackId))
        assertNull(repository.lockedSource(trackId))
    }

    @Test
    fun aStoredConfirmationTurnsAnAmbiguousResolveIntoALock() = runBlocking {
        val query = SourceMatcher.Query(title = "The Slow Tide", artists = listOf("Kaori Ito"), durationMs = 190_000)
        val candidates = listOf(candidate("first"), candidate("second"))

        val before = repository.resolveForTrack(trackId, query, candidates)
        val ambiguous = before is SourceMatcher.Resolution.Accepted ||
            before is SourceMatcher.Resolution.NeedsChoice
        assertTrue("two perfect candidates need a user choice", ambiguous)

        repository.confirmMatch(trackId, ProviderId.Audius, "second", score = 0.5, at = Fixtures.now)
        val after = repository.resolveForTrack(trackId, query, candidates)

        assertTrue(after is SourceMatcher.Resolution.Locked)
        assertEquals("second", (after as SourceMatcher.Resolution.Locked).confirmed.external.externalId)
    }

    @Test
    fun forgettingAMatchRemovesTheLockToo() = runBlocking {
        repository.confirmMatch(trackId, ProviderId.Audius, "chosen", score = 0.7, at = Fixtures.now)
        assertNotNull(repository.confirmedMatch(trackId))

        repository.forgetMatch(trackId)

        assertNull(repository.confirmedMatch(trackId))
        val resolution = repository.resolveForTrack(
            trackId,
            SourceMatcher.Query("The Slow Tide", listOf("Kaori Ito"), durationMs = 190_000),
            listOf(candidate("chosen"))
        )
        assertTrue(resolution !is SourceMatcher.Resolution.Locked)
    }

    @Test
    fun anAvailabilityChangeOnTheLockedSourceDoesNotUnlockIt() = runBlocking {
        val source = addSource("chosen")
        repository.setSourceLocked(com.tunenagi.core.model.TrackSourceId.parse(source.id), true)

        db.sourceDao().updateAvailability(source.id, SourceAvailability.Restricted.name, Fixtures.now.toEpochMilli())

        val locked = repository.lockedSource(trackId)
        assertEquals("chosen", locked!!.externalId)
        assertEquals(SourceAvailability.Restricted, locked.availability)
    }

    @Test
    fun lockingASourceThatDoesNotExistIsRefused() = runBlocking {
        val missing = com.tunenagi.core.model.TrackSourceId.random()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setSourceLocked(missing, true) }
        }
        assertTrue(thrown.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun confirmingAMatchForATrackThatDoesNotExistIsRefusedByTheDatabase() = runBlocking {
        val stranger = TrackId.random()
        assertThrows(Throwable::class.java) {
            runBlocking { repository.confirmMatch(stranger, ProviderId.Audius, "x", score = 0.5, at = Fixtures.now) }
        }
        assertNull(repository.confirmedMatch(stranger))
    }

    @Test
    fun sourcesForListsTheTracksSourcesPreferredFirst() = runBlocking {
        addSource("machine-pick")
        addSource("user-pick", preferred = true)

        val listed = repository.sourcesFor(trackId)

        assertEquals(listOf("user-pick", "machine-pick"), listed.map { it.externalId })
    }

    @Test
    fun choosingASourceMakesItTheOnlyPreferredAndTheOnlyLockedOne() = runBlocking {
        addSource("first", preferred = true)
        val second = addSource("second")

        val chosen = repository.chooseSource(trackId, TrackSourceId.parse(second.id))

        assertTrue("a choice is both preferred and locked", chosen.preferred && chosen.locked)
        val listed = repository.sourcesFor(trackId)
        assertEquals("second", listed.first().externalId)
        val first = listed.first { it.externalId == "first" }
        assertTrue("two choices cannot both be 'the' choice", !first.preferred && !first.locked)
        assertEquals("second", repository.preferredSource(trackId)!!.externalId)

        // A second choice moves the lock rather than accumulating one.
        repository.chooseSource(trackId, first.id)
        val afterMove = repository.sourcesFor(trackId)
        assertEquals("first", afterMove.first().externalId)
        assertEquals(1, afterMove.count { it.locked })
        assertTrue(afterMove.none { it.externalId == "second" && it.locked })
    }

    @Test
    fun choosingAMissingOrForeignSourceIsRefusedAndChangesNothing() = runBlocking {
        addSource("mine")
        val strangerTrack = TrackId.random()
        db.trackDao().insert(
            TunenagiMappers.toTrackEntity(Track(id = strangerTrack, title = "Another Track"), Fixtures.now, Fixtures.now)
        )
        val foreign = TunenagiMappers.toTrackSourceEntity(Fixtures.source(strangerTrack, externalId = "theirs"))
        db.sourceDao().insert(foreign)

        val missing = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.chooseSource(trackId, TrackSourceId.random()) }
        }
        assertTrue(missing.message.orEmpty().contains("does not exist"))

        val crossed = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.chooseSource(trackId, TrackSourceId.parse(foreign.id)) }
        }
        assertTrue(crossed.message.orEmpty().contains("belongs to track"))

        assertTrue("a refusal must change nothing", repository.sourcesFor(trackId).none { it.preferred || it.locked })
    }
}
