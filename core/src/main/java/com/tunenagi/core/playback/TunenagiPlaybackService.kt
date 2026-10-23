package com.tunenagi.core.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only place in Tunenagi that owns a player — owned.
 *
 * `SPEC/ARCHITECTURE.md` requires one application process whose long-lived `MediaLibraryService`
 * owns the sole Media3 player, with the UI observing through a controller and never holding its own
 * player instance. This class is that service, and it is the *only* class that constructs one:
 * `SCRIPTS/check_boundaries.py` rule B10 fails the build if an `ExoPlayer` is imported or a
 * session is built anywhere else, because "the player lives in the Activity" is the first trap the
 * phase-02 document names and it is invisible in a code review of any single file.
 *
 * The player is private on purpose. Everything the rest of the app needs goes through the session
 * (see [TunenagiMediaLibraryCallback]) and the app's `MediaController`.
 *
 * `onCreate` builds exactly one engine and one session over it; `onDestroy` releases both, so a
 * service that is stopped and started again cannot accumulate players. The session resolves media
 * items through the resolution the app installed (see [PlaybackResolution]). Both halves are
 * asserted by
 * `TunenagiPlaybackServiceTest` on the JVM, using the real `ExoPlayer` and the real session — the
 * Android runtime is not available to this project (ADR-008), and a fake player would not prove the
 * invariant that matters here.
 */
open class TunenagiPlaybackService : MediaLibraryService() {

    private var engine: TunenagiPlaybackEngine? = null
    private var library: MediaLibraryService.MediaLibrarySession? = null

    /**
     * The service's own scope, for the work that has to outlive a callback but not the service.
     *
     * Not an application-scoped one: a checkpoint written by a service that no longer owns a player
     * would attach a position to a queue nobody is playing, so this scope is cancelled with the
     * engine it belongs to.
     */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The scope the last checkpoint is dispatched on when the task goes away.
     *
     * Its own scope, so [onDestroy]'s cancellation of [sessionScope] cannot take the write with it;
     * `Main.immediate`, so the flush reads the player on this callback's thread before anything else
     * can run. The job is never cancelled - the write is one statement and the process, not the
     * service, decides when to reclaim it.
     */
    private val lastCheckpointScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The callback the session was built with.
     *
     * Held as a named field rather than constructed inline so a test can drive the same instance
     * the session uses; Media3 does not expose a session's callback, and asserting against a
     * second, separate instance would prove nothing about the wiring.
     *
     * The resolution comes from [PlaybackResolution], which the Application installs: this service
     * is created by the OS, so it has no constructor to be handed one (ADR-013). Reading it here
     * means the session and its callback cannot disagree about how a media id is resolved.
     */
    internal val sessionCallback = PlaybackResolution.current().let { graph ->
        // The queue is the same graph the commands persist through, and the scope is this service's:
        // a resumption read must not outlive the service that would apply its answer.
        TunenagiMediaLibraryCallback(
            resolution = graph.resolution,
            queue = graph.queue,
            scope = sessionScope
        )
    }

    /**
     * The queue commands this session applies, or `null` when the host installed no library.
     *
     * Built from the same graph the callback resolves through, so the two cannot disagree about
     * which library they are working on. It needs the engine, which is why it is created in
     * [onCreate] rather than beside the callback — and why it is dropped again in [onDestroy], since
     * a command that outlived its player could seek something that no longer exists.
     */
    internal var queueCommands: PlaybackQueueCommands? = null
        private set

    /**
     * The debounced position checkpoint writer, or `null` when the host installed no library.
     *
     * Started with the player and stopped with it, so "where the user was" is recorded exactly as
     * long as there is a player to be at a position in (TUN-0205).
     */
    internal var progressRecorder: PlaybackProgressRecorder? = null
        private set

    /**
     * The bounded error-recovery loop over the service's player (TUN-0207), or `null` when the
     * service is not running.
     *
     * Owned here rather than by the UI so every failure the player raises - from a lockscreen
     * command, a resumption or the app - gets the same policy. It is detached and cancelled in
     * [onDestroy], because a retry that outlived its player would prepare nothing.
     */
    internal var playbackRecovery: PlaybackRecovery? = null
        private set

    /**
     * Why the start-up restore did not happen, when it did not.
     *
     * A restore can fail for a reason a user can act on - a file deleted since the last session, a
     * media permission revoked - and that is the typed [UnresolvedMediaItemException] the resolution
     * raises. The failure cannot be thrown from the scope it runs on (a service that crashed while
     * starting up would be the worst possible answer), and silently swallowing it would hide a real
     * problem, so it is *held* here for the status surface TUN-0207 builds. Nothing in this class
     * decides how to show it.
     */
    internal var restoreFailure: UnresolvedMediaItemException? = null
        private set

