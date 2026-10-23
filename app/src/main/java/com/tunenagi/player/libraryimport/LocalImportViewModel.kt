package com.tunenagi.player.libraryimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tunenagi.core.model.LocalFolderImporter
import com.tunenagi.core.model.LocalImportResult
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.playback.QueueRepository
import com.tunenagi.player.playback.NowPlayingSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the import control is doing — owned.
 *
 * The states are the four answers a user can act on: it worked and there is N of their music ready,
 * the folder had nothing this app can play, the grant is gone, or the folder could not be read. A
 * spinner for an operation that silently did nothing is the failure mode this type exists to avoid.
 */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object Importing : ImportUiState

    /** The folder was indexed and its tracks were handed to the session to play. */
    data class Ready(val songs: Int) : ImportUiState

    /** The folder was read, but held nothing this app can play. */
    data object NoAudio : ImportUiState

    /** The persisted grant is no longer valid; only the system picker can restore it. */
    data object PermissionRequired : ImportUiState

    /** The folder could not be read for any other reason. Details stay out of the UI. */
    data object Failed : ImportUiState
}

/**
 * Imports one folder the user picked and starts it — owned.
 *
 * This is the bridge TUN-0202 was missing: discovery and indexing already worked, but nothing in the
 * app asked for a grant or started a queue, so local playback was unreachable. The order matters and
 * is the same one the queue commands use: **persist the queue first, then tell the session to play**.
 * If the player side fails, the stored queue is the authority the next command re-applies, and a
 * relaunch still shows the import; the reverse order would play music that vanishes from the queue at
 * the next restart.
 *
 * The importer is the domain interface from `:core` (the app may not reach `core.provider` or
 * `core.database`), and the grant itself is requested by the Activity: only an Activity can take a
 * persistable URI permission.
 */
@HiltViewModel
class LocalImportViewModel @Inject constructor(
    private val importer: LocalFolderImporter,
    private val queue: QueueRepository,
    private val playback: NowPlayingSource
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /**
     * Reads [treeUri] and, when it holds playable audio, replaces the queue and starts it.
     *
     * Re-entrant taps are ignored rather than queued: two pickers cannot be open at once, and a
     * second scan started over the first would double the import work for the same folder.
     */
    fun importFolder(treeUri: String) {
        if (_state.value == ImportUiState.Importing) return
        _state.value = ImportUiState.Importing
        viewModelScope.launch {
            _state.value = when (val result = readFolder(treeUri)) {
                LocalImportResult.PermissionRequired -> ImportUiState.PermissionRequired
                is LocalImportResult.Failed -> ImportUiState.Failed
                is LocalImportResult.Imported ->
                    if (result.trackIds.isEmpty()) ImportUiState.NoAudio
                    else startQueue(result.trackIds)
            }
        }
    }

    /** A failure is a state, not a crash: the screen says the folder could not be read. */
    private suspend fun readFolder(treeUri: String): LocalImportResult =
        try {
            importer.importFolder(treeUri)
        } catch (cancelled: CancellationException) {
            // A cleared view model is not a folder error; swallowing this would keep the scan's
            // continuation alive after its owner asked it to stop.
            throw cancelled
        } catch (failure: Throwable) {
            LocalImportResult.Failed(failure::class.java.simpleName)
        }

    private suspend fun startQueue(trackIds: List<TrackId>): ImportUiState =
        try {
            queue.replaceQueue(trackIds, startIndex = 0, at = Instant.now())
            playback.playTracks(trackIds)
            ImportUiState.Ready(trackIds.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ImportUiState.Failed
        }
}
