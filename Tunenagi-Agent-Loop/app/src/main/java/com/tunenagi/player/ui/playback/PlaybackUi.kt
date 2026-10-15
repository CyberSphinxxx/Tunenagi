package com.tunenagi.player.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunenagi.player.playback.PlaybackViewModel
import com.tunenagi.player.ui.shell.MiniPlayerState

/**
 * Everything the shell needs to know about playback — owned.
 *
 * State and command travel together in one value so a host cannot wire the bar and forget the
 * control that goes with it. The shell itself stays a pure function of these parameters, which is
 * what lets the JVM UI suite render it with no Hilt graph, no service and no device.
 */
class PlaybackUi(
    val state: MiniPlayerState,
    val onTogglePlayPause: () -> Unit
)

/** Attaches the host to the playback view model and observes its state for as long as it is shown. */
@Composable
fun rememberPlaybackUi(): PlaybackUi {
    val viewModel: PlaybackViewModel = hiltViewModel()
    val state by viewModel.miniPlayerState.collectAsStateWithLifecycle(
        initialValue = MiniPlayerState.None
    )
    return PlaybackUi(state = state, onTogglePlayPause = viewModel::togglePlayPause)
}