    /** The engine this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedEngine: TunenagiPlaybackEngine? get() = engine

    /** The player this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedPlayer: Player? get() = engine?.player

    /** The session this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedSession: MediaLibraryService.MediaLibrarySession? get() = library

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Installed before the session exists, because MediaSessionService reads the provider once
        // when the session is added. Media3 owns when the notification is posted, updated, moved to
        // the foreground and removed; this only fixes the channel and the notification id
        // (`TunenagiNotifications` explains why those are part of the contract).
        setMediaNotificationProvider(TunenagiNotifications.provider(this))
        val playbackEngine = TunenagiPlaybackEngine(this)
        engine = playbackEngine
        // A failure is retried at most twice, on the main thread, through the player this service
        // owns. `prepare()` is the whole retry: it re-opens the failed item rather than inventing
        // a replacement source, and it resumes only if `playWhenReady` still reflects the user's
        // intent.
        val recovery = PlaybackRecovery(
            retry = { playbackEngine.player.prepare() },
            scheduler = HandlerRetryScheduler()
        )
        playbackEngine.player.addListener(recovery)
        playbackRecovery = recovery
        val graph = PlaybackResolution.current()
        queueCommands = graph.queue?.let { queue ->
            PlaybackQueueCommands(
                player = playbackEngine.player,
                queue = queue,
                resolution = graph.resolution
            )
        }
        progressRecorder = graph.queue?.let { queue ->
            PlaybackProgressRecorder(
                player = playbackEngine.player,
                queue = queue,
                scope = sessionScope
            ).also { it.start() }
        }
        // The queue the user left, at the position they left it, and deliberately not playing: the
        // operating system creating this service is not a request for sound (see
        // `PlaybackQueueCommands.restore`).
        sessionScope.launch {
            val commands = queueCommands ?: return@launch
            try {
                commands.restore()
            } catch (failure: UnresolvedMediaItemException) {
                restoreFailure = failure
            }
        }
        library = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            playbackEngine.player,
            sessionCallback
        )
            .setId(SESSION_ID)
            .build()
    }

    /**
     * Hands the same session to every controller.
     *
     * Returning the one instance (rather than building a session per connection) is what makes
     * "single player" true in practice: two controllers — the app's `MediaController` and, later, a
     * system media control client — observe and command the same queue and position.
     */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibraryService.MediaLibrarySession? = library

    /**
     * The last moment the user's place can be written before the task is gone.
     *
     * Swiping the task away is one of the paths in `SPEC/EDGE_CASES.md` ("task swiped away"), and the
     * periodic checkpoint may be up to one interval behind it. Two details make this work rather than
     * merely look right:
     *
     * - **The write does not run on [sessionScope].** Media3 stops this service from
     *   `super.onTaskRemoved` when nothing is playing, and [onDestroy] cancels that scope - a flush
     *   launched on it could be cancelled before Room had run the UPDATE. The last checkpoint is
     *   therefore dispatched on a scope of its own, which is one write and then unreachable.
     * - **It starts on this thread, not on a background one.** The player may only be read on the
     *   main thread (Media3 enforces it), and it may be released by [onDestroy] as soon as this
     *   callback returns. A flush launched on `Dispatchers.IO` would queue its player read behind
     *   whatever the main thread does next - including that release - so the numbers would be read
     *   after they stopped existing. An immediate main-thread launch reads them *now*, before
     *   `super.onTaskRemoved` can stop anything, and only the database part is left asynchronous.
     * - **It cannot be awaited.** A database write is asynchronous, so this is best-effort by
     *   construction: the honest guarantee remains "at most one interval of position is lost", and
     *   this narrows it for the swipe-away case instead of pretending to close it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        progressRecorder?.let { recorder ->
            lastCheckpointScope.launch { recorder.flush() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Detach before the engine goes: a listener left on a released player would be called for
        // events that no longer describe anything, and its ticker would write positions for a queue
        // this service no longer owns.
        playbackRecovery?.let { recovery ->
            engine?.player?.removeListener(recovery)
            recovery.cancel()
        }
        playbackRecovery = null
        progressRecorder?.stop()
        progressRecorder = null
        library?.release()
        engine?.release()
        library = null
        engine = null
        queueCommands = null
        sessionScope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * The session id third-party clients and the media button receiver use to find this
         * session. It is a constant rather than the package name so a future package rename cannot
         * silently orphan a session that a client has already saved.
         */
        const val SESSION_ID = "tunenagi.playback"
    }
}
