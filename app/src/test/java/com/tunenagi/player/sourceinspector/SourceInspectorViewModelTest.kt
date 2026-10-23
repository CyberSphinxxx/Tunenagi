package com.tunenagi.player.sourceinspector

import com.tunenagi.core.library.SourceMatchRepository
import com.tunenagi.core.library.SourceMatcher
import com.tunenagi.core.model.MetadataMatch
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.playback.MediaItemIds
import com.tunenagi.player.playback.NowPlaying
import com.tunenagi.player.playback.NowPlayingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The inspector's contract — owned (TUN-0307).
 *
 * The repository and the session are doubles; what is under test is what the task promises: the
 * screen keys to the *current* item and to nothing else, every source arrives with its identity,
 * its typed status and the format the source itself reported, playback's unavailable choice is
 * called out, a chosen source is written through the repository (so it persists), and an id the
 * rows never contained is refused **before** any write is attempted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceInspectorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val trackId = TrackId.random()
    private val audiusSourceId = TrackSourceId.random()
    private val localSourceId = TrackSourceId.random()

    /** An in-memory repository with the same rules the Room one enforces. */
    private class FakeRepository : SourceMatchRepository {
        var sources: List<TrackSource> = emptyList()
        var chooseCalls = 0
        var lockWrites = 0

        override suspend fun confirmedMatch(trackId: TrackId): MetadataMatch? = null

        override suspend fun confirmMatch(
            trackId: TrackId,
            providerId: ProviderId,
            externalId: String,
            score: Double,
            at: java.time.Instant
        ): MetadataMatch = error("not used by this suite")

        override suspend fun forgetMatch(trackId: TrackId): Unit = error("not used by this suite")

        override suspend fun setSourceLocked(sourceId: TrackSourceId, locked: Boolean) {
            lockWrites++
            sources = sources.map { if (it.id == sourceId) it.copy(locked = locked) else it }
        }

        override suspend fun lockedSource(trackId: TrackId): TrackSource? =
            sources.firstOrNull { it.locked }

        override suspend fun sourcesFor(trackId: TrackId): List<TrackSource> = sources

        override suspend fun chooseSource(trackId: TrackId, sourceId: TrackSourceId): TrackSource {
            chooseCalls++
            val target = sources.firstOrNull { it.id == sourceId }
                ?: throw IllegalArgumentException("Cannot choose a source that does not exist: $sourceId")
            sources = sources.map { it.copy(preferred = it.id == sourceId, locked = it.id == sourceId) }
            return sources.first { it.id == target.id }
        }

        override suspend fun preferredSource(trackId: TrackId): TrackSource? =
            sources.firstOrNull { it.locked } ?: sources.firstOrNull { it.preferred }

        override suspend fun resolveForTrack(
            trackId: TrackId,
            query: SourceMatcher.Query,
            candidates: List<com.tunenagi.core.model.ExternalTrack>
        ): SourceMatcher.Resolution = error("not used by this suite")
    }

    private class FakePlayback : NowPlayingSource {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val connection = MutableStateFlow<NowPlayingSource.Connection>(
            NowPlayingSource.Connection.Closed
        )

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun togglePlayPause() = Unit

        override fun playTracks(trackIds: List<TrackId>) = Unit

        override fun retry() = Unit
    }

    private val repository = FakeRepository()
    private val playback = FakePlayback()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SourceInspectorViewModel(repository, playback)

    private fun playing(mediaId: String?) {
        playback.nowPlaying.value = mediaId?.let {
            NowPlaying(mediaId = it, title = "Any title", artist = null, isPlaying = true)
        }
    }

    private fun trackMediaId() = MediaItemIds.TRACK_PREFIX + trackId.value

    private fun audiusSource(
        availability: SourceAvailability = SourceAvailability.Available,
        preferred: Boolean = true,
        locked: Boolean = false
    ) = TrackSource(
        id = audiusSourceId,
        trackId = trackId,
        providerId = ProviderId.Audius,
        externalId = "GJY7l",
        contentType = "audio/mpeg",
        availability = availability,
        preferred = preferred,
        locked = locked
    )

    private fun localSource() = TrackSource(
        id = localSourceId,
        trackId = trackId,
        providerId = ProviderId.Local,
        externalId = "media-9",
        localUri = "content://media/external/audio/media/9",
        availability = SourceAvailability.StorageGone
    )

    @Test
    fun nothingPlayingShowsNoTrackRatherThanAnEmptyList() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(SourceInspectorState.NoTrack, vm.state.value)
    }

    @Test
    fun aPlayingTrackShowsEverySourceWithItsIdentityStatusAndKnownFormat() = runTest {
        repository.sources = listOf(audiusSource(), localSource())
        val vm = viewModel()
        playing(trackMediaId())
        advanceUntilIdle()

        val loaded = vm.state.value as SourceInspectorState.Loaded
        assertEquals(2, loaded.rows.size)

        val audius = loaded.rows.first { it.providerId == ProviderId.Audius.value }
        assertEquals(audiusSourceId.value, audius.sourceId)
        assertEquals(SourceStatus.Playable, audius.status)
        assertEquals("audio/mpeg", audius.format)
        assertTrue(audius.isPreferred)

        val local = loaded.rows.first { it.providerId == ProviderId.Local.value }
        assertEquals(SourceStatus.FileMissing, local.status)
        assertNull("a format the source never reported stays null", local.format)
        assertTrue(!local.isPreferred && !local.isLocked)
    }

    @Test
    fun theChoicePlaybackWouldMakeIsCalledOutWhenItIsUnavailable() = runTest {
        repository.sources = listOf(audiusSource(availability = SourceAvailability.Restricted, locked = true))
        val vm = viewModel()
        playing(trackMediaId())
        advanceUntilIdle()

        val loaded = vm.state.value as SourceInspectorState.Loaded
        val feedback = loaded.unavailableFeedback
        assertEquals(audiusSourceId.value, feedback?.sourceId)
        assertEquals(SourceStatus.Restricted, feedback?.status)
    }

    @Test
    fun choosingASourceWritesItLocksItAndSaysSo() = runTest {
        repository.sources = listOf(
            audiusSource(preferred = true),
            localSource()
        )
        val vm = viewModel()
        playing(trackMediaId())
        advanceUntilIdle()

        vm.choose(localSourceId.value)
        advanceUntilIdle()

        assertEquals(1, repository.chooseCalls)
        val loaded = vm.state.value as SourceInspectorState.Loaded
        val local = loaded.rows.first { it.providerId == ProviderId.Local.value }
        assertTrue("the choice is now both preferred and locked", local.isPreferred && local.isLocked)
        val audius = loaded.rows.first { it.providerId == ProviderId.Audius.value }
        assertTrue("exactly one choice remains", !audius.isPreferred && !audius.isLocked)
        assertEquals(Notice.Chosen(ProviderId.Local.value), loaded.notice)
    }

    @Test
    fun choosingAnIdThatIsNotOnScreenRefusesWithoutTouchingTheRepository() = runTest {
        repository.sources = listOf(audiusSource())
        val vm = viewModel()
        playing(trackMediaId())
        advanceUntilIdle()

        vm.choose("a-source-that-was-never-listed")
        advanceUntilIdle()

        assertEquals(0, repository.chooseCalls)
        val loaded = vm.state.value as SourceInspectorState.Loaded
        assertEquals(ActionFailure.SourceNotListed, loaded.failure)
        assertNull(loaded.notice)
    }

    @Test
    fun aMediaIdThatIsNotALibraryTrackSaysSo() = runTest {
        val vm = viewModel()
        playing(MediaItemIds.SOURCE_PREFIX + "00000000-0000-0000-0000-000000000000")
        advanceUntilIdle()

        assertEquals(SourceInspectorState.NotALibraryTrack, vm.state.value)
    }

    @Test
    fun unlockingKeepsTheSourcePreferredAndReportsIt() = runTest {
        repository.sources = listOf(audiusSource(locked = true, preferred = true))
        val vm = viewModel()
        playing(trackMediaId())
        advanceUntilIdle()

        vm.unlock(audiusSourceId.value)
        advanceUntilIdle()

        assertEquals(1, repository.lockWrites)
        val loaded = vm.state.value as SourceInspectorState.Loaded
        val audius = loaded.rows.single()
        assertTrue("the unlocked source stays preferred", audius.isPreferred)
        assertTrue(!audius.isLocked)
        assertEquals(Notice.Unlocked(ProviderId.Audius.value), loaded.notice)
        assertNull(loaded.unavailableFeedback)
    }
}
