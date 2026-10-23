package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.player.R
import com.tunenagi.player.discovery.RemoteCatalogUiState
import com.tunenagi.player.libraryimport.ImportUiState
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val HOME_SCREEN_TAG = "screen-home"
const val HOME_IMPORT_TAG = "home-import"
const val HOME_IMPORT_STATUS_TAG = "home-import-status"
const val HOME_TRENDING_ROW_TAG = "trending-row"
const val HOME_TRENDING_BROWSE_TAG = "trending-browse"

/**
 * Home — the wordmark, the real import action and the honest first-launch state.
 *
 * `SPEC/UX_DESIGN.md` puts Import Music here as the primary action, and ADR-003 held it back until
 * there was something behind it. There is now: a folder picker, a scan that indexes what it finds,
 * and a queue. Browse Audius still has no provider, so it is still absent rather than disabled.
 *
 * The import control is the only thing on this screen that changes the library, and its result is
 * shown in words: "3 songs ready" is a claim a user can check against the mini-player that starts.
 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    importState: ImportUiState = ImportUiState.Idle,
    onImportMusic: () -> Unit = {},
    trendingState: RemoteCatalogUiState = RemoteCatalogUiState.Idle,
    onBrowseAudius: () -> Unit = {},
    onPlayTrending: (ExternalTrack) -> Unit = {},
    onRetryTrending: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TunenagiSpacing.LgDp.dp)
            .testTag(HOME_SCREEN_TAG)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge
        )
        Button(
            onClick = onImportMusic,
            enabled = importState != ImportUiState.Importing,
            modifier = Modifier
                .padding(top = TunenagiSpacing.LgDp.dp)
                .testTag(HOME_IMPORT_TAG)
        ) {
            Text(text = stringResource(R.string.import_music))
        }
        importStatus(importState)?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = TunenagiSpacing.SmDp.dp)
                    .testTag(HOME_IMPORT_STATUS_TAG)
            )
        }
        TunenagiEmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            actionLabel = stringResource(R.string.action_open_search),
            onAction = onOpenSearch,
            modifier = Modifier.weight(1f)
        )
        LoadTrendingHome(
            state = trendingState,
            onBrowseAudius = onBrowseAudius,
            onOpenSearch = onOpenSearch,
            onPlay = onPlayTrending,
            onRetry = onRetryTrending
        )
    }
}

/**
 * The trending surface at the bottom of Home.
 *
 * Until trending is asked for, this section does not even appear: nothing is requested
 * before the user chooses to browse. Once loaded, rows are actions and a stopped
 * page explains itself the way Search does.
 */
@Composable
private fun LoadTrendingHome(
    state: RemoteCatalogUiState,
    modifier: Modifier = Modifier,
    onBrowseAudius: () -> Unit,
    onOpenSearch: () -> Unit,
    onPlay: (ExternalTrack) -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.browse_audius_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = TunenagiSpacing.LgDp.dp)
        )
        when (state) {
            is RemoteCatalogUiState.Idle -> Button(
                onClick = onBrowseAudius,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TunenagiSpacing.SmDp.dp)
                    .testTag(HOME_TRENDING_BROWSE_TAG)
            ) {
                Text(stringResource(R.string.browse_audius_start))
            }
            is RemoteCatalogUiState.Loading -> Text(
                text = stringResource(R.string.browse_audius_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is RemoteCatalogUiState.Results -> Column {
                state.rows.take(6).forEach { row ->
                    TrendingRow(row, onPlay)
                }
                // The rest of the page lives on the Search screen, which is already a tab
                // the shell routes — Home links there rather than reimplementing paging.
            }
            is RemoteCatalogUiState.Empty -> Text(
                text = stringResource(R.string.home_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is RemoteCatalogUiState.Error -> Column {
                Text(
                    text = state.error.userMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = TunenagiSpacing.SmDp.dp)
                        .heightIn(min = TunenagiSpacing.MinTouchTargetDp.dp)
                ) {
                    Text(stringResource(R.string.remote_retry))
                }
            }
        }
    }
}

@Composable
private fun TrendingRow(row: ExternalTrack, onPlay: (ExternalTrack) -> Unit) {
    Button(
        onClick = { onPlay(row) },
        // Only the provider's own "cannot play" claims disable the row; the resolver
        // is still allowed to confirm an `Unknown` claim it may have recovered on.
        enabled = row.availability != SourceAvailability.Restricted &&
            row.availability != SourceAvailability.Unavailable,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TunenagiSpacing.SmDp.dp)
            .heightIn(min = TunenagiSpacing.MinTouchTargetDp.dp)
            .testTag(HOME_TRENDING_ROW_TAG)
    ) {
        Column {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Text(
                text = row.primaryArtist.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * The sentence under the button, or `null` when there is nothing to say.
 *
 * Exhaustive so a new state cannot be added without a message: the idle state is the only silent
 * one, and every other state is something the user just caused.
 */
@Composable
private fun importStatus(state: ImportUiState): String? = when (state) {
    ImportUiState.Idle -> null
    ImportUiState.Importing -> stringResource(R.string.import_reading_folder)
    is ImportUiState.Ready ->
        pluralStringResource(R.plurals.import_songs_ready, state.songs, state.songs)
    ImportUiState.NoAudio -> stringResource(R.string.import_no_audio)
    ImportUiState.PermissionRequired -> stringResource(R.string.import_permission_required)
    ImportUiState.Failed -> stringResource(R.string.import_failed)
}
