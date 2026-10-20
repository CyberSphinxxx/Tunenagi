package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.tunenagi.player.R
import com.tunenagi.player.libraryimport.ImportUiState
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val HOME_SCREEN_TAG = "screen-home"
const val HOME_IMPORT_TAG = "home-import"
const val HOME_IMPORT_STATUS_TAG = "home-import-status"

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
    onImportMusic: () -> Unit = {}
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
