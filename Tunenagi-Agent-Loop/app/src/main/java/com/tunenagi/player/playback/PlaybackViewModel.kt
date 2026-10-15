package com.tunenagi.player.playback

import androidx.lifecycle.ViewModel
import com.tunenagi.player.ui.shell.MiniPlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Presents playback to the shell — owned.
 *
 * It owns the connection's lifetime: the controller is opened with the view model and released when
 * its owner clears the store, so a screen going away does not leave a bound service behind. The
 * mapping from [NowPlaying] to the shell's [MiniPlayerState] lives here rather than in the
 * composable, so "what the bar shows" is assertable without rendering anything.
 *
 * The mapping is a plain cold [Flow] rather than a `stateIn` cache: the source is already a
 * `StateFlow`, so there is one home for the current value, and the UI cannot end up displaying a
 * cached copy of something the session has since changed.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val source: NowPlayingSource
) : ViewModel() {

    val miniPlayerState: Flow<MiniPlayerState> = source.nowPlaying
        .map { playing -> playing?.toMiniPlayerState() ?: MiniPlayerState.None }

    /** Whether the controller is attached; shown when the service cannot be reached. */
    val connection: StateFlow<NowPlayingSource.Connection> = source.connection

    init {
        source.connect()
    }

    fun togglePlayPause() {
        source.togglePlayPause()
    }

    override fun onCleared() {
        source.disconnect()
        super.onCleared()
    }
}
