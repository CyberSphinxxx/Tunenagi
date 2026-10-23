package com.tunenagi.player.discovery

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.RemoteCatalog
import com.tunenagi.core.model.RemotePage
import com.tunenagi.core.model.SearchQuery
import com.tunenagi.core.model.TrackId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The discovery presenter's contract — owned (TUN-0305).
 *
 * The catalog and the playback facade are doubles; what is under test is the same rule the local
 * importer is held to — **persist first, then play** — plus the five-state honesty contract: a
 * typed error, a served-empty page and real rows are distinguishable, and a spinner cannot outlive
 * its request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteDiscoveryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** A catalog that answers from a script; records the queries it was actually asked. */
    private class FakeCatalog(
        var searchResult: ProviderResult<RemotePage>,
        var trendingResult: ProviderResult<RemotePage>
    ) : RemoteCatalog {
        val searched = mutableListOf<String>()
        var persister: (List<ExternalTrack>) -> List<TrackId> = { it.map { _ -> TrackId.random() } }
        val persisted = mutableListOf<List<ExternalTrack>>()

        override suspend fun search(query: SearchQuery, limit: Int, offset: Int): ProviderResult<RemotePage> {
            searched += query.normalized
            return searchResult
        }

        override suspend fun trending(limit: Int, offset: Int): ProviderResult<RemotePage> = trendingResult

        override suspend fun persist(items: List<ExternalTrack>): List<TrackId> {
            persisted += items
            return persister(items)
        }
    }

    /** Constructed rows good enough for every state assertion; identity differentiation is key. */
    companion object {
        private fun row(id: String, availability: com.tunenagi.core.model.SourceAvailability) = ExternalTrack(
            providerId = ProviderId.Audius,
            externalId = id,
            title = "Track $id",
            artists = listOf("Artist $id"),
            availability = availability
        )

        private fun page(items: List<ExternalTrack>) = RemotePage(
            items = items,
            nextOffset = null,
            received = items.size,
            skipped = 0
        )
    }

    private class FakePlayback : NowPlayingSource {
        val played = mutableListOf<List<TrackId>>()

        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val connection = MutableStateFlow<NowPlayingSource.Connection>(
            NowPlayingSource.Connection.Connected
        )

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun togglePlayPause() = Unit

        override fun retry() = Unit

        override fun playTracks(trackIds: List<TrackId>) {
            played += trackIds
        }
    }

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSearchShowsRowsWithWhatTheProviderActuallyServed() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Success(page(listOf(row("1", com.tunenagi.core.model.SourceAvailability.Available)))),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.search("slow tide")
        advanceUntilIdle()

        assertEquals("slow tide", catalog.searched.single())
        val state = viewModel.searchState.value
        assertTrue("rows must be distinguishable from a failure or a spinner", state is RemoteCatalogUiState.Results)
        assertEquals(1, (state as RemoteCatalogUiState.Results).rows.size)
        assertEquals(1, state.received)
        assertEquals(0, state.skipped)
    }

    @Test
    fun aServedEmptyPageIsAnEmptyStateNotAnError() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Success(page(items = emptyList())),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.search("nothing anywhere")
        advanceUntilIdle()

        assertTrue(viewModel.searchState.value is RemoteCatalogUiState.Empty)
    }

    @Test
    fun aTypedProviderFailureBecomesAnErrorStateAndRetryReplaysTheQuery() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Failure(ProviderError.Timeout),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.search("slow tide")
        advanceUntilIdle()

        val error = viewModel.searchState.value
        assertTrue(error is RemoteCatalogUiState.Error)
        assertEquals(ProviderError.Timeout, (error as RemoteCatalogUiState.Error).error)

        catalog.searchResult = ProviderResult.Success(page(listOf(row("2", com.tunenagi.core.model.SourceAvailability.Available))))
        viewModel.retrySearch()
        advanceUntilIdle()

        assertEquals("the retry replays the same query rather than asking again", 2, catalog.searched.size)
        assertTrue(viewModel.searchState.value is RemoteCatalogUiState.Results)
    }

    @Test
    fun aBlankSearchClearsBackToIdleAndNeverTouchesTheProvider() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Success(page(emptyList())),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.search("   ")
        advanceUntilIdle()

        assertEquals(RemoteCatalogUiState.Idle, viewModel.searchState.value)
        assertTrue("no search call for a blank query", catalog.searched.isEmpty())
    }

    @Test
    fun aSecondSearchWhileOneIsRunningIsIgnored() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Success(page(emptyList())),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.search("first")
        viewModel.search("second")
        advanceUntilIdle()

        assertEquals("one in-flight search at a time", listOf("first"), catalog.searched)
    }

    @Test
    fun trendingLoadsIntoRowsAndRetryReloadsAfterAFailure() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Failure(ProviderError.NotFound),
            trendingResult = ProviderResult.Failure(ProviderError.Timeout)
        )
        val viewModel = RemoteDiscoveryViewModel(catalog, FakePlayback())

        viewModel.loadTrending()
        advanceUntilIdle()

        assertTrue(viewModel.trendingState.value is RemoteCatalogUiState.Error)

        catalog.trendingResult = ProviderResult.Success(page(listOf(row("7", com.tunenagi.core.model.SourceAvailability.Available))))
        viewModel.retryTrending()
        advanceUntilIdle()

        val state = viewModel.trendingState.value
        assertTrue(state is RemoteCatalogUiState.Results)
        assertEquals(1, (state as RemoteCatalogUiState.Results).rows.size)
    }

    @Test
    fun playPersistsFirstThenHandsTheIdToTheSession() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Failure(ProviderError.NotFound),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val playback = FakePlayback()
        val expected = listOf(TrackId.random())
        catalog.persister = { expected }
        val viewModel = RemoteDiscoveryViewModel(catalog, playback)

        val item = row("9", com.tunenagi.core.model.SourceAvailability.Restricted)
        viewModel.playRemoteTrack(item)
        advanceUntilIdle()

        assertEquals("the tapped row itself is what was persisted", listOf(item), catalog.persisted.single())
        assertEquals(expected, playback.played.single())
    }

    @Test
    fun aRowThatPersistsToNothingStartsNoPlayback() = runTest(dispatcher) {
        val catalog = FakeCatalog(
            searchResult = ProviderResult.Failure(ProviderError.NotFound),
            trendingResult = ProviderResult.Failure(ProviderError.NotFound)
        )
        val playback = FakePlayback()
        catalog.persister = { emptyList() }
        val viewModel = RemoteDiscoveryViewModel(catalog, playback)

        viewModel.playRemoteTrack(row("10", com.tunenagi.core.model.SourceAvailability.Available))
        advanceUntilIdle()

        assertTrue("empty persist must not hand the session an empty play", playback.played.isEmpty())
    }
}
