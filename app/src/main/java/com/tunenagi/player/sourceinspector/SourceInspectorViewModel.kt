package com.tunenagi.player.sourceinspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tunenagi.core.library.SourceMatchRepository
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.playback.MediaItemIds
import com.tunenagi.player.playback.NowPlayingSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What one row of the inspector shows. Typed, so the screen maps it to strings and the test can
 * assert the classification without asserting a translation. */
enum class SourceStatus { Playable, Restricted, FileMissing, PermissionNeeded, Unavailable, NotChecked }

/** One stored source of the track under the player. [providerId] is the raw namespaced id; the
 * screen turns it into a display name, because a plugin's name is its host and belongs to the UI. */
data class SourceRow(
    val sourceId: String,
    val providerId: String,
    val status: SourceStatus,
    /** The source's own content type (`audio/mpeg`), or null. Never guessed from an extension. */
    val format: String?,
    val isPreferred: Boolean,
    val isLocked: Boolean
)

/** Why playback's own choice is unavailable, with the status that explains it. */
data class UnavailableFeedback(val sourceId: String, val status: SourceStatus)

/** What a just-performed action did, so the screen can confirm it in words. */
sealed interface Notice {
    data class Chosen(val providerId: String) : Notice

    data class Unlocked(val providerId: String) : Notice
}

/** Why the last action did not happen. Typed rather than an exception string leaking to the UI. */
enum class ActionFailure { SourceNotListed, Rejected }

/**
 * What the source inspector shows — owned (TUN-0307).
 *
 * The states are the honest answers to "what is this track playable from": nothing is loaded, the
 * session is playing something that is not a library track id, the track has no stored sources, or
 * here they are with each one's status. `unavailableFeedback` is the task's "unavailable variant
 * feedback": when the source playback would actually use is not playable, the screen says so with
 * the typed reason instead of letting the row quietly disagree with what the player is doing.
 */
sealed interface SourceInspectorState {
    data object Loading : SourceInspectorState

    data object NoTrack : SourceInspectorState

    /** The media id is not `tunenagi:track:<uuid>`; there is no library track to inspect. */
    data object NotALibraryTrack : SourceInspectorState

    data class Loaded(
        val rows: List<SourceRow>,
        val unavailableFeedback: UnavailableFeedback? = null,
        val notice: Notice? = null,
        val failure: ActionFailure? = null
    ) : SourceInspectorState
}

/**
 * The inspector over the track under the player — owned (TUN-0307).
 *
 * The screen may only ever see the sources of the *current* item: the key is read from the
 * session's own snapshot (`tunenagi:track:` media ids, the convention
 * `MediaItemIds` owns), never from a parameter the UI could point at another track. Writes go
 * through [SourceMatchRepository], so the choice persists across a restart exactly like a scan's
 * own preference would, and an id the current rows never contained is refused *before* the
 * repository is asked, so a stale click cannot lock something the user cannot see.
 */
@HiltViewModel
class SourceInspectorViewModel @Inject constructor(
    private val repository: SourceMatchRepository,
    playback: NowPlayingSource
) : ViewModel() {

    private val _state = MutableStateFlow<SourceInspectorState>(SourceInspectorState.Loading)
    val state: StateFlow<SourceInspectorState> = _state.asStateFlow()

    /** The track the rows belong to; every action is refused when there is none. */
    private var trackId: TrackId? = null

    /** The rows last shown, which is also the closed set of ids an action may name. */
    private var rows: List<SourceRow> = emptyList()

    init {
        viewModelScope.launch {
            // Every session snapshot re-keys the inspector: the item under the player is the only
            // thing whose sources this screen may show or change.
            playback.nowPlaying.collect { playing -> show(playing?.mediaId) }
        }
    }

    /** Makes [sourceId] the track's preferred and locked source, persistently. */
    fun choose(sourceId: String) = act(sourceId, lock = true)

    /** Removes the lock but leaves the source preferred, so playback keeps using it for now. */
    fun unlock(sourceId: String) = act(sourceId, lock = false)

    private fun act(sourceId: String, lock: Boolean) {
        val track = trackId ?: return
        val row = rows.firstOrNull { it.sourceId == sourceId }
        if (row == null) {
            // Refused without a write: the id is not part of what this screen is showing.
            _state.value = SourceInspectorState.Loaded(
                rows = rows,
                unavailableFeedback = (_state.value as? SourceInspectorState.Loaded)?.unavailableFeedback,
                failure = ActionFailure.SourceNotListed
            )
            return
        }
        viewModelScope.launch {
            try {
                if (lock) {
                    repository.chooseSource(track, TrackSourceId.parse(sourceId))
                } else {
                    repository.setSourceLocked(TrackSourceId.parse(sourceId), locked = false)
                }
                reload(
                    track,
                    notice = if (lock) Notice.Chosen(row.providerId) else Notice.Unlocked(row.providerId)
                )
            } catch (rejected: IllegalArgumentException) {
                _state.value = SourceInspectorState.Loaded(
                    rows = rows,
                    failure = ActionFailure.Rejected
                )
            }
        }
    }

    private suspend fun show(mediaId: String?) {
        if (mediaId == null) {
            trackId = null
            rows = emptyList()
            _state.value = SourceInspectorState.NoTrack
            return
        }
        val parsed = MediaItemIds.trackIdOrNull(mediaId)
        if (parsed == null) {
            trackId = null
            rows = emptyList()
            _state.value = SourceInspectorState.NotALibraryTrack
            return
        }
        trackId = parsed
        reload(parsed, notice = null)
    }

    /** Reads the stored rows and the source playback would use, in that order, in one state. */
    private suspend fun reload(track: TrackId, notice: Notice?) {
        val stored = repository.sourcesFor(track)
        rows = stored.map { it.toRow() }
        val used = repository.preferredSource(track)
        _state.value = SourceInspectorState.Loaded(
            rows = rows,
            unavailableFeedback = used
                ?.takeUnless { it.availability.isPlayable }
                ?.let { UnavailableFeedback(it.id.value, it.availability.toStatus()) },
            notice = notice
        )
    }

    private fun TrackSource.toRow() = SourceRow(
        sourceId = id.value,
        providerId = providerId.value,
        status = availability.toStatus(),
        format = contentType,
        isPreferred = preferred,
        isLocked = locked
    )

    private fun SourceAvailability.toStatus(): SourceStatus = when (this) {
        SourceAvailability.Available -> SourceStatus.Playable
        SourceAvailability.Restricted -> SourceStatus.Restricted
        SourceAvailability.StorageGone -> SourceStatus.FileMissing
        SourceAvailability.PermissionRequired -> SourceStatus.PermissionNeeded
        SourceAvailability.Unavailable -> SourceStatus.Unavailable
        SourceAvailability.Unknown -> SourceStatus.NotChecked
    }
}
