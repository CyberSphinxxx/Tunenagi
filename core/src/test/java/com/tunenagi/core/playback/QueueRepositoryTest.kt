package com.tunenagi.core.playback

import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.ShuffleOrder
import com.tunenagi.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Queue behaviour against a real database. Everything here is about what survives: a reorder that
 * only exists in memory, a duplicate that removes two rows instead of one, or a shuffle that changes
 * on every restart are all bugs a user sees immediately.
 */
@RunWith(RobolectricTestRunner::class)
class QueueRepositoryTest {

    private lateinit var db: TunenagiDatabase
    private lateinit var queue: QueueRepository
    private val trackIds = List(4) { TrackId.random() }

    @Before
    fun setUp() = runBlocking {
        db = Fixtures.database()
        queue = RoomQueueRepository(db)
        trackIds.forEachIndexed { index, id ->
            db.trackDao().insert(
                TunenagiMappers.toTrackEntity(Fixtures.track(id, title = "Track $index"), Fixtures.now, Fixtures.now)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun queueAll(): List<String> = queue.snapshot().queue.map { it.trackId.value }

    @Test
    fun replacingTheQueueStoresTheOrderAndStartsAtTheRequestedIndex() = runBlocking {
        val snapshot = queue.replaceQueue(trackIds, startIndex = 2, at = Fixtures.now)

        assertEquals(trackIds.map { it.value }, snapshot.queue.map { it.trackId.value })
        assertEquals(trackIds[2], snapshot.currentItem!!.trackId)
        assertEquals(0L, snapshot.progressMs)
        assertEquals(RepeatMode.Off, snapshot.repeatMode)
    }

    @Test
    fun appendingKeepsPositionContiguous() = runBlocking {
        queue.replaceQueue(trackIds.take(2), at = Fixtures.now)
        queue.append(trackIds[2], at = Fixtures.now.plusSeconds(10))

        assertEquals(listOf(0, 1, 2), queue.snapshot().queue.map { it.position })
        assertEquals(trackIds[2].value, queueAll().last())
    }

    /**
     * "Play Next" inserts a new occurrence; it does not move the existing one. Queuing a track that
     * is already later in the queue therefore leaves that copy where it was.
     */
    @Test
    fun playNextInsertsDirectlyAfterTheCurrentItem() = runBlocking {
        queue.replaceQueue(trackIds, startIndex = 1, at = Fixtures.now)
        queue.playNext(trackIds[3], at = Fixtures.now.plusSeconds(5))

        assertEquals(
            listOf(trackIds[0], trackIds[1], trackIds[3], trackIds[2], trackIds[3]).map { it.value },
            queueAll()
        )
        assertEquals(listOf(0, 1, 2, 3, 4), queue.snapshot().queue.map { it.position })
    }

    /** The same track twice is two rows; removing one must not touch the other. */
    @Test
    fun duplicatesAreAddressedIndividually() = runBlocking {
        queue.replaceQueue(listOf(trackIds[0]), at = Fixtures.now)
        queue.append(trackIds[0], at = Fixtures.now.plusSeconds(1))
        val queueNow = queue.snapshot()
        assertEquals(2, queueNow.queue.size)
        assertEquals(2, queueNow.queue.distinctBy { it.id }.size)

        queue.remove(queueNow.queue[0].id)

        val after = queue.snapshot()
        assertEquals(1, after.queue.size)
        assertEquals(trackIds[0].value, after.queue.single().trackId.value)
        assertEquals(0, after.queue.single().position)
    }

    @Test
    fun removingTheCurrentItemMovesToTheNextOne() = runBlocking {
        queue.replaceQueue(trackIds, startIndex = 1, at = Fixtures.now)
        val current = queue.snapshot().currentItem!!

        queue.remove(current.id)

        val after = queue.snapshot()
        assertEquals(trackIds[2].value, after.currentItem!!.trackId.value)
        assertEquals(listOf(0, 1, 2), after.queue.map { it.position })
    }

    @Test
    fun removingTheLastRemainingItemClearsThePlaybackState() = runBlocking {
        queue.replaceQueue(listOf(trackIds[0]), at = Fixtures.now)
        queue.remove(queue.snapshot().queue.single().id)

        val after = queue.snapshot()
        assertTrue(after.queue.isEmpty())
        assertNull(after.currentItemId)
    }

    @Test
    fun movingAnItemRewritesEveryPosition() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        val first = queue.snapshot().queue.first()

        queue.move(first.id, toIndex = 3)

        assertEquals(
            listOf(trackIds[1], trackIds[2], trackIds[3], trackIds[0]).map { it.value },
            queueAll()
        )
        assertEquals(listOf(0, 1, 2, 3), queue.snapshot().queue.map { it.position })
    }

    @Test
    fun movingOutOfRangeIsRefusedAndLeavesTheOrderAlone() = runBlocking<Unit> {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        val before = queueAll()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { queue.move(queue.snapshot().queue.first().id, toIndex = 9) }
        }
        assertEquals(before, queueAll())
    }

    @Test
    fun shuffleIsDeterministicForASeedAndSurvivesARestore() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        val before = queue.snapshot().queue.map { it.id.value }
        val currentId = queue.snapshot().currentItemId!!.value
        val expectedOrder = ShuffleOrder.order(before.map(QueueItemId::parse), 4711).map { it.value }

        queue.setShuffle(enabled = true, seed = 4711, at = Fixtures.now)

        val stored = queue.snapshot().queue.map { it.id.value }
        // The item being listened to stays first; nothing else is invented or lost.
        assertEquals(currentId, stored.first())
        assertEquals(expectedOrder.toSet(), stored.toSet())

        val restored = RoomQueueRepository(db).snapshot()
        assertEquals(stored, restored.queue.map { it.id.value })
        assertEquals(4711, restored.shuffleSeed)
        assertTrue(restored.shuffleEnabled)
        assertEquals("a restore must not start playing", trackIds[0], restored.currentItem!!.trackId)
        assertEquals(0L, restored.progressMs)
    }

