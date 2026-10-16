package com.tunenagi.core.playback

/**
 * Where the app installs the resolution its session resolves through — owned.
 *
 * The service is created by the operating system, not by the graph that owns everything else: by
 * the time `onCreate` runs there is no injector to ask, and Media3 builds the session inside that
 * same callback. So the one dependency the session cannot be handed at construction time is handed
 * over just before it is needed — the Application installs it, and a service created afterwards
 * picks it up.
 *
 * The default is [MediaItemResolution.LocalUriOnly], which is a real behaviour and not a
 * placeholder: an item that already carries a playable uri plays, and anything else fails with a
 * typed error. A host that installs nothing therefore gets honest failures rather than a crash, and
 * the fallback cannot silently play the wrong thing.
 *
 * The install point is deliberately a single named function rather than Hilt injection into the
 * service: injecting would make the *generated* `Hilt_...` class the component the platform starts,
 * which moves the service declaration into build output and takes the manifest assertions of
 * TUN-0201 with it. See ADR-013.
 */
object PlaybackResolution {

    @Volatile
    private var installed: MediaItemResolution? = null

    /**
     * The resolution the session uses.
     *
     * Read when a service is built, so an install that happens later does not retroactively change a
     * session that is already resolving: the player would then have two answers to the same media id
     * within one queue.
     */
    fun current(): MediaItemResolution = installed ?: MediaItemResolution.LocalUriOnly

    /** Installs the library-backed resolution. Called once, from the Application. */
    fun install(resolution: MediaItemResolution) {
        installed = resolution
    }

    /** Back to the default. Test-only: production installs exactly once and never uninstalls. */
    internal fun reset() {
        installed = null
    }
}
