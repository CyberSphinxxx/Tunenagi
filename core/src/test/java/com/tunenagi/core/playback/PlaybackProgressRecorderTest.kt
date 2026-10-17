package com.tunenagi.core.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.PlaybackSnapshot
import com.tunenagi.core.model.QueueItem
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The write-cadence contract of `SPEC/DATA_MODEL.md`, against a real player and a real database.
 *
 * The subject is [PlaybackProgressRecorder], so the interesting question is not "does the position
 * reach the database" (one checkpoint proves that) but *how often* it does - which needs a repository
 * that can be counted, and a clock that can be moved by hand. Both are supplied here; the player, the
 * queue and the room the position is written into are the real ones.
 *
 * A hard kill is the case this cannot test: no JVM test can kill a process mid-playback, so what is
 * asserted instead is the property that makes the loss bounded - a checkpoint whose interval has not
 * elapsed is refused, and one that follows an event is not.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackProgressRecorderTest {

    private val at: Instant = Instant.parse("2026-10-17T00:00:00Z")
    private val database: TunenagiDatabase = Fixtures.databaseOnCallerThread()
    private val room = RoomQueueRepository(database)
    private val queue = CountingQueue(room)
    private val player: Player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set to drive the interval gate; `null` follows Robolectric's clock, which `idleFor` moves. */
    private var frozenNowMs: Long? = null

    private val resolution = MediaItemResolution { requested ->
        Futures.immediateFuture(requested.map { it.buildUpon().setUri(FIXTURE_URI).build() })
    }

    private fun recorder(intervalMs: Long = 1_000L) = PlaybackProgressRecorder(
        player = player,
        queue = queue,
        scope = scope,
        intervalMs = intervalMs,
        clock = { at },
        monotonicMs = { frozenNowMs ?: SystemClock.elapsedRealtime() }
    )

    private val commands = PlaybackQueueCommands(
        player = player,
        queue = room,
        resolution = resolution,
        clock = { at }
    )

    @After
    fun tearDown() {
        scope.cancel()
        player.release()
    }

    private suspend fun queuedTracks(titles: List<String>): List<TrackId> =
        titles.map { title ->
            val track = Fixtures.track(title = title)
            database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
            track.id
        }

    // ---- the interval gate ------------------------------------------------------------------------

    @Test
    fun checkpointsInsideTheIntervalAreRefused() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two", "Three")))
        val recorder = recorder(intervalMs = 10_000L)
        frozenNowMs = 1_000_000L

        val first = recorder.checkpoint()
        repeat(4) { assertNull("the interval has not elapsed", recorder.checkpoint()) }
        frozenNowMs = 1_009_999L
        assertNull("one millisecond short is still inside the interval", recorder.checkpoint())
        frozenNowMs = 1_010_000L
        val second = recorder.checkpoint()

        assertEquals(2, queue.progressWrites.size)
        assertEquals(listOf(first, second), queue.progressWrites)
    }

    /**
     * The point of the cadence: a position that changes on every audio frame is only *stored* once per
     * interval, so the write count is a property of time rather than of how long a track plays.
     */
    @Test
    fun manyPositionChangesInsideOneIntervalProduceOneWrite() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two")))
        val recorder = recorder(intervalMs = 10_000L)
        frozenNowMs = 0L

        recorder.checkpoint()
        listOf(250L, 500L, 750L, 1_000L, 1_250L).forEach { elapsed ->
            frozenNowMs = elapsed
            recorder.checkpoint()
        }

        assertEquals(1, queue.progressWrites.size)
    }

    @Test
    fun aFlushIgnoresTheInterval() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two")))
        val recorder = recorder(intervalMs = 10_000L)
        frozenNowMs = 0L
        recorder.checkpoint()

        frozenNowMs = 1L
        recorder.flush()

        assertEquals(2, queue.progressWrites.size)
    }

    /**
     * The event-driven half, through the real listener: Media3 delivers these on the application's
     * main thread, the recorder launches the write from there, and the direct-executor fixture makes
     * it finish before the assertion - which is also the reason the assertion can be `assertEquals`
     * rather than a wait.
     */
    @Test
    fun anEventCheckpointsImmediatelyEvenInsideTheInterval() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two")))
        val recorder = recorder(intervalMs = 10_000L)
        frozenNowMs = 0L
        recorder.start()
        recorder.checkpoint()
        // Whatever the player has done on its own by now is not this test's subject; the pause that
        // follows is, so the count is measured against what the direct checkpoint left behind. (The
        // fixture uri is not a real file, so nothing here ever starts playing.)
        shadowOf(Looper.getMainLooper()).idle()
        val beforeThePause = queue.progressWrites.size

        recorder.onIsPlayingChanged(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("a pause is an explicit checkpoint", beforeThePause + 1, queue.progressWrites.size)
        recorder.stop()
    }

    @Test
    fun theTickerCheckpointsWhileTheRecorderRuns() {
        runBlocking { commands.play(queuedTracks(listOf("One", "Two"))) }
        val recorder = recorder(intervalMs = 1_000L)
        frozenNowMs = 0L
        recorder.start()

        assertTrue(recorder.isRunning)
        // The delay is real looper time, so the main looper is advanced by hand; the frozen clock is
        // moved with it because the interval gate is the rule under test, not the scheduler.
        repeat(3) { step ->
            frozenNowMs = (step + 1) * 1_000L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        }

        assertTrue(
            "at least one write per interval, three intervals",
            queue.progressWrites.size >= 3
        )
        recorder.stop()
        assertFalse(recorder.isRunning)

        val writesAfterStop = queue.progressWrites.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
        assertEquals("a stopped recorder writes nothing more", writesAfterStop, queue.progressWrites.size)
    }

    // ---- attribution ----------------------------------------------------------------------------

    /**
     * The defect this rule exists to prevent: Media3 advances to the next item when a track ends, and
     * the position then belongs to the *new* item. Storing it against the old one is how a relaunch
     * resumes the wrong song at the wrong place.
     */
    @Test
    fun theStoredCurrentItemFollowsThePlayer() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two", "Three")))
        val firstItem = room.snapshot().queue.first()
        val recorder = recorder()
        recorder.start()
        frozenNowMs = 0L

        player.seekToNextMediaItem()
        shadowOf(Looper.getMainLooper()).idle()

        val stored = room.snapshot()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("the stored current item moved with the player", 1, stored.queue.indexOfFirst { it.id == stored.currentItemId })
        assertTrue("and it is not the item that was current before", stored.currentItemId != firstItem.id)

        recorder.stop()
    }

    @Test
    fun aCheckpointWithNothingToAttributeWritesNothing() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two")))
        val recorder = recorder()
        frozenNowMs = 0L
        player.clearMediaItems()

        assertNull(recorder.checkpoint(force = true))
        assertNull(recorder.flush())

        assertEquals(emptyList<Long>(), queue.progressWrites)
    }

    @Test
    fun anEmptyStoreWritesNothing() = runBlocking {
        val recorder = recorder()
        frozenNowMs = 0L

        assertEquals(null, recorder.checkpoint(force = true))

        assertEquals(emptyList<Long>(), queue.progressWrites)
    }

    /**
     * What is stored is the player's own position, bounded, attributed to the stored current item -
     * the checkpoint does not invent a position and does not write one for an item it cannot name.
     */
    @Test
    fun theStoredPositionIsThePlayersOwnBoundedPosition() = runBlocking {
        commands.play(queuedTracks(listOf("One", "Two")))
        val recorder = recorder()
        frozenNowMs = 0L

        val written = recorder.flush()

        val expected = PlaybackProgressRecorder.resumePositionMs(player.playbackState, player.currentPosition)
        assertEquals(expected, written)
        assertEquals(expected, room.snapshot().progressMs)
        assertEquals(room.snapshot().queue.first().id, room.snapshot().currentItemId)
    }

    // ---- the ended-item rule, as a decision rather than a state ------------------------------------

    @Test
    fun aFinishedItemStoresItsStartAndNotItsEnd() {
        assertEquals(0L, PlaybackProgressRecorder.resumePositionMs(Player.STATE_ENDED, 190_000L))
        assertEquals(0L, PlaybackProgressRecorder.resumePositionMs(Player.STATE_ENDED, 0L))
        assertEquals(12_345L, PlaybackProgressRecorder.resumePositionMs(Player.STATE_READY, 12_345L))
        assertEquals("a negative position is clamped, never stored", 0L, PlaybackProgressRecorder.resumePositionMs(Player.STATE_READY, -1L))
    }

    private companion object {
        const val FIXTURE_URI = "file:///fixtures/recorder-test.mp3"
    }

    /**
     * A repository that counts progress writes.
     *
     * Every other call is delegated untouched, so what the recorder records is still a real Room
     * snapshot; only the write *count* is the test's own concern.
     */
    private class CountingQueue(private val delegate: QueueRepository) : QueueRepository {

        val progressWrites = mutableListOf<Long>()

        override fun observe(): Flow<PlaybackSnapshot> = delegate.observe()

        override suspend fun snapshot(): PlaybackSnapshot = delegate.snapshot()

        override suspend fun replaceQueue(trackIds: List<TrackId>, startIndex: Int, at: Instant): PlaybackSnapshot =
            delegate.replaceQueue(trackIds, startIndex, at)

        override suspend fun append(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId?): QueueItem =
            delegate.append(trackId, at, sourceOverrideId)

        override suspend fun playNext(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId?): QueueItem =
            delegate.playNext(trackId, at, sourceOverrideId)

        override suspend fun remove(itemId: QueueItemId) = delegate.remove(itemId)

        override suspend fun move(itemId: QueueItemId, toIndex: Int) = delegate.move(itemId, toIndex)

        override suspend fun clear() = delegate.clear()

        override suspend fun setCurrent(itemId: QueueItemId) = delegate.setCurrent(itemId)

        override suspend fun setRepeatMode(mode: RepeatMode) = delegate.setRepeatMode(mode)

        override suspend fun setShuffle(enabled: Boolean, seed: Int, at: Instant) = delegate.setShuffle(enabled, seed, at)

        override suspend fun saveProgress(progressMs: Long, at: Instant) {
            progressWrites += progressMs
            delegate.saveProgress(progressMs, at)
        }

        override suspend fun shuffledOrder(seed: Int): List<QueueItemId> = delegate.shuffledOrder(seed)
    }
}
