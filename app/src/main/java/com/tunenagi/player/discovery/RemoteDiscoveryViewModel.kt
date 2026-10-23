package com.tunenagi.player.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.RemoteCatalog
import com.tunenagi.core.model.RemotePage
import com.tunenagi.core.model.SearchQuery
import com.tunenagi.player.playback.NowPlayingSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a remote catalog screen is showing — owned (TUN-0305).
 *
 * The five states are the honest answers: nothing asked yet, the provider is answering, rows came
 * back, the provider served an empty page, or it refused for a typed reason. A spinner that never
 * resolves or a blank page that hides a failure is the failure mode this type exists to avoid, the
 * same way `ImportUiState` does for local folders. [Empty.received]/[Empty.skipped] are shown so a
 * "no results" page explains what the provider actually said.
 */
sealed interface RemoteCatalogUiState {
    data object Idle : RemoteCatalogUiState

    data object Loading : RemoteCatalogUiState

    data class Results(val rows: List<ExternalTrack>, val received: Int, val skipped: Int) : RemoteCatalogUiState

    data class Empty(val received: Int, val skipped: Int) : RemoteCatalogUiState

    /** [error] is a typed provider failure, not a guess: its message is a product string. */
    data class Error(val error: ProviderError) : RemoteCatalogUiState
}

/**
 * Search and trending — owned (TUN-0305).
 *
 * The ViewModel owns two flows because the two surfaces ask independently (Search of a term, Home
 * of whatever is trending); a shared one would drop the other tab's page mid-flight. The catalog is
 * the domain interface, never a provider type, per B7. One note on ordering: persistence and play
 * are checked exhaustively below because that is where verification happens first, not as an
 * afterthought.
 */
@HiltViewModel
class RemoteDiscoveryViewModel @Inject constructor(
    private val catalog: RemoteCatalog,
    private val playback: NowPlayingSource
) : ViewModel() {

    private val _searchState = MutableStateFlow<RemoteCatalogUiState>(RemoteCatalogUiState.Idle)
    val searchState: StateFlow<RemoteCatalogUiState> = _searchState.asStateFlow()

    private val _trendingState = MutableStateFlow<RemoteCatalogUiState>(RemoteCatalogUiState.Idle)
    val trendingState: StateFlow<RemoteCatalogUiState> = _trendingState.asStateFlow()

    /** The last accepted search: the retry button re-issues it instead of re-asking for input. */
    private var lastSearchText: String? = null

    /**
     * Runs [text] as a search.
     *
     * Re-entry is ignored rather than queued: two overlapping searches cannot both win, and the
     * field's IME action fires again while the first is in flight. A blank query clears back to
     * Idle because a search that was never asked for must not leave a stale page behind.
     */
    fun search(text: String) {
        if (text.isBlank()) {
            lastSearchText = null
            _searchState.value = RemoteCatalogUiState.Idle
            return
        }
        // Two overlapping searches cannot both win; re-entry is ignored rather than queued.
        if (_searchState.value == RemoteCatalogUiState.Loading) return
        lastSearchText = text
        // The flag is set synchronously, before the coroutine queues: a re-entry that
        // arrives while the first request is still waiting for its dispatcher must see it.
        _searchState.value = RemoteCatalogUiState.Loading
        start {
            when (val page = catalog.search(SearchQuery(text.trim()))) {
                is ProviderResult.Failure -> _searchState.value = RemoteCatalogUiState.Error(page.error)
                is ProviderResult.Success -> _searchState.value = presentPage(page.value)
            }
        }
    }

    /** Replays the last accepted query instead of re-asking for input; a no-op with no query. */
    fun retrySearch() {
        when (val state = _searchState.value) {
            is RemoteCatalogUiState.Error, is RemoteCatalogUiState.Empty -> {
                lastSearchText?.let { search(it) }
            }
            else -> return
        }
    }

    fun loadTrending() {
        if (_trendingState.value == RemoteCatalogUiState.Loading) return
        // Same synchronous flag as `search`: the re-entry guard must hold before the
        // coroutine even starts running.
        _trendingState.value = RemoteCatalogUiState.Loading
        start {
            when (val page = catalog.trending()) {
                is ProviderResult.Failure -> _trendingState.value = RemoteCatalogUiState.Error(page.error)
                is ProviderResult.Success -> _trendingState.value = presentPage(page.value)
            }
        }
    }

    /** Retry after an error, or to confirm a served-empty page was not a fluke. */
    fun retryTrending() {
        loadTrending()
    }

    /**
     * Puts [item] in the library, then hands its id to the session.
     *
     * The order follows the local importer: **persist first, then play** — if the session side
     * fails, the library row remains and a retry resolves the same track; playing first would be a
     * queue of ids nothing can resolve.
     */
    fun playRemoteTrack(item: ExternalTrack) {
        start {
            val ids = catalog.persist(listOf(item))
            if (ids.isNotEmpty()) playback.playTracks(ids)
        }
    }

    private fun start(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** One result screen renders an empty page and a page of rows exhaustively. */
    private fun presentPage(page: RemotePage): RemoteCatalogUiState = if (page.isEmpty) {
        RemoteCatalogUiState.Empty(received = page.received, skipped = page.skipped)
    } else {
        RemoteCatalogUiState.Results(
            rows = page.items,
            received = page.received,
            skipped = page.skipped
        )
    }
}
