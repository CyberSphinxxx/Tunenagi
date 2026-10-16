package com.tunenagi.core.playback

/**
 * What the session needs to resolve and to remember — owned.
 *
 * Two collaborators, not one, because they arrived together: TUN-0203 gave the session a way to turn
 * a media id into a playable item, and TUN-0204 gives it the queue commands, which need the
 * persisted queue as well. [queue] is nullable on purpose: a host that installs a resolution without
 * a library (a test, or a future browse-only deployment) still gets a working session, it just has
 * nowhere to persist a queue, and the session does not pretend otherwise.
 */
class PlaybackGraph(
    val resolution: MediaItemResolution,
    val queue: QueueRepository? = null
)

/**
 * Where the app installs the graph its session uses — owned.
 *
 * The service is created by the operating system, not by the graph that owns everything else: by
 * the time `onCreate` runs there is no injector to ask, and Media3 builds the session inside that
 * same callback. So the one dependency the session cannot be handed at construction time is handed
 * over just before it is needed — the Application installs it, and a service created afterwards
 * picks it up (ADR-013).
 *
 * The default is a real behaviour and not a placeholder: [MediaItemResolution.LocalUriOnly] plays an
 * item that already carries a uri and refuses everything else with a typed error, and there is no
 * queue to persist. A host that installs nothing therefore gets honest failures rather than a crash,
 * and the fallback cannot silently play the wrong thing.
 *
 * The install point is deliberately a named function rather than Hilt injection into the service:
 * injecting would make the *generated* `Hilt_...` class the component the platform starts, which
 * moves the service declaration into build output and takes the manifest assertions of TUN-0201 with
 * it. See ADR-013.
 */
object PlaybackResolution {

    @Volatile
    private var installed: PlaybackGraph? = null

    /**
     * The graph the session uses.
     *
     * Read when a service is built, so an install that happens later does not retroactively change a
     * session that is already resolving: the player would then have two answers to the same media id
     * within one queue.
     */
    fun current(): PlaybackGraph = installed ?: PlaybackGraph(MediaItemResolution.LocalUriOnly)

    /** Installs the library-backed graph. Called once, from the Application. */
    fun install(graph: PlaybackGraph) {
        installed = graph
    }

    /** Back to the default. Test-only: production installs exactly once and never uninstalls. */
    internal fun reset() {
        installed = null
    }
}
