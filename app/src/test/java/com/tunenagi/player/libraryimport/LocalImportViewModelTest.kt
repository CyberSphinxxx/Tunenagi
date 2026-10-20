package com.tunenagi.player.libraryimport

import com.tunenagi.core.model.LocalFolderImporter
import com.tunenagi.core.model.LocalImportResult
import com.tunenagi.core.model.PlaybackSnapshot
import com.tunenagi.core.model.QueueItem
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.playback.QueueRepository
import com.tunenagi.player.playback.NowPlaying
import com.tunenagi.player.playback.NowPlayingSource
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The import flow's order and its failure states.
 *
 * The importer and the queue are doubles; what is under test is the rule that matters: **persist
 * first, then play**, and that no state pretends a folder was imported when it was not. The scan and
 * the indexing are covered against a real database in `:core`'s `LocalFolderImportTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeImporter(var result: LocalImportResult) : LocalFolderImporter {
        var calls = 0
        var lastUri: String? = null

        override suspend fun importFolder(treeUri: String): LocalImportResult {
            calls++
            lastUri = treeUri
            return result
        }
    }

    /** Records what was written and in which order relative to playback. */
    private class FakeQueue : QueueRepository {
        val events = mutableListOf<String>()
        var replaceFailure: Throwable? = null

        override fun observe(): Flow<PlaybackSnapshot> = flowOf(PlaybackSnapshot())

        override suspend fun snapshot(): PlaybackSnapshot = PlaybackSnapshot()

        override suspend fun replaceQueue(
            trackIds: List<TrackId>,
            startIndex: Int,
            at: Instant
        ): PlaybackSnapshot {
            replaceFailure?.let { throw it }
            events += "queue:${trackIds.size}:$startIndex"
            val items = trackIds.mapIndexed { index, trackId ->
                QueueItem(id = QueueItemId.random(), trackId = trackId, position = index, addedAt = at)
            }
            return PlaybackSnapshot(queue = items, currentItemId = items[startIndex].id)
        }

        override suspend fun append(
            trackId: TrackId,
            at: Instant,
            sourceOverrideId: TrackSourceId?
        ): QueueItem = throw UnsupportedOperationException("not used")

        override suspend fun playNext(
            trackId: TrackId,
            at: Instant,
            sourceOverrideId: TrackSourceId?
        ): QueueItem = throw UnsupportedOperationException("not used")

        override suspend fun remove(itemId: QueueItemId) = Unit

        override suspend fun move(itemId: QueueItemId, toIndex: Int) = Unit

        override suspend fun clear() = Unit

        override suspend fun setCurrent(itemId: QueueItemId) = Unit

        override suspend fun setRepeatMode(mode: RepeatMode) = Unit

        override suspend fun setShuffle(enabled: Boolean, seed: Int, at: Instant) = Unit

        override suspend fun saveProgress(progressMs: Long, at: Instant) = Unit

        override suspend fun shuffledOrder(seed: Int): List<QueueItemId> = emptyList()
    }

    private class FakePlayback(val queue: FakeQueue) : NowPlayingSource {
        val played = mutableListOf<List<TrackId>>()

        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val connection = MutableStateFlow<NowPlayingSource.Connection>(
            NowPlayingSource.Connection.Connected
        )

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun togglePlayPause() = Unit

        override fun retry() = Unit

        override fun playTracks(trackIds: List<TrackId>) {
            queue.events += "play:${trackIds.size}"
            played += trackIds
        }
    }

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun trackIds(count: Int): List<TrackId> =
        List(count) { TrackId.random() }

    @Test
    fun aFolderWithMusicIsPersistedBeforeTheSessionIsToldToPlay() = runTest(dispatcher) {
        val ids = trackIds(3)
        val importer = FakeImporter(LocalImportResult.Imported(trackIds = ids, added = 3))
        val queue = FakeQueue()
        val playback = FakePlayback(queue)
        val viewModel = LocalImportViewModel(importer, queue, playback)

        viewModel.importFolder("content://tree/music")
        advanceUntilIdle()

        assertEquals(
            "a stored queue must exist before the player starts, or a relaunch loses the import",
            listOf("queue:3:0", "play:3"),
            queue.events
        )
        assertEquals(ids, playback.played.single())
        assertEquals(ImportUiState.Ready(3), viewModel.state.value)
        assertEquals("content://tree/music", importer.lastUri)
    }

    @Test
    fun aFolderWithNothingPlayableStartsNothingAndSaysSo() = runTest(dispatcher) {
        val importer = FakeImporter(LocalImportResult.Imported(trackIds = emptyList()))
        val queue = FakeQueue()
        val playback = FakePlayback(queue)
        val viewModel = LocalImportViewModel(importer, queue, playback)

        viewModel.importFolder("content://tree/empty")
        advanceUntilIdle()

        assertEquals(ImportUiState.NoAudio, viewModel.state.value)
        assertTrue("an empty folder must not create a queue", queue.events.isEmpty())
        assertTrue("an empty folder must not start sound", playback.played.isEmpty())
    }

    @Test
    fun aRevokedGrantIsShownAsSuchAndDoesNotTouchTheQueue() = runTest(dispatcher) {
        val queue = FakeQueue()
        val viewModel = LocalImportViewModel(
            FakeImporter(LocalImportResult.PermissionRequired),
            queue,
            FakePlayback(queue)
        )

        viewModel.importFolder("content://tree/music")
        advanceUntilIdle()

        assertEquals(ImportUiState.PermissionRequired, viewModel.state.value)
        assertTrue(queue.events.isEmpty())
    }

    @Test
    fun anImporterThatThrowsBecomesAFailedStateRatherThanACrash() = runTest(dispatcher) {
        val importer = object : LocalFolderImporter {
            override suspend fun importFolder(treeUri: String): LocalImportResult =
                throw IllegalStateException("provider died")
        }
        val queue = FakeQueue()
        val viewModel = LocalImportViewModel(importer, queue, FakePlayback(queue))

        viewModel.importFolder("content://tree/music")
        advanceUntilIdle()

        assertEquals(ImportUiState.Failed, viewModel.state.value)
    }

    @Test
    fun aQueueThatCannotBeWrittenIsReportedAndStartIsNotAttempted() = runTest(dispatcher) {
        val ids = trackIds(2)
        val queue = FakeQueue().apply { replaceFailure = IllegalStateException("disk full") }
        val playback = FakePlayback(queue)
        val viewModel = LocalImportViewModel(
            FakeImporter(LocalImportResult.Imported(trackIds = ids, added = 2)),
            queue,
            playback
        )

        viewModel.importFolder("content://tree/music")
        advanceUntilIdle()

        assertEquals(ImportUiState.Failed, viewModel.state.value)
        assertTrue("playing a queue that was not stored would diverge the two", playback.played.isEmpty())
    }

    @Test
    fun aSecondTapWhileTheScanIsRunningIsIgnored() = runTest(dispatcher) {
        val importer = FakeImporter(LocalImportResult.Imported(trackIds = emptyList()))
        val queue = FakeQueue()
        val viewModel = LocalImportViewModel(importer, queue, FakePlayback(queue))

        viewModel.importFolder("content://tree/music")
        viewModel.importFolder("content://tree/music")
        advanceUntilIdle()

        assertEquals("one scan per tap on an already-running import", 1, importer.calls)
    }

}
