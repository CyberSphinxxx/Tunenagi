package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.SearchQuery
import com.tunenagi.player.R
import com.tunenagi.player.discovery.RemoteCatalogUiState
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val SEARCH_SCREEN_TAG = "screen-search"
const val SEARCH_FIELD_TAG = "search-field"
const val SEARCH_RESULTS_TAG = "search-results"
const val SEARCH_ROW_TAG = "remote-result-row"
const val SEARCH_LOADING_TAG = "search-state-loading"
const val SEARCH_ERROR_RETRY_TAG = "search-retry"

/**
 * Search — owned (TUN-0305).
 *
 * The query field is real state that survives rotation, and results are whatever the remote
 * discovery state machine says: typed reason on an error, an honest empty page, or rows each of
 * which is a real action. Rows carry what the provider offered the track, never stream URLs.
 */
@Composable
fun SearchScreen(
    state: RemoteCatalogUiState,
    modifier: Modifier = Modifier,
    onSearchQuery: (String) -> Unit = {},
    onRetrySearch: () -> Unit = {},
    onPlayRemoteTrack: (ExternalTrack) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TunenagiSpacing.LgDp.dp)
            .testTag(SEARCH_SCREEN_TAG)
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium
        )
        OutlinedTextField(
            value = query,
            // A query at the catalog length limit only ever is a blank page from the
            // provider; stopping here is the honest clamping of what the user can type.
            onValueChange = { text -> if (text.length <= SearchQuery.MAX_LENGTH) query = text },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TunenagiSpacing.MdDp.dp)
                .testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text(stringResource(R.string.search_label)) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        StateContent(
            state = state,
            onSearchQuery = onSearchQuery,
            onRetrySearch = onRetrySearch,
            onPlayRemoteTrack = onPlayRemoteTrack,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The five states a search is actually in; rendered exhaustively so adding a state without
 * deciding how it reads cannot compile.
 */
@Composable
private fun StateContent(
    state: RemoteCatalogUiState,
    modifier: Modifier = Modifier,
    onSearchQuery: (String) -> Unit,
    onRetrySearch: () -> Unit,
    onPlayRemoteTrack: (ExternalTrack) -> Unit
) {
    when (state) {
        // A search that was never asked for keeps the existing guidance, not a stub table.
        is RemoteCatalogUiState.Idle -> TunenagiEmptyState(
            title = stringResource(R.string.search_empty_title),
            message = stringResource(R.string.search_empty_message),
            modifier = modifier
        )
        is RemoteCatalogUiState.Loading -> LoadingState(
            message = stringResource(R.string.search_audius_loading),
            modifier = modifier
        )
        is RemoteCatalogUiState.Empty -> TunenagiEmptyState(
            title = stringResource(R.string.search_empty_results),
            message = stringResource(R.string.search_empty_message),
            modifier = modifier
        )
        is RemoteCatalogUiState.Results -> ResultsList(
            rows = state.rows,
            modifier = modifier,
            onPlay = onPlayRemoteTrack
        )
        is RemoteCatalogUiState.Error -> TunenagiEmptyState(
            title = state.error.userMessage,
            // A retry is also a replay: the message already asked, the button re-runs.
            message = stringResource(R.string.search_empty_message),
            modifier = modifier,
            actionLabel = stringResource(R.string.remote_retry),
            onAction = onRetrySearch
        )
    }
}

@Composable
private fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(SEARCH_LOADING_TAG),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(top = TunenagiSpacing.XlDp.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TunenagiSpacing.MdDp.dp)
        )
    }
}
@Composable
private fun ResultsList(
    rows: List<ExternalTrack>,
    modifier: Modifier = Modifier,
    onPlay: (ExternalTrack) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(SEARCH_RESULTS_TAG)
    ) {
        items(rows, key = { it.identity }) { row ->
            RemoteResultRow(row = row, onPlay = onPlay)
            Spacer(modifier = Modifier.height(TunenagiSpacing.SmDp.dp))
        }
    }
}

@Composable
private fun RemoteResultRow(row: ExternalTrack, onPlay: (ExternalTrack) -> Unit) {
    val primary = row.primaryArtist.orEmpty()
    Button(
        onClick = { onPlay(row) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TunenagiSpacing.MinTouchTargetDp.dp)
            .testTag(SEARCH_ROW_TAG)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = primary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
