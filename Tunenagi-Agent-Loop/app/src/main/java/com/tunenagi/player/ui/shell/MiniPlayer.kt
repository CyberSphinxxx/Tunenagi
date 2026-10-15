package com.tunenagi.player.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.player.R
import com.tunenagi.player.ui.theme.TunenagiColors

const val MINI_PLAYER_TAG = "mini-player"
const val MINI_PLAYER_TOGGLE_TAG = "mini-player-toggle"

/**
 * What the mini-player is showing.
 *
 * [Track] is filled from the playback session's own state (`NowPlaying`), never from an intention:
 * [isPlaying] is the player's answer, so the control and the label cannot disagree with what is
 * actually audible.
 */
sealed interface MiniPlayerState {
    data object None : MiniPlayerState

    data class Track(
        val title: String,
        val artist: String,
        val isPlaying: Boolean = false
    ) : MiniPlayerState
}

/**
 * Renders the mini-player, or occupies no space at all.
 *
 * The transport control is the first one the app can honour: it commands the bound `MediaController`,
 * so it is present exactly when there is something loaded to command, and its icon and accessible
 * name both follow the session state.
 */
@Composable
fun MiniPlayerHost(
    state: MiniPlayerState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onTogglePlayPause: () -> Unit = {}
) {
    when (state) {
        MiniPlayerState.None -> Unit
        is MiniPlayerState.Track -> MiniPlayerBar(state, onExpand, onTogglePlayPause, modifier)
    }
}

@Composable
private fun MiniPlayerBar(
    track: MiniPlayerState.Track,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
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
                if (track.artist.isNotEmpty()) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // The accessible name describes the action, so a screen reader announces
            // "Pause" for a playing track rather than the state it is already in.
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.testTag(MINI_PLAYER_TOGGLE_TAG)
            ) {
                if (track.isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = stringResource(R.string.mini_player_pause)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.mini_player_play)
                    )
                }
            }
        }
    }
}
