package com.tunenagi.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.tunenagi.core.model.PlaybackSnapshot
import com.tunenagi.core.model.QueueItem
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.TrackId
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every command a user can give the queue, applied to the player *and* to the library — owned.
 *
 * The player and the database are two places one queue lives, and the whole job here is to keep them
 * from disagreeing. The persisted queue is the authority for *order* (`SPEC/DATA_MODEL.md` stores the
 * queue with positions and a seeded permutation), so every command writes through
 * [QueueRepository] first and then makes the player match it. The player stays the authority for
 * *position* and for what is audible, which is why a seek or a repeat change does not rebuild it.
 *
 * Three decisions are worth reading before the code:
 *
 * 1. **Shuffle is materialised, not delegated.** Turning shuffle on rewrites the stored order into
 *    the seeded permutation and the player is given that order with its own shuffle left *off*.
 *    `SPEC/DATA_MODEL.md` requires a persisted permutation so a restart reproduces what the user was
 *    hearing; two shuffles (player and storage) would be a second order nobody can reason about, and
 *    `player.shuffleModeEnabled` is deliberately not used.
 * 2. **An edit preserves the listening position.** Adding, removing, reordering or shuffling keeps
 *    the current item current and its position intact — `SPEC/UX_DESIGN.md` requires the queue to be
 *    editable without interrupting playback.
 * 3. **Previous has a documented threshold.** `SPEC/AUDIO_ENGINE.md` requires "restart the current
 *    track if elapsed > threshold, otherwise the previous queue entry"; the threshold is a named
 *    constant here and the position is read through an injectable seam so the policy is testable
 *    without a device.
 *
 * Resolution failures are not swallowed: the items handed to the player are the *resolved* items
 * (`SPEC/ARCHITECTURE.md`: a fresh reference, produced late), and an unresolvable one surfaces as the
 * typed [UnresolvedMediaItemException] from TUN-0203 rather than as silence. When that happens the
 * stored queue is ahead of the player; the next command re-applies it, and [restore] does the same
 * after a restart - quietly, which is `SPEC/DATA_MODEL.md`'s rule rather than a preference.
 */
