package com.tunenagi.core.playback

import com.tunenagi.core.database.PlaybackStateEntity
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.PlaybackSnapshot
import com.tunenagi.core.model.QueueItem
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.ShuffleOrder
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Queue persistence — owned.
 *
 * The rules this implements, all from `SPEC/DATA_MODEL.md` and `SPEC/UX_DESIGN.md`:
 *
 * - the queue is ordered by `(position, addedAt)` and every edit is one transaction, so a partially
 *   applied reorder cannot be observed;
 * - duplicates are legitimate, so items are addressed by their own id;
 * - shuffle order is *materialised* into the stored positions with a persisted seed, so a restore
 *   looks like what the user was listening to;
 * - a restore returns the queue and the current item but does not autoplay: deciding to play is the
 *   player's job, and only after the platform's media-resumption contract is met;
 * - progress is written by explicit checkpoints (pause, seek, periodic save), never per audio tick.
 */
interface QueueRepository {

    fun observe(): Flow<PlaybackSnapshot>

    suspend fun snapshot(): PlaybackSnapshot

    /** Replaces the queue with [trackIds] and points the current item at [startIndex]. */
    suspend fun replaceQueue(
        trackIds: List<TrackId>,
        startIndex: Int = 0,
        at: Instant
    ): PlaybackSnapshot

    suspend fun append(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId? = null): QueueItem

    /** "Play Next": immediately after the current item. */
    suspend fun playNext(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId? = null): QueueItem

    suspend fun remove(itemId: QueueItemId)

    suspend fun move(itemId: QueueItemId, toIndex: Int)

    suspend fun clear()

    suspend fun setCurrent(itemId: QueueItemId)

    suspend fun setRepeatMode(mode: RepeatMode)

    /** Turns shuffle on (materialising an order for [seed]) or off (restoring playback order). */
    suspend fun setShuffle(enabled: Boolean, seed: Int, at: Instant)

    suspend fun saveProgress(progressMs: Long, at: Instant)

    /** The order a seeded shuffle would produce for the queue as it is stored now. */
    suspend fun shuffledOrder(seed: Int): List<QueueItemId>
}

class RoomQueueRepository(private val database: TunenagiDatabase) : QueueRepository {

    private val queueDao get() = database.queueDao()
    private val stateDao get() = database.playbackStateDao()
    private val trackDao get() = database.trackDao()

    override fun observe(): Flow<PlaybackSnapshot> =
        queueDao.observeAll().map { entities ->
            TunenagiMappers.toSnapshot(entities, stateDao.current())
        }

    override suspend fun snapshot(): PlaybackSnapshot =
        TunenagiMappers.toSnapshot(queueDao.all(), stateDao.current())

    override suspend fun replaceQueue(
        trackIds: List<TrackId>,
        startIndex: Int,
        at: Instant
    ): PlaybackSnapshot {
        require(trackIds.isNotEmpty()) { "A queue needs at least one track" }
        require(startIndex in trackIds.indices) {
            "startIndex $startIndex is outside the ${trackIds.size} tracks supplied"
        }
        // Every track has to exist: inserting a queue item for an unknown track would be stopped by
        // the foreign key halfway through, leaving a half-built queue behind.
        val missing = trackIds.filter { trackDao.byId(it.value) == null }
        require(missing.isEmpty()) { "Cannot queue unknown track(s): ${missing.joinToString()}" }

        queueDao.clear()
        queueDao.insertAll(
            trackIds.mapIndexed { index, trackId ->
                TunenagiMappers.toQueueItemEntity(
                    QueueItem(
                        id = QueueItemId.random(),
                        trackId = trackId,
                        position = index,
                        addedAt = at.plusMillis(index.toLong())
                    )
                )
            }
        )
        val queue = queueDao.all()
        writeState(queue[startIndex].id, progressMs = 0, at = at)
        return TunenagiMappers.toSnapshot(queue, stateDao.current())
    }

    override suspend fun append(
        trackId: TrackId,
        at: Instant,
        sourceOverrideId: TrackSourceId?
    ): QueueItem {
        // Checked here as well as by the foreign key: a clear error beats a constraint violation
        // from the middle of a transaction (the test suite caught exactly that difference).
        require(trackDao.byId(trackId.value) != null) { "Cannot queue an unknown track $trackId" }
        val item = QueueItem(
            id = QueueItemId.random(),
            trackId = trackId,
            sourceOverrideId = sourceOverrideId,
            position = queueDao.maxPosition() + 1,
            addedAt = at
        )
        val entity = TunenagiMappers.toQueueItemEntity(item)
        queueDao.insert(entity)
        ensureState(at)
        return TunenagiMappers.toQueueItem(entity)
    }

