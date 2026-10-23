package com.tunenagi.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tunenagi.core.playback.MediaItemIds
import com.tunenagi.core.playback.TunenagiPlaybackService
import com.tunenagi.core.model.TrackId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's `MediaController` — owned.
 *
 * This is the *only* way the UI touches playback: a controller bound to
 * [TunenagiPlaybackService], never a player of its own. It names the service component directly
 * through a [SessionToken] instead of looking it up by package, so a renamed or missing component
 * fails visibly here rather than connecting to something unexpected.
 *
 * Two listeners are attached, and both are needed. `MediaController.Listener` reports the
 * *connection* (a disconnect, an error the session sent back), while `Player.Listener` reports the
 * *player* state the bar renders. Collapsing them into one is how an app ends up showing stale
 * metadata after the service has gone away.
 *
 * [NowPlayingSource.Connection] exists because "connected but nothing is playing" and "could not
 * connect at all" look identical on screen; a screen that cannot tell them apart ends up showing a
 * confident, wrong empty state. The failure branch is therefore part of the contract rather than an
 * exception logged out of sight.
 *
 * The whole class opts into Media3's `UnstableApi` rather than annotating each call site: the
 * `MediaController`, its builder and its listener are marked unstable in this Media3 release, and
 * this is the one class in the app that touches them. `:app:lintDebug` is what keeps that promise
 * honest - an opt-in anywhere else would be a deliberate act rather than a leak of the annotation
 * into code that has no business naming a session.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context
) : NowPlayingSource {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _connection = MutableStateFlow<NowPlayingSource.Connection>(
        NowPlayingSource.Connection.Closed
    )
    override val connection: StateFlow<NowPlayingSource.Connection> = _connection.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /**
     * Every state change that can alter what the bar shows arrives here: item changes, playback
     * state and position discontinuities all produce an event, so the snapshot cannot go stale
     * without a second subscription to keep in step.
     */
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _nowPlaying.value = player.nowPlayingOrNull()
        }
    }

    /** Connection-level events: the service going away, or refusing a command. */
    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            // The service died or was stopped: the snapshot is no longer trustworthy, and keeping
            // it would leave a bar showing a track nobody can pause.
            controllerRelease()
            _nowPlaying.value = null
            _connection.value = NowPlayingSource.Connection.Closed
        }

        override fun onError(controller: MediaController, sessionError: SessionError) {
            // The session refused a command or could not serve it. The controller is released as
            // well as reported: leaving it attached would keep `connect()` short-circuiting on a
            // connection that is already known to be broken, so the only way out would be an app
            // restart.
            controllerRelease()
            _connection.value = NowPlayingSource.Connection.Failed(
                "session error ${sessionError.code}: ${sessionError.message}"
            )
        }
    }

    override fun connect() {
        // Idempotent: a recomposition, a second screen or a rotation must not open a second
        // connection to a service that owns a single player.
        if (controllerFuture != null) return

        val token = SessionToken(
            context,
            ComponentName(context, TunenagiPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token)
            .setListener(sessionListener)
            .buildAsync()
        controllerFuture = future
        _connection.value = NowPlayingSource.Connection.Connecting

        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { attached ->
                        controller = attached
                        attached.addListener(playerListener)
                        _connection.value = NowPlayingSource.Connection.Connected
                        _nowPlaying.value = attached.nowPlayingOrNull()
                    }
                    .onFailure { failure ->
                        // Leave the future null so a later connect() can retry: a failed connection
                        // is not a permanent state, and hiding that would need an app restart.
                        controllerFuture = null
                        _connection.value = NowPlayingSource.Connection.Failed(
                            failure.cause?.message ?: failure.message ?: failure::class.java.name
                        )
                    }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    override fun disconnect() {
        controllerRelease()
        _nowPlaying.value = null
        _connection.value = NowPlayingSource.Connection.Closed
    }

    /**
     * Releases whichever controller the connection currently holds — attached or still binding.
     *
     * Extracted so the three paths that end a connection (an explicit [disconnect], a disconnect
     * from the service, a session error) all release through one function, and so the invariant
     * "at most one attached or pending controller" is written once.
     *
     * The pending future is released by this function rather than only by [disconnect], because a
     * controller that is still binding owns the service binding just as an attached one does: the
     * earlier version released it only when a controller had already attached, so a disconnect
     * during connection left the future in place and every later [connect] returned early, leaving
     * the app permanently `Closed` with a bound service still behind it.
     *
     * Idempotent by construction: both fields are cleared on the first release, so a second call
     * does nothing.
     */
    private fun controllerRelease() {
        if (controllerFuture == null && controller == null) return
        controller?.removeListener(playerListener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
    }

    override fun togglePlayPause() {
        val attached = controller ?: return
        // `play()` on an idle player with an empty queue does nothing and is not an error; the
        // caller sees the unchanged state through `nowPlaying` rather than a silent lie.
        if (attached.isPlaying) attached.pause() else attached.play()
    }

    override fun playTracks(trackIds: List<TrackId>) {
        val attached = controller
        if (attached == null) {
            _connection.value = NowPlayingSource.Connection.Failed(
                "playback is not connected, so the queue could not be started"
            )
            return
        }
        if (trackIds.isEmpty()) return
        // Only the media id crosses the process boundary; the session resolves each one through the
        // library (TUN-0203), so an imported track that has become unavailable fails with a typed
        // error instead of being handed a uri this side guessed.
        val items = trackIds.map { id ->
            MediaItem.Builder().setMediaId(MediaItemIds.track(id)).build()
        }
        attached.setMediaItems(items, 0, 0L)
        attached.prepare()
        attached.play()
    }

    override fun retry() {
        val attached = controller ?: return
        // Media3 keeps `playWhenReady` across a playback error, so `prepare()` re-opens the item
        // and resumes only when the user had asked for sound. Calling `play()` here would turn a
        // manual retry after a deliberate pause into an autoplay surprise.
        attached.prepare()
    }
}