class PlaybackQueueCommands(
    private val player: Player,
    private val queue: QueueRepository,
    private val resolution: MediaItemResolution,
    /**
     * How far into the current track playback is. A seam rather than `player.currentPosition` so the
     * previous-track threshold can be tested at a chosen position on the JVM.
     */
    private val positionMs: () -> Long = { player.currentPosition },
    private val clock: () -> Instant = Instant::now
) {

    /**
     * Puts the queue the user left behind back on a fresh player, without starting it.
     *
     * `SPEC/DATA_MODEL.md` is explicit that queue resumption after process death "should not autoplay
     * unexpectedly unless Android media-resumption contract is met", and the operating system
     * creating a service is not that contract: it happens when the app is opened, when a controller
     * connects and when the system rebinds for its own reasons. So the default here is [play] `false`,
     * and nothing about restoring produces sound - the media items and their metadata are on the
     * player (so the queue a user sees is the one they left, and pressing play works immediately),
     * but the player is not even prepared, which also means no decoder, no audio focus request and no
     * buffer for a queue nobody asked to hear.
     *
     * Explicit callers that hold a resumption request pass `play = true`; that is the only path here
     * that prepares and plays. The platform's own resumption request does not come through this
     * method: `TunenagiMediaLibraryCallback.onPlaybackResumption` answers it with the stored items and
     * Media3 applies and starts them, which is why a normal service start still passes `false`.
     *
     * Returns `null` when the store holds no queue, which is not a failure - a first launch has
     * nothing to restore, and the UI shows its empty state rather than an error. A failure to resolve
     * is the typed [UnresolvedMediaItemException] from TUN-0203 and leaves the player untouched (the
     * resolve happens before anything is handed to it), so a queue that cannot be played produces an
     * honest empty player instead of a broken first item.
     *
     * A stored current item that is no longer in the queue - removed while the app was not running,
     * or a queue edited by hand - resumes at the *first* item with no position, because the stored
     * progress belonged to an item that is gone and applying it to a different track is the one
     * outcome a user would notice. A stored position past the item's end is left to Media3, which
     * clamps it when the item is prepared.
     */
    suspend fun restore(play: Boolean = false): PlaybackSnapshot? {
        val snapshot = queue.snapshot()
        if (snapshot.queue.isEmpty()) return null
        val index = snapshot.queue.indexOfFirst { it.id == snapshot.currentItemId }
        val resumeMs = if (index < 0) 0L else snapshot.progressMs.coerceAtLeast(0)
        apply(snapshot, resumeMs = resumeMs, play = play, prepare = play)
        return snapshot
    }

    /** Starts a new queue and begins playing it. */
    suspend fun play(trackIds: List<TrackId>, startIndex: Int = 0): PlaybackSnapshot {
        val snapshot = queue.replaceQueue(trackIds, startIndex, clock())
        apply(snapshot, resumeMs = 0, play = true)
        return snapshot
    }

    /** "Play Next": the track goes directly after whatever is playing. */
    suspend fun playNext(trackId: TrackId): PlaybackSnapshot {
        queue.playNext(trackId, clock())
        return reapply()
    }

    suspend fun addToEnd(trackId: TrackId): PlaybackSnapshot {
        queue.append(trackId, clock())
        return reapply()
    }

    /**
     * Removes whatever is playing.
     *
     * `SPEC/AUDIO_ENGINE.md` requires "advance safely or stop": the repository moves the current
     * pointer to the successor (or the new first item), and an empty queue clears the player rather
     * than leaving a stopped item behind, which is what lets the UI hide its controls truthfully.
     */
    suspend fun removeCurrent(): PlaybackSnapshot {
        val snapshot = queue.snapshot()
        val current = snapshot.currentItemId ?: return snapshot
        queue.remove(current)
        return reapply()
    }

    suspend fun remove(itemId: QueueItemId): PlaybackSnapshot {
        queue.remove(itemId)
        return reapply()
    }

    suspend fun move(itemId: QueueItemId, toIndex: Int): PlaybackSnapshot {
        queue.move(itemId, toIndex)
        return reapply()
    }

    /** Empties the queue and the player, so nothing is left half-playing. */
    suspend fun clear(): PlaybackSnapshot {
        queue.clear()
        stopPlayback()
        player.clearMediaItems()
        return queue.snapshot()
    }

    /** Off -> All -> One -> Off, persisted and applied to the player in one step. */
    suspend fun cycleRepeat(): RepeatMode {
        val next = RepeatMode.nextAfter(queue.snapshot().repeatMode)
        queue.setRepeatMode(next)
        player.repeatMode = next.toPlayerRepeatMode()
        return next
    }

    /**
     * Turns the materialised shuffle on (with [seed]) or off, keeping playback where it is.
     *
     * The current item is moved to the front of the shuffled order by the repository, so the track
     * being played stays current and the player's index follows the restored order.
     */
    suspend fun setShuffle(enabled: Boolean, seed: Int): PlaybackSnapshot {
        queue.setShuffle(enabled, seed, clock())
        return reapply()
    }

    /** Seeks within the current track and checkpoints the position (`SPEC/DATA_MODEL.md`). */
    suspend fun seekTo(positionMs: Long): PlaybackSnapshot {
        val bounded = positionMs.coerceAtLeast(0)
        player.seekTo(bounded)
        queue.saveProgress(bounded, clock())
        return queue.snapshot()
    }

    /**
     * The next item, or a graceful stop at the end when repeat is off.
     *
     * The walk is the stored one ([PlaybackSnapshot.nextItem]) rather than Media3's own, so repeat
     * One/All and the end-of-queue boundary are decided by the same rule the persisted state
     * describes — and so the persisted current item cannot drift from what is audible.
     */
    suspend fun next(): PlaybackSnapshot {
        val snapshot = queue.snapshot()
        val target = snapshot.nextItem()
        if (target == null) {
            // Repeat is off and the last item has ended: stop, without clearing the queue, so the
            // user can still see and replay what they were listening to.
            stopPlayback()
            return snapshot
        }
        queue.setCurrent(target.id)
        seekToItem(snapshot, target.id, startPlaying = true)
        return queue.snapshot()
    }

    /**
     * Previous, with the threshold from `SPEC/AUDIO_ENGINE.md`.
     *
     * Past the threshold — or already at the first item, where stepping back would mean wrapping —
     * the current track restarts. Below it, playback steps to the previous entry.
     */
    suspend fun previous(): PlaybackSnapshot {
        val snapshot = queue.snapshot()
        val current = snapshot.currentItem ?: return snapshot
        val index = snapshot.queue.indexOfFirst { it.id == current.id }
        if (positionMs() >= RESTART_THRESHOLD_MS || index <= 0) {
            player.seekTo(0)
            return snapshot
        }
        val target = snapshot.queue[index - 1]
        queue.setCurrent(target.id)
        seekToItem(snapshot, target.id, startPlaying = true)
        return queue.snapshot()
    }

    /**
     * Moves the player to a queue item.
     *
     * A plain seek, not a rebuild: re-resolving and re-adding the queue on every track change would
     * throw away the buffer of the item being replaced, and it is not needed because the player's
     * order is the stored order.
     */
    private fun seekToItem(snapshot: PlaybackSnapshot, itemId: QueueItemId, startPlaying: Boolean) {
        val index = snapshot.queue.indexOfFirst { it.id == itemId }
        if (index >= 0 && player.mediaItemCount == snapshot.queue.size) {
            player.seekTo(index, 0)
            if (startPlaying) player.play()
        }
    }

    /** Re-applies the stored order without changing what is playing or where it is. */
    private suspend fun reapply(): PlaybackSnapshot {
        val snapshot = queue.snapshot()
        apply(snapshot, resumeMs = positionMs().coerceAtLeast(0), play = player.playWhenReady)
        return snapshot
    }

    private suspend fun apply(
        snapshot: PlaybackSnapshot,
        resumeMs: Long,
        play: Boolean,
        /**
         * Whether the items are turned into audio now. False only for a restore nobody asked to
         * hear: `setMediaItems` alone already gives a controller the queue and its metadata, while a
         * `prepare` would open a decoder and take audio focus for it.
         */
        prepare: Boolean = true
    ) {
        if (snapshot.queue.isEmpty()) {
            stopPlayback()
            player.clearMediaItems()
            return
        }
        val index = snapshot.queue.indexOfFirst { it.id == snapshot.currentItemId }.coerceAtLeast(0)
        val items = resolved(snapshot.queue.map { item(it) })
        player.setMediaItems(items, index, resumeMs)
        // `prepare` is what turns the ids into audio; `play` is only ever true when the caller asked
        // for sound (a new queue, or the platform's resumption contract) or something was already
        // playing (an edit mid-listen), so a command never starts playback the user had paused.
        if (prepare) player.prepare()
        if (play) player.play()
    }

    private fun item(entry: QueueItem): MediaItem = entry.requestedMediaItem()

    private suspend fun resolved(items: List<MediaItem>): List<MediaItem> =
        // The resolution is asynchronous and may read the database and the file system; keeping it
        // off the caller's dispatcher is the same rule the rest of this project follows.
        withContext(Dispatchers.IO) {
            try {
                resolution.resolve(items).get()
            } catch (wrapped: ExecutionException) {
                // The resolution reports failures as the typed exception TUN-0203 defines, and `get()`
                // wraps it. Unwrapping here is what keeps that type usable: a caller should handle
                // `UnresolvedMediaItemException`, not the mechanics of how this method waits.
                throw wrapped.cause ?: wrapped
            }
        }

    /**
     * Stops playback *and* lowers `playWhenReady`.
     *
     * Media3's `stop()` moves the player to idle without touching the flag, so a player stopped this
     * way still reports `playWhenReady == true` - which is how a UI ends up showing a play button
     * over a queue it cannot play. Both facts are set together, in one place, so no caller forgets.
     */
    private fun stopPlayback() {
        player.stop()
        player.playWhenReady = false
    }

    companion object {
        /**
         * `SPEC/AUDIO_ENGINE.md`: elapsed time past which Previous restarts the current track instead
         * of stepping back. Three seconds is the value most users' habits match, and it is a named
         * constant so the behaviour is a contract rather than a number inside a branch.
         */
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}

/**
 * The media item a stored queue entry asks the resolver for.
 *
 * A queue item names its source when the user picked one (`sourceOverrideId`) and its track
 * otherwise, so the preferred source is chosen at resolve time rather than frozen into the queue.
 * This lives outside the command class because the platform's resumption callback asks the same
 * question about the same stored queue ([TunenagiMediaLibraryCallback.onPlaybackResumption]); two
 * copies of the id convention would be two chances to resume the wrong track.
 */
internal fun QueueItem.requestedMediaItem(): MediaItem {
    val override = sourceOverrideId
    val mediaId = if (override != null) MediaItemIds.source(override) else MediaItemIds.track(trackId)
    return MediaItem.Builder().setMediaId(mediaId).build()
}

/** `SPEC/AUDIO_ENGINE.md`'s repeat modes, as Media3 expresses them. */
internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.Off -> Player.REPEAT_MODE_OFF
    RepeatMode.All -> Player.REPEAT_MODE_ALL
    RepeatMode.One -> Player.REPEAT_MODE_ONE
}