    override suspend fun playNext(
        trackId: TrackId,
        at: Instant,
        sourceOverrideId: TrackSourceId?
    ): QueueItem {
        require(trackDao.byId(trackId.value) != null) { "Cannot queue an unknown track $trackId" }
        val state = ensureState(at)
        val currentPosition = state.currentQueueItemId
            ?.let { queueDao.byId(it) }
            ?.position
            ?: queueDao.maxPosition()
        val item = QueueItem(
            id = QueueItemId.random(),
            trackId = trackId,
            sourceOverrideId = sourceOverrideId,
            position = currentPosition + 1,
            addedAt = at
        )
        queueDao.insertAfter(TunenagiMappers.toQueueItemEntity(item), afterPosition = currentPosition)
        // Re-normalise so positions stay 0..n-1 and a long session cannot drift into gaps.
        queueDao.applyOrder(queueDao.all().map { it.id })
        return TunenagiMappers.toQueueItem(queueDao.byId(item.id.value)!!)
    }

    override suspend fun remove(itemId: QueueItemId) {
        val removed = queueDao.byId(itemId.value) ?: return
        val wasCurrent = stateDao.current()?.currentQueueItemId == itemId.value
        queueDao.delete(itemId.value)
        val remaining = queueDao.all()
        queueDao.applyOrder(remaining.map { it.id })

        if (wasCurrent) {
            val successor = remaining.firstOrNull { it.position >= removed.position } ?: remaining.firstOrNull()
            writeState(successor?.id, progressMs = 0, at = Instant.EPOCH)
        }
        if (remaining.isEmpty()) stateDao.clear()
    }

    override suspend fun move(itemId: QueueItemId, toIndex: Int) {
        val ordered = queueDao.all().map { it.id }.toMutableList()
        val from = ordered.indexOf(itemId.value)
        if (from < 0) return
        require(toIndex in ordered.indices) { "Cannot move an item to index $toIndex" }
        ordered.removeAt(from)
        ordered.add(toIndex, itemId.value)
        queueDao.applyOrder(ordered)
    }

    override suspend fun clear() {
        queueDao.clear()
        stateDao.clear()
    }

    override suspend fun setCurrent(itemId: QueueItemId) {
        require(queueDao.byId(itemId.value) != null) { "Cannot select an item that is not in the queue: $itemId" }
        val state = ensureState(Instant.EPOCH)
        stateDao.upsert(state.copy(currentQueueItemId = itemId.value, progressMs = 0))
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        val state = ensureState(Instant.EPOCH)
        stateDao.upsert(state.copy(repeatMode = mode.name))
    }

    override suspend fun setShuffle(enabled: Boolean, seed: Int, at: Instant) {
        val state = ensureState(at)
        if (enabled) {
            // Materialise the order, keeping the current item where the user is: shuffling must not
            // interrupt playback, and the seed is stored so a restore reproduces this order.
            val current = state.currentQueueItemId
            val ids = queueDao.all().map { it.id }
            val shuffled = ShuffleOrder.order(ids.map(QueueItemId::parse), seed).map { it.value }
            val ordered = if (current != null && shuffled.contains(current)) {
                listOf(current) + shuffled.filter { it != current }
            } else {
                shuffled
            }
            queueDao.applyOrder(ordered)
            stateDao.upsert(state.copy(shuffleEnabled = true, shuffleSeed = seed))
        } else {
            // Off means the order the user built, which is positions as stored *before* shuffling, so
            // the shuffle is undone by re-sorting on the original added sequence.
            val restored = queueDao.all()
                .sortedWith(compareBy({ it.addedAt }, { it.id }))
                .map { it.id }
            queueDao.applyOrder(restored)
            stateDao.upsert(state.copy(shuffleEnabled = false, shuffleSeed = null))
        }
    }

    override suspend fun saveProgress(progressMs: Long, at: Instant) {
        val state = ensureState(at)
        stateDao.upsert(state.copy(progressMs = progressMs.coerceAtLeast(0), lastUpdatedAt = at.toEpochMilli()))
    }

    override suspend fun shuffledOrder(seed: Int): List<QueueItemId> =
        ShuffleOrder.order(queueDao.all().map { QueueItemId.parse(it.id) }, seed)

    private suspend fun ensureState(at: Instant): PlaybackStateEntity {
        stateDao.current()?.let { return it }
        val initial = PlaybackStateEntity.initial(at.toEpochMilli())
        stateDao.upsert(initial)
        return initial
    }

    private suspend fun writeState(currentItemId: String?, progressMs: Long, at: Instant) {
        val state = ensureState(at)
        stateDao.upsert(
            state.copy(
                currentQueueItemId = currentItemId,
                progressMs = progressMs,
                lastUpdatedAt = if (at == Instant.EPOCH) state.lastUpdatedAt else at.toEpochMilli()
            )
        )
    }
}
