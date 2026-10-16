package com.tunenagi.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import com.google.common.util.concurrent.ListenableFuture
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.database.TrackSourceEntity
import com.tunenagi.core.model.PlayableReference
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Resolving a media item out of the library, against a real Room database on the JVM.
 *
 * The database is real (the same in-memory Room build the schema tests use), the media ids are the
 * real convention, and the resolution is driven exactly as the session drives it — a batch of items
 * in, a future out. The only thing supplied is the *provider's* answer, through a
 * [PlaybackSourceResolver] stub, because the local adapter is tested in its own package and because
 * `core.playback` must not know a provider exists (rule B11 keeps the dependency pointing one way).
 *
 * Not proven here, and stated instead of implied: that a real decoder renders the resolved uri. That
 * needs an Android runtime this project does not have (ADR-008).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryMediaItemResolutionTest {

    private val at: Instant = Instant.parse("2026-10-16T00:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A resolver that answers with whatever the test set, and records what it was asked. */
    private class StubResolver(
        override val providerId: ProviderId = ProviderId.Local,
        var answer: ResolveResult = ResolveResult.Failed(ProviderError.NotFound)
    ) : PlaybackSourceResolver {

        val calls = mutableListOf<Pair<Track, TrackSource>>()

        override suspend fun resolve(track: Track, source: TrackSource): ResolveResult {
            calls += track to source
            return answer
        }
    }

    private suspend fun seed(
        database: TunenagiDatabase,
        availability: SourceAvailability = SourceAvailability.Available,
        contentType: String? = "audio/mpeg",
        externalId: String = "7",
        uri: String = MEDIA_URI
    ): TrackSourceEntity {
        val track = Track(
            id = TrackId.random(),
            title = "Slow Tide",
            durationMs = 190_000,
            artworkRef = ARTWORK
        )
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
        val source = TrackSource(
            id = TrackSourceId.random(),
            trackId = track.id,
            providerId = ProviderId.Local,
            // The database's unique identity is (provider, external id, variant), so a second file
            // in the same grant must be a *different* row — which is what the index test asserts too.
            externalId = externalId,
            localUri = uri,
            sourceVariant = "mediastore",
            contentType = contentType,
            availability = availability,
            lastCheckedAt = at
        )
        database.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(source))
        return database.sourceDao().byId(source.id.value)!!
    }

    private fun resolution(database: TunenagiDatabase, vararg resolvers: PlaybackSourceResolver) =
        LibraryMediaItemResolution(
            database = database,
            resolvers = resolvers.toList(),
            scope = scope,
            clock = { at }
        )

    private fun item(sourceId: String): MediaItem =
        MediaItem.Builder().setMediaId(MediaItemIds.source(TrackSourceId.parse(sourceId))).build()

    private fun <T> ListenableFuture<T>.value(): T = get(5, TimeUnit.SECONDS)

    private fun <T> ListenableFuture<T>.failure(): Throwable =
        try {
            get(5, TimeUnit.SECONDS)
            throw AssertionError("expected the request to fail, but it succeeded")
        } catch (expected: ExecutionException) {
            expected.cause ?: expected
        }

    // ---- the happy path --------------------------------------------------------------------------

    @Test
    fun aSourceIdResolvesToAPlayableItemBuiltFromTheLibrary() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(
                PlayableReference(MEDIA_URI, mimeType = "audio/flac", sourceId = TrackSourceId.parse(stored.id))
            )
        )
        val requested = item(stored.id)

        val resolved = resolution(database, resolver).resolve(listOf(requested)).value().single()

        assertEquals(requested.mediaId, resolved.mediaId)
        assertEquals(MEDIA_URI, resolved.localConfiguration!!.uri.toString())
        assertEquals("audio/flac", resolved.localConfiguration!!.mimeType)
        // The title comes from the library, not from the caller: only the id and the metadata cross
        // the process boundary, so the database is the authority on what the track is called.
        assertEquals("Slow Tide", resolved.mediaMetadata.title.toString())
        assertEquals(Uri.parse(ARTWORK), resolved.mediaMetadata.artworkUri)
        // The item is playable, and it is not a node in a browsable tree: the flag is left unset
        // rather than claimed, which is what a system browser would otherwise render as a folder.
        assertEquals(java.lang.Boolean.TRUE, resolved.mediaMetadata.isPlayable)
        assertNull(resolved.mediaMetadata.isBrowsable)
        assertEquals(
            "the resolver must be asked about this exact source",
            stored.id,
            resolver.calls.single().second.id.value
        )
        assertEquals("Slow Tide", resolver.calls.single().first.title)
    }

    @Test
    fun aBatchOfLibraryItemsResolvesIntoTheSameOrder() = runBlocking {
        val database = Fixtures.database()
        val first = seed(database, externalId = "7")
        val second = seed(database, externalId = "8", uri = "content://media/external/audio/media/8")
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(
                PlayableReference(MEDIA_URI, "audio/mpeg", TrackSourceId.parse(first.id))
            )
        )

        val resolved = resolution(database, resolver)
            .resolve(listOf(item(first.id), item(second.id)))
            .value()

        assertEquals(2, resolved.size)
        assertEquals(MediaItemIds.source(TrackSourceId.parse(first.id)), resolved[0].mediaId)
        assertEquals(MediaItemIds.source(TrackSourceId.parse(second.id)), resolved[1].mediaId)
    }

    /** A file that had been marked gone and is playable again is put back, not left unplayable. */
    @Test
    fun aSuccessfulResolveRecordsTheSniffedFormatAndRestoresAvailability() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database, availability = SourceAvailability.StorageGone, contentType = "audio/mpeg")
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(PlayableReference(MEDIA_URI, "audio/flac", TrackSourceId.parse(stored.id)))
        )

        resolution(database, resolver).resolve(listOf(item(stored.id))).value()

        val after = database.sourceDao().byId(stored.id)!!
        assertEquals(SourceAvailability.Available.name, after.availability)
        // The declared type was wrong and the bytes won; the library keeps the honest one.
        assertEquals("audio/flac", after.contentType)
        assertEquals(at.toEpochMilli(), after.lastCheckedAt)
    }

    // ---- failure paths ---------------------------------------------------------------------------

    @Test
    fun anUnsupportedContainerFailsTheRequestWithTheTypedProviderError() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)
        val resolver = StubResolver(answer = ResolveResult.Failed(ProviderError.UnsupportedFormat))

        val failure = resolution(database, resolver).resolve(listOf(item(stored.id))).failure()

        val unresolved = failure as UnresolvedMediaItemException
        assertEquals(MediaItemIds.source(TrackSourceId.parse(stored.id)), unresolved.mediaId)
        assertEquals(ProviderError.UnsupportedFormat, unresolved.error)
        assertNull(unresolved.availability)
        // A format this app cannot play is not a reason to hide the track: nothing was rewritten.
        assertEquals(
            SourceAvailability.Available.name,
            database.sourceDao().byId(stored.id)!!.availability
        )
    }

    @Test
    fun aFileThatIsGoneIsMarkedStorageGoneAndKeepsItsTrack() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)
        val resolver = StubResolver(answer = ResolveResult.NeedsUserAction(SourceAvailability.StorageGone))

        val failure = resolution(database, resolver).resolve(listOf(item(stored.id))).failure()

        assertEquals(SourceAvailability.StorageGone, (failure as UnresolvedMediaItemException).availability)
        assertEquals(
            SourceAvailability.StorageGone.name,
            database.sourceDao().byId(stored.id)!!.availability
        )
        assertTrue("the track survives its file", database.trackDao().byId(stored.trackId) != null)

        // Asking again does not rewrite the row: a track that keeps failing does not churn.
        val checkedAt = database.sourceDao().byId(stored.id)!!.lastCheckedAt
        resolution(database, resolver).resolve(listOf(item(stored.id))).failure()
        assertEquals(checkedAt, database.sourceDao().byId(stored.id)!!.lastCheckedAt)
    }

    @Test
    fun aRevokedGrantIsReportedAsNeedingThePermissionBack() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)
        val resolver = StubResolver(answer = ResolveResult.NeedsUserAction(SourceAvailability.PermissionRequired))

        val failure = resolution(database, resolver).resolve(listOf(item(stored.id))).failure()

        assertEquals(
            SourceAvailability.PermissionRequired,
            (failure as UnresolvedMediaItemException).availability
        )
        assertEquals(
            SourceAvailability.PermissionRequired.name,
            database.sourceDao().byId(stored.id)!!.availability
        )
    }

    @Test
    fun aMediaIdThatIsNotASourceIdIsRejectedWithoutTouchingTheLibrary() = runBlocking {
        val database = Fixtures.database()
        val resolver = StubResolver()
        val requested = MediaItem.Builder().setMediaId("local:7").build()

        val failure = resolution(database, resolver).resolve(listOf(requested)).failure()

        assertTrue((failure as UnresolvedMediaItemException).source!!.contains("not a Tunenagi source id"))
        assertTrue("nothing was resolved for an id this app did not issue", resolver.calls.isEmpty())
    }

    @Test
    fun aSourceIdThatIsNotInTheLibraryIsNotFoundRatherThanGuessed() = runBlocking {
        val database = Fixtures.database()
        val resolver = StubResolver()
        val requested = item(TrackSourceId.random().value)

        val failure = resolution(database, resolver).resolve(listOf(requested)).failure()

        assertTrue((failure as UnresolvedMediaItemException).source!!.contains("not in the library"))
        assertTrue(resolver.calls.isEmpty())
    }

    @Test
    fun aSourceWhoseProviderHasNoInstalledResolverFailsTyped() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)

        val failure = resolution(database).resolve(listOf(item(stored.id))).failure()

        assertTrue(
            (failure as UnresolvedMediaItemException).source!!.contains("no resolver is installed")
        )
    }

    @Test
    fun oneUnresolvableItemFailsTheWholeRequest() = runBlocking {
        val database = Fixtures.database()
        val stored = seed(database)
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(PlayableReference(MEDIA_URI, "audio/mpeg", TrackSourceId.parse(stored.id)))
        )

        val failure = resolution(database, resolver)
            .resolve(listOf(item(stored.id), MediaItem.Builder().setMediaId("tunenagi:source:nonsense").build()))
            .failure()

        assertTrue(failure is UnresolvedMediaItemException)
    }

    // ---- pass-through and id convention ----------------------------------------------------------

    @Test
    fun anItemThatAlreadyCarriesAUriIsPassedThroughWithoutAResolver() = runBlocking {
        val database = Fixtures.database()
        val resolver = StubResolver()
        val requested = MediaItem.Builder().setMediaId("local:1").setUri("file:///music/fixture.mp3").build()

        val resolved = resolution(database, resolver).resolve(listOf(requested)).value().single()

        assertEquals(requested, resolved)
        assertTrue("an item with a uri needs no lookup at all", resolver.calls.isEmpty())
    }

    @Test
    fun theMediaIdConventionRoundTripsAndRejectsForeignIds() {
        val id = TrackSourceId.random()
        val trackId = TrackId.random()

        assertEquals(id, MediaItemIds.sourceIdOrNull(MediaItemIds.source(id)))
        assertEquals("tunenagi:source:" + id.value, MediaItemIds.source(id))
        assertEquals(trackId, MediaItemIds.trackIdOrNull(MediaItemIds.track(trackId)))
        assertEquals("tunenagi:track:" + trackId.value, MediaItemIds.track(trackId))
        assertNull(MediaItemIds.sourceIdOrNull(null))
        assertNull(MediaItemIds.sourceIdOrNull(""))
        assertNull(MediaItemIds.sourceIdOrNull("local:1"))
        assertNull(MediaItemIds.sourceIdOrNull("tunenagi:source:not-a-uuid"))
        assertNull("a bare uuid is not an id this app issued", MediaItemIds.sourceIdOrNull(id.value))
        // The two kinds must not be confused for one another, or a queue item would resolve as
        // whichever id happens to parse first.
        assertNull(MediaItemIds.trackIdOrNull(MediaItemIds.source(id)))
        assertNull(MediaItemIds.sourceIdOrNull(MediaItemIds.track(trackId)))
    }

    // ---- track-level ids and the source a track plays through -------------------------------------

    /**
     * A queue holds tracks, not sources (`SPEC/DATA_MODEL.md`), so a track-level media id has to
     * choose a source at resolve time - and the chosen one is what the resolver is asked about.
     */
    private suspend fun seedTwoSources(
        preferredLocked: Boolean?,
        preferredAvailability: SourceAvailability,
        otherAvailability: SourceAvailability
    ): Pair<TunenagiDatabase, String> {
        val database = Fixtures.database()
        val track = Track(id = TrackId.random(), title = "Slow Tide", durationMs = 190_000)
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
        val sources = listOf(
            TrackSource(
                id = TrackSourceId.random(),
                trackId = track.id,
                providerId = ProviderId.Local,
                externalId = "7",
                localUri = MEDIA_URI,
                sourceVariant = "mediastore",
                contentType = "audio/mpeg",
                availability = preferredAvailability,
                preferred = preferredLocked != null,
                locked = preferredLocked == true
            ),
            TrackSource(
                id = TrackSourceId.random(),
                trackId = track.id,
                providerId = ProviderId.Local,
                externalId = "8",
                localUri = "content://media/external/audio/media/8",
                sourceVariant = "mediastore",
                contentType = "audio/mpeg",
                availability = otherAvailability,
                lastCheckedAt = at
            )
        )
        sources.forEach { database.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(it)) }
        return database to track.id.value
    }

    @Test
    fun aTrackIdResolvesThroughItsPreferredSource() = runBlocking {
        val (database, trackId) = seedTwoSources(
            preferredLocked = false,
            preferredAvailability = SourceAvailability.Available,
            otherAvailability = SourceAvailability.Available
        )
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(PlayableReference(MEDIA_URI, "audio/mpeg", TrackSourceId.random()))
        )
        val requested = MediaItem.Builder().setMediaId(MediaItemIds.track(TrackId.parse(trackId))).build()

        val resolved = resolution(database, resolver).resolve(listOf(requested)).value().single()

        assertEquals(trackId, MediaItemIds.trackIdOrNull(resolved.mediaId)!!.value)
        assertEquals("the preferred source is the one asked about", "7", resolver.calls.single().second.externalId)
    }

    @Test
    fun anUnavailablePreferredSourceFallsBackToAnotherCopyOfTheSameRecording() = runBlocking {
        val (database, trackId) = seedTwoSources(
            preferredLocked = false,
            preferredAvailability = SourceAvailability.StorageGone,
            otherAvailability = SourceAvailability.Available
        )
        val resolver = StubResolver(
            answer = ResolveResult.Resolved(
                PlayableReference("content://media/external/audio/media/8", "audio/mpeg", TrackSourceId.random())
            )
        )
        val requested = MediaItem.Builder().setMediaId(MediaItemIds.track(TrackId.parse(trackId))).build()

        resolution(database, resolver).resolve(listOf(requested)).value()

        assertEquals("8", resolver.calls.single().second.externalId)
    }

    /**
     * A locked source is the user's explicit choice (TUN-0104). Reporting it as unplayable is
     * honest; quietly playing a different copy is the substitution `SPEC/PROVIDERS.md` forbids.
     */
    @Test
    fun aSourceTheUserLockedIsUsedEvenWhenAnotherCopyIsAvailable() = runBlocking {
        val (database, trackId) = seedTwoSources(
            preferredLocked = true,
            preferredAvailability = SourceAvailability.StorageGone,
            otherAvailability = SourceAvailability.Available
        )
        val resolver = StubResolver(answer = ResolveResult.NeedsUserAction(SourceAvailability.StorageGone))
        val requested = MediaItem.Builder().setMediaId(MediaItemIds.track(TrackId.parse(trackId))).build()

        val failure = resolution(database, resolver).resolve(listOf(requested)).failure()

        assertEquals("7", resolver.calls.single().second.externalId)
        assertEquals(SourceAvailability.StorageGone, (failure as UnresolvedMediaItemException).availability)
    }

    @Test
    fun aTrackWithNoSourceAtAllFailsTypedInsteadOfGuessingOne() = runBlocking {
        val database = Fixtures.database()
        val track = Track(id = TrackId.random(), title = "Sourceless")
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
        val resolver = StubResolver()
        val requested = MediaItem.Builder().setMediaId(MediaItemIds.track(track.id)).build()

        val failure = resolution(database, resolver).resolve(listOf(requested)).failure()

        assertTrue((failure as UnresolvedMediaItemException).source!!.contains("no source in the library"))
        assertTrue(resolver.calls.isEmpty())
    }

    private companion object {
        const val MEDIA_URI = "content://media/external/audio/media/7"
        const val ARTWORK = "content://media/external/audio/albumart/3"
    }
}