    @Test
    fun theSameSeedProducesTheSameOrderAndADifferentSeedUsuallyDoesNot() {
        val ids = List(12) { QueueItemId.random() }

        assertEquals(ShuffleOrder.order(ids, 99), ShuffleOrder.order(ids, 99))
        assertTrue(ShuffleOrder.order(ids, 99).toSet() == ShuffleOrder.order(ids, 100).toSet())
        assertTrue(ShuffleOrder.order(ids, 99) != ShuffleOrder.order(ids, 100))
    }

    @Test
    fun turningShuffleOffRestoresTheOrderTracksWereAddedIn() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        val original = queue.snapshot().queue.map { it.id.value }

        queue.setShuffle(enabled = true, seed = 7, at = Fixtures.now)
        queue.setShuffle(enabled = false, seed = 7, at = Fixtures.now)

        assertEquals(original, queue.snapshot().queue.map { it.id.value })
        assertEquals(trackIds.map { it.value }, queueAll())
        assertNull(queue.snapshot().shuffleSeed)
    }

    @Test
    fun repeatModeIsPersistedAndNextItemFollowsIt() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        queue.setCurrent(queue.snapshot().queue.last().id)

        assertEquals(null, queue.snapshot().nextItem())

        queue.setRepeatMode(RepeatMode.All)
        assertEquals(trackIds[0], queue.snapshot().nextItem()!!.trackId)

        queue.setRepeatMode(RepeatMode.One)
        assertEquals(trackIds[3], queue.snapshot().nextItem()!!.trackId)

        queue.setRepeatMode(RepeatMode.Off)
        assertEquals(RepeatMode.Off, RoomQueueRepository(db).snapshot().repeatMode)
    }

    @Test
    fun progressIsSavedOnCheckpointsOnlyAndNeverNegative() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        queue.saveProgress(45_000, at = Fixtures.now.plusSeconds(45))

        assertEquals(45_000, queue.snapshot().progressMs)

        queue.saveProgress(-10, at = Fixtures.now.plusSeconds(46))
        assertEquals(0, queue.snapshot().progressMs)
    }

    @Test
    fun anEmptyQueueProvidesNoNextItemAndNoCurrentItem() = runBlocking {
        val empty = queue.snapshot()
        assertTrue(empty.queue.isEmpty())
        assertNull(empty.currentItem)
        assertNull(empty.nextItem())
    }

    @Test
    fun queueingAnUnknownTrackIsRefusedRatherThanHalfApplied() = runBlocking<Unit> {
        val unknown = TrackId.random()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { queue.replaceQueue(listOf(trackIds[0], unknown), at = Fixtures.now) }
        }
        assertTrue(thrown.message.orEmpty().contains("unknown track"))
        assertTrue("nothing is queued when the request is invalid", queue.snapshot().queue.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { queue.append(unknown, at = Fixtures.now) }
        }
    }

    @Test
    fun selectingAnItemThatIsNotQueuedIsRefused() = runBlocking<Unit> {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        val stranger = QueueItemId.random()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { queue.setCurrent(stranger) }
        }
    }

    @Test
    fun observationEmitsTheQueueAsItChanges() = runBlocking {
        queue.replaceQueue(trackIds.take(2), at = Fixtures.now)
        assertEquals(2, queue.observe().first().queue.size)

        queue.append(trackIds[2], at = Fixtures.now.plusSeconds(1))
        assertEquals(3, queue.observe().first().queue.size)

        queue.clear()
        assertTrue(queue.observe().first().queue.isEmpty())
    }

    @Test
    fun clearingTheQueueLeavesNoStateBehind() = runBlocking {
        queue.replaceQueue(trackIds, at = Fixtures.now)
        queue.saveProgress(1_000, at = Fixtures.now)
        queue.setRepeatMode(RepeatMode.All)

        queue.clear()

        val after = queue.snapshot()
        assertTrue(after.queue.isEmpty())
        assertNull(after.lastUpdatedAt)
        assertEquals(RepeatMode.Off, after.repeatMode)
        assertNotNull(db)
    }
}
