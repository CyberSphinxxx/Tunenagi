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

const val HOME_SCREEN_TAG = "screen-home"

/**
 * Home — the wordmark plus the honest first-launch state.
 *
 * `SPEC/UX_DESIGN.md` wants prominent Import Music and Browse Audius actions here once
 * there is content. Neither action exists yet (local import arrives with the local
 * playback phase, Audius with the provider phase), and this project does not ship buttons
 * that cannot act, so the first-launch state offers the one destination that does work
 * today: Search. See ADR-002 in `STATE/decisions.md`.
 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
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
        TunenagiEmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            actionLabel = stringResource(R.string.action_open_search),
            onAction = onOpenSearch,
            modifier = Modifier.weight(1f)
        )
    }
}
