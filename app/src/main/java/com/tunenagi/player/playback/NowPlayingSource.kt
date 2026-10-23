package com.tunenagi.player.playback

import kotlinx.coroutines.flow.StateFlow
import com.tunenagi.core.model.TrackId

/**
 * What the UI is allowed to know about playback — owned.
 *
 * The screens depend on this interface, not on Media3: the shell renders a snapshot and sends
 * commands, and it cannot reach a player even by accident because no player type appears here.
 * That is also what keeps the UI testable on the JVM with no service running.
 *
 * [connect] and [disconnect] are explicit because a controller is a bound-service connection with
 * a cost: it is opened when something is watching and closed when nothing is.
 */
interface NowPlayingSource {

    /**
     * Whether the connection to the playback service is up.
     *
     * It is part of the contract rather than a detail of the Media3 implementation because
     * "connected with nothing playing" and "could not connect" look identical on screen, and a
     * screen that cannot tell them apart ends up showing a confident, wrong empty state.
     */
    sealed interface Connection {
        data object Closed : Connection

        data object Connecting : Connection

        data object Connected : Connection

        data class Failed(val reason: String) : Connection
    }

    /** The latest snapshot, or `null` when nothing is loaded. */
    val nowPlaying: StateFlow<NowPlaying?>

    /** The state of the connection itself. */
    val connection: StateFlow<Connection>

    /** Opens the connection to the playback service. Safe to call more than once. */
    fun connect()

    /** Closes the connection and releases the controller. Safe to call more than once. */
    fun disconnect()

    /** Starts playback when paused, pauses when playing. A no-op with nothing loaded. */
    fun togglePlayPause()

    /**
     * Replaces the session's queue with [trackIds] and starts it.
     *
     * The ids are tracks, not resolved uris: only the media id crosses the process boundary, and
     * the session turns it into a playable item through the library (which is also where a source
     * that has become unavailable is reported). A no-op before the controller is attached; the
     * caller can see that the connection did not become `Connected`.
     */
    fun playTracks(trackIds: List<TrackId>)

    /**
     * Retries the item playback stopped on. A no-op with nothing attached.
     *
     * The session has already spent its automatic attempts by the time a failure is shown; this is
     * the user's own retry, so it is not counted against that budget and does not wait.
     */
    fun retry()
}
