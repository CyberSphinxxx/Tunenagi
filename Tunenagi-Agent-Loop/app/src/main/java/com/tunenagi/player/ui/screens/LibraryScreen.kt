package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.player.R
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val LIBRARY_SCREEN_TAG = "screen-library"

/**
 * Library.
 *
 * The Songs/Artists/Albums/Playlists/Favorites filters, sorting and the rescan action all
 * need a local library to act on, so this phase ships the empty state and nothing that
 * pretends otherwise.
 */
@Composable
fun LibraryScreen(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TunenagiSpacing.LgDp.dp)
            .testTag(LIBRARY_SCREEN_TAG)
    ) {
        Text(
            text = stringResource(R.string.tab_library),
            style = MaterialTheme.typography.headlineMedium
        )
        TunenagiEmptyState(
            title = stringResource(R.string.library_empty_title),
            message = stringResource(R.string.library_empty_message),
            actionLabel = stringResource(R.string.action_open_search),
            onAction = onOpenSearch,
            modifier = Modifier.weight(1f)
        )
    }
}
