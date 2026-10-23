package com.tunenagi.player.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunenagi.player.sourceinspector.SourceInspectorState
import com.tunenagi.player.sourceinspector.SourceInspectorViewModel

/**
 * What the player screen needs to know about sources — owned (TUN-0307).
 *
 * The same contract as [PlaybackUi]: state and the commands that belong to it travel in one
 * value, so a host cannot render the inspector and forget the action that goes with it, and the
 * shell stays a pure function of its parameters (which is what lets the JVM UI suite render it
 * with no Hilt graph, no service and no device).
 */
class SourceInspectorUi(
    val state: SourceInspectorState,
    /** Makes the named source the track's preferred and locked one. */
    val onChoose: (String) -> Unit,
    /** Removes the lock, leaving the source preferred. */
    val onUnlock: (String) -> Unit
) {
    companion object {
        /** Nothing loaded: the player keeps its "nothing is playing" state. */
        val None = SourceInspectorUi(SourceInspectorState.NoTrack, onChoose = {}, onUnlock = {})
    }
}

/** Attaches the inspector view model and observes it for as long as the screen is shown. */
@Composable
fun rememberSourceInspectorUi(): SourceInspectorUi {
    val viewModel: SourceInspectorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle(initialValue = SourceInspectorState.Loading)
    return SourceInspectorUi(
        state = state,
        onChoose = viewModel::choose,
        onUnlock = viewModel::unlock
    )
}
