package com.tunenagi.player.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import com.tunenagi.core.playback.PlaybackFailure
import com.tunenagi.player.R
import com.tunenagi.player.ui.theme.TunenagiColors

const val MINI_PLAYER_TAG = "mini-player"
const val MINI_PLAYER_TOGGLE_TAG = "mini-player-toggle"
const val MINI_PLAYER_RETRY_TAG = "mini-player-retry"

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
        val isPlaying: Boolean = false,
        /** The player is waiting for data; shown instead of the artist line. */
        val isBuffering: Boolean = false,
        /** Why playback stopped, when it did; a Retry control is offered alongside it. */
        val failure: PlaybackFailure? = null
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
    onTogglePlayPause: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    when (state) {
        MiniPlayerState.None -> Unit
        is MiniPlayerState.Track ->
            MiniPlayerBar(state, onExpand, onTogglePlayPause, onRetry, modifier)
    }
}

@Composable
private fun MiniPlayerBar(
    track: MiniPlayerState.Track,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
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
                val status = when {
                    track.failure != null -> stringResource(track.failure.messageRes())
                    track.isBuffering -> stringResource(R.string.playback_buffering)
                    else -> null
                }
                // A failure replaces the artist line: the reason audio stopped is more important
                // than who made the track, and showing both would repeat the same row of text.
                val secondary = status ?: track.artist
                if (secondary.isNotEmpty()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (track.failure != null) {
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(MINI_PLAYER_RETRY_TAG)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.playback_retry)
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

/**
 * The sentence a listener needs, per failure kind.
 *
 * Kept in the UI layer so the core taxonomy stays free of Android resources, and exhaustive so a
 * new failure category cannot appear without a written explanation.
 */
@StringRes
private fun PlaybackFailure.messageRes(): Int = when (this) {
    PlaybackFailure.Timeout -> R.string.playback_error_timeout
    PlaybackFailure.Network -> R.string.playback_error_network
    PlaybackFailure.MissingSource -> R.string.playback_error_missing
    PlaybackFailure.AccessLost -> R.string.playback_error_permission
    PlaybackFailure.UnsupportedFormat -> R.string.playback_error_unsupported
    is PlaybackFailure.Unknown -> R.string.playback_error_unknown
}
