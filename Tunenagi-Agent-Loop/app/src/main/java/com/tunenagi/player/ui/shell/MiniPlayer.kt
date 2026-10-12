package com.tunenagi.player.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.player.ui.theme.TunenagiColors

const val MINI_PLAYER_TAG = "mini-player"

/**
 * What the mini-player is showing.
 *
 * Phase 00 has no audio engine, so [None] is the only state reachable in production.
 * [Track] exists so the shell's layout contract — the bar sits above the navigation and
 * disappears on the player route — is testable and reviewable before playback lands.
 */
sealed interface MiniPlayerState {
    data object None : MiniPlayerState

    data class Track(val title: String, val artist: String) : MiniPlayerState
}

/**
 * Renders the mini-player, or occupies no space at all.
 *
 * It deliberately ships no transport buttons in this phase: there is no player to command
 * yet, and a visible control that cannot act is worse than a missing one. Play/pause and
 * progress arrive with the Media3 service, the first phase that can honour them.
 */
@Composable
fun MiniPlayerHost(
    state: MiniPlayerState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        MiniPlayerState.None -> Unit
        is MiniPlayerState.Track -> MiniPlayerBar(state, onExpand, modifier)
    }
}

@Composable
private fun MiniPlayerBar(
    track: MiniPlayerState.Track,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MINI_PLAYER_TAG)
            .heightIn(min = TunenagiSpacing.MinTouchTargetDp.dp)
            .clickable(onClick = onExpand),
        color = TunenagiColors.Overlay
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TunenagiSpacing.LgDp.dp,
                vertical = TunenagiSpacing.SmDp.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
