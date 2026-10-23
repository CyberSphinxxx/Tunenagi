package com.tunenagi.core.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes where the user is, rarely enough to be safe and often enough to be true — owned.
 *
 * `SPEC/DATA_MODEL.md` states the rule this class exists to obey: persist the current position
 * "periodically (e.g. at 5-15-second intervals) and on suspend/seek/pause; do not issue DB writes
 * every audio tick". A position changes on every audio frame, so a listener that wrote whenever it
 * changed would put thousands of writes per track on the same database the library uses. Instead
 * there are exactly two kinds of checkpoint:
 *
 * - **Explicit ones**, driven by player events that mean the stored position has just stopped being
 *   true: playback paused, the user sought, the player moved to another item, the item ended. These
 *   are immediate, because the next thing that happens may be process death.
 * - **Periodic ones**, one every [intervalMs] while something is playing, which is what bounds what
 *   a hard kill can lose to one interval.
 *
 * Two rules keep the pair from fighting each other, and both are about correctness rather than
 * volume:
 *
 * 1. **The stored current item follows the player.** When Media3 advances because a track ended, the
 *    position now belongs to the *next* queue entry (or to the first one) - writing it against the
 *    previous current item would store "track 4, 0:07" for a user who is listening to track 5, and a
 *    relaunch would resume the wrong song at the wrong place. So the checkpoint resolves the player's
 *    current index against the stored queue first, and only writes a position it can attribute.
 * 2. **A position that cannot be attributed is not written.** While a command is re-applying the
 *    queue the player and the store briefly hold different lengths; a position from that moment has
 *    no item to belong to, so it is skipped. Losing one checkpoint is cheap; attaching a position to
 *    the wrong track is not.
 *
 * What this does *not* do: it never writes on `release` or on scope cancellation, because a suspend
 * write cannot be awaited in `onDestroy`. The documented guarantee is therefore "at most one interval
 * of position is lost to a hard kill", not "nothing is ever lost" - and [stop] is deliberately not a
 * flush, so a caller who wants a final write has to ask for [flush] while the scope is still alive.
 */
