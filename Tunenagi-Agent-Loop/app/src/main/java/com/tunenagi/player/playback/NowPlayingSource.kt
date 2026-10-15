package com.tunenagi.player.playback

import kotlinx.coroutines.flow.StateFlow

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
}
