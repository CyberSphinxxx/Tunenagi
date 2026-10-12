package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.player.R
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val PLAYER_SCREEN_TAG = "player-screen"

/**
 * The player host route.
 *
 * This is the destination the mini-player expands into and the one the audio phase fills
 * with artwork, transport controls, seekbar and queue. It is reachable and navigable now,
 * which is what this phase owes; it shows an explicit "nothing is playing" state rather
 * than mock transport controls that would lie about the player.
 */
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(PLAYER_SCREEN_TAG)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TunenagiSpacing.SmDp.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(PLAYER_CLOSE_TAG)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_close)
                )
            }
            Text(text = stringResource(R.string.player_title))
        }
        TunenagiEmptyState(
            title = stringResource(R.string.player_empty_title),
            message = stringResource(R.string.player_empty_message),
            modifier = Modifier.weight(1f)
        )
    }
}

const val PLAYER_CLOSE_TAG = "player-close"
