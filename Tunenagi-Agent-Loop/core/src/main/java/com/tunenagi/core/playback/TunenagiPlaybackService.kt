package com.tunenagi.core.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

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
    internal val sessionCallback = TunenagiMediaLibraryCallback(PlaybackResolution.current())

    /** The engine this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedEngine: TunenagiPlaybackEngine? get() = engine

    /** The player this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedPlayer: Player? get() = engine?.player

    /** The session this service owns, or `null` before [onCreate] / after [onDestroy]. */
    internal val ownedSession: MediaLibraryService.MediaLibrarySession? get() = library

    override fun onCreate() {
        super.onCreate()
        val playbackEngine = TunenagiPlaybackEngine(this)
        engine = playbackEngine
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

    override fun onDestroy() {
        library?.release()
        engine?.release()
        library = null
        engine = null
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
