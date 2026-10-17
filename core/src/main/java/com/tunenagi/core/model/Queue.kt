package com.tunenagi.core.model

import java.time.Instant
import java.util.Random

/**
 * Queue domain models — owned.
 *
 * A queue is a list of *occurrences*: `SPEC/DATA_MODEL.md` allows the same track twice, so
 * [QueueItem.id] (not the track id) is what a remove or a reorder acts on. Playing track 3 twice
 * and removing one of them must not remove both.
 */
data class QueueItem(
    val id: QueueItemId,
    val trackId: TrackId,
    val sourceOverrideId: TrackSourceId? = null,
    val position: Int,
    val addedAt: Instant
) {
    init {
        require(position >= 0) { "Queue position must not be negative, but was $position" }
    }
}

/**
 * What playback should look like after a restart.
 *
 * [shuffleSeed] is stored alongside the flag because `SPEC/UX_DESIGN.md` requires deterministic
 * shuffle ordering: restoring a shuffled queue without the seed would silently re-order the user's
 * listening. [currentItemId] being null is the "restored but deliberately not playing" state —
 * `SPEC/DATA_MODEL.md` forbids autoplaying a restored queue without the platform's media-resumption
 * contract.
 */
data class PlaybackSnapshot(
    val queue: List<QueueItem> = emptyList(),
    val currentItemId: QueueItemId? = null,
    val progressMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val shuffleSeed: Int? = null,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val lastUpdatedAt: Instant? = null
) {
    val currentItem: QueueItem? get() = queue.firstOrNull { it.id == currentItemId }

    /**
     * The item after [currentItemId] according to the repeat mode, or null when playback should
     * stop. Shuffle is already reflected in the stored order, so this walk stays a plain list walk
     * and cannot disagree with what the user sees.
     */
    fun nextItem(from: QueueItemId? = currentItemId): QueueItem? {
        if (queue.isEmpty()) return null
        val index = queue.indexOfFirst { it.id == from }
        if (index < 0) return queue.first()
        if (repeatMode == RepeatMode.One) return queue[index]
        val following = queue.getOrNull(index + 1)
        if (following != null) return following
        return if (repeatMode == RepeatMode.All) queue.first() else null
    }
}

/**
 * Deterministic shuffle — owned.
 *
 * Fisher-Yates driven by a seeded [Random], so the same seed and the same items always produce the
 * same order. That is what makes "deterministic shuffle ordering" in `SPEC/UX_DESIGN.md` testable
 * rather than aspirational, and what lets a restored queue look exactly as it did before.
 */
object ShuffleOrder {

    fun order(ids: List<QueueItemId>, seed: Int): List<QueueItemId> {
        val shuffled = ids.toMutableList()
        val random = Random(seed.toLong())
        for (index in shuffled.size - 1 downTo 1) {
            val swapWith = random.nextInt(index + 1)
            val held = shuffled[index]
            shuffled[index] = shuffled[swapWith]
            shuffled[swapWith] = held
        }
        return shuffled
    }

    /** A seed to persist when the user switches shuffle on. */
    fun seedFor(seedSource: Long): Int = seedSource.toInt()
}