class PlaybackProgressRecorder(
    private val player: Player,
    private val queue: QueueRepository,
    private val scope: CoroutineScope,
    /** How often a playing session checkpoints. Inside `SPEC/DATA_MODEL.md`'s 5-15s window. */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Instant = Instant::now,
    /**
     * Monotonic time for the interval gate. `SystemClock`'s uptime rather than the wall clock: a
     * user changing the device clock or a DST jump must not collapse or stretch the interval.
     */
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * The thread the player may be touched on.
     *
     * Media3 confines a player to the application's main thread and *enforces* it - reading a
     * position from anywhere else throws `IllegalStateException` (measured: that is exactly what the
     * swipe-away flush in `TunenagiPlaybackService.onTaskRemoved` did before this seam existed). A
     * checkpoint is often dispatched from somewhere else, so the player-derived numbers are read
     * here and only those numbers travel to the database work.
     */
    private val playerContext: CoroutineContext = Dispatchers.Main.immediate
) : Player.Listener {

    private val mutex = Mutex()
    private var lastWriteAtMs: Long? = null
    private var ticker: Job? = null

    /**
     * Whether this recorder may still touch its player.
     *
     * Cleared by [stop], because whoever stops a recorder is the owner about to release the player: a
     * flush still in flight must then record nothing rather than read a released player. The read in
     * [checkpoint] happens on [playerContext] and so does [stop], which is what makes the check an
     * ordering rather than a race. Tests that drive [checkpoint] directly without ever starting the
     * recorder never detach, so a standalone checkpoint still reads its player.
     */
    @Volatile
    private var attached = true

    /** Whether this recorder is attached to its player and ticking. */
    val isRunning: Boolean get() = ticker?.isActive == true

    /**
     * Attaches to the player and starts the periodic checkpoint.
     *
     * Called by whoever owns the player's lifetime, so that a recorder cannot outlive the thing it
     * records - the same reason the session's commands are dropped when the service is destroyed.
     */
    fun start() {
        attached = true
        player.addListener(this)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(intervalMs)
                // Not forced: the interval gate below is the real rule, and the delay is only how
                // often it is given a chance to run.
                checkpoint()
            }
        }
    }

    /** Detaches and stops ticking. Deliberately does not write; see the class note. */
    fun stop() {
        attached = false
        player.removeListener(this)
        ticker?.cancel()
        ticker = null
    }

    /** Writes now, whatever the interval says. The last-write-before-teardown call. */
    suspend fun flush(): Long? = checkpoint(force = true)

    /**
     * Stores the position the user is at, at most once per [intervalMs] unless [force]d.
     *
     * Returns the position written, or `null` when nothing was written. That is one of four things,
     * none of them an error: the recorder has been [stop]ped (and so may no longer touch its player),
     * the store has no queue to attach a position to, the player and the store disagree about the
     * queue's length (a command is mid-reapply), or the interval has not elapsed yet and the call was
     * not forced. The player half is read on [playerContext]; everything else runs where the caller
     * put it, so this is safe to call from a background scope.
     */
    suspend fun checkpoint(force: Boolean = false): Long? {
        val observed = observePlayer() ?: return null

        val stored = queue.snapshot()
        if (stored.queue.isEmpty()) return null
        // The player's items are the stored queue in the stored order while a session is alive; a
        // mismatch means a command is mid-reapply or the player was rebuilt, and a position read now
        // belongs to no item in particular.
        if (observed.itemCount != stored.queue.size) return null
        val entry = stored.queue.getOrNull(observed.index) ?: return null

        mutex.withLock {
            val now = monotonicMs()
            val previous = lastWriteAtMs
            if (!force && previous != null && now - previous < intervalMs) return null
            // The stored current item has to name the item the position belongs to before the
            // position is written, otherwise the pair can be read back as a different track.
            if (entry.id != stored.currentItemId) queue.setCurrent(entry.id)
            queue.saveProgress(observed.positionMs, clock())
            lastWriteAtMs = now
        }
        return observed.positionMs
    }

    /**
     * Reads the player's side of a checkpoint on the thread that owns it.
     *
     * `null` means "nothing to record": the recorder has been detached and its player may be gone.
     * That is a shutdown path, so it is a value rather than a signal - a flush racing the service's
     * teardown must not be a crash nobody can report.
     */
    private suspend fun observePlayer(): PlayerProgress? = withContext(playerContext) {
        if (!attached) return@withContext null
        PlayerProgress(
            itemCount = player.mediaItemCount,
            index = player.currentMediaItemIndex,
            positionMs = resumePositionMs(player.playbackState, player.currentPosition)
        )
    }

    /** The numbers a checkpoint takes off the player, once it is safe to take them. */
    private data class PlayerProgress(val itemCount: Int, val index: Int, val positionMs: Long)

    /**
     * A pause is an explicit checkpoint, and so is a resume: the user letting go of the transport is
     * exactly when the stored position should be true.
     */
    override fun onIsPlayingChanged(isPlaying: Boolean) = record()

    /** The player moved to another item, so the previous item's position is final. */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = record()

    /**
     * A seek, an auto-transition or a skip is the user's or the queue's own repositioning.
     *
     * `INTERNAL` is excluded on purpose: it is Media3 adjusting its own timeline (a stream window
     * moving, a silence skip), which can happen repeatedly and is not a position the user chose. The
     * periodic checkpoint covers it.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason != Player.DISCONTINUITY_REASON_INTERNAL) record()
    }

    /** An ended item is a finished item; see [resumePositionMs]. */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) record()
    }

    /**
     * Runs a checkpoint off the player's callback.
     *
     * Media3 delivers listener callbacks on the application's main thread, and a checkpoint reads
     * and writes the database, so it is launched rather than awaited. Forced, because these are the
     * events that mean a position is about to become history.
     */
    private fun record() {
        scope.launch { checkpoint(force = true) }
    }

    companion object {
        /**
         * Ten seconds: inside `SPEC/DATA_MODEL.md`'s 5-15 second window, and short enough that a
         * process killed while playing loses a position a user would not notice losing.
         */
        const val DEFAULT_INTERVAL_MS = 10_000L

        /**
         * What a checkpoint should store for a given player state.
         *
         * An ended item stores *zero*, not its duration. A stored position at the very end is a trap
         * on the next launch: the player is restored at the end of the track, so pressing play plays
         * nothing and the only way to hear the song again is to seek - for a track the user has
         * already finished and may well want to hear again. "Finished" is therefore stored as "start
         * of this item", which is what pressing play on a finished track does in every player a user
         * has used. A zero or negative position from anywhere else is clamped instead of trusting it.
         */
        fun resumePositionMs(playbackState: Int, positionMs: Long): Long =
            if (playbackState == Player.STATE_ENDED) 0L else positionMs.coerceAtLeast(0)
    }
}
