package com.tunenagi.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.ShuffleOrder
import com.tunenagi.core.model.TrackId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The queue commands, as state transitions between the player and the library.
 *
 * Both are real here: a real `ExoPlayer` (as TUN-0201's tests use) and a real Room database with the
 * real queue repository. The only supplied piece is the *resolution*, because resolving is TUN-0203's
 * subject and `core.playback` must not know a provider exists (rule B11) — so the tests install a
 * resolution that hands each item a local uri, exactly as the real library-backed one would.
 *
 * What a JVM test cannot show is stated instead of implied: the fixture uri points at no real file,
 * so no audio is decoded, and a player that has not prepared anything cannot be asked where it is -
 * which is why the previous-track *threshold* is tested through the injected position seam and the
 * seek checkpoint is asserted in the persisted snapshot rather than in `currentPosition`.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackQueueCommandsTest {

    private val at: Instant = Instant.parse("2026-10-16T00:00:00Z")
    private val database: TunenagiDatabase = Fixtures.database()
    private val queue = RoomQueueRepository(database)
    private val player: Player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()

    /** Set to make the next resolve fail, which is how the failure path is exercised. */
    private var failure: Throwable? = null

    private val resolution = MediaItemResolution { requested ->
        val error = failure
        if (error != null) {
            Futures.immediateFailedFuture<List<MediaItem>>(error)
        } else {
            Futures.immediateFuture(requested.map { it.buildUpon().setUri(FIXTURE_URI).build() })
        }
    }

    private fun commands(positionMs: () -> Long = { 0L }) = PlaybackQueueCommands(
        player = player,
        queue = queue,
        resolution = resolution,
        positionMs = positionMs,
        clock = { at }
    )

    @After
    fun releasePlayer() {
        player.release()
    }

    private suspend fun addTrack(title: String): TrackId {
        val track = Fixtures.track(title = title)
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
        return track.id
    }

    private fun mediaIds(): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private suspend fun currentTrackId(): TrackId? = queue.snapshot().currentItem?.trackId

    private fun idAt(index: Int, ids: List<TrackId>): String = MediaItemIds.track(ids[index])

    // ---- starting and editing a queue ------------------------------------------------------------

    @Test
    fun playingAListPutsTheSameQueueInTheLibraryAndInThePlayer() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))

        val snapshot = commands().play(ids, startIndex = 1)

        assertEquals(3, player.mediaItemCount)
        assertEquals(ids.map { MediaItemIds.track(it) }, mediaIds())
        assertEquals(1, player.currentMediaItemIndex)
        assertTrue("a queue the user asked to play must actually play", player.playWhenReady)
        assertEquals(ids[1], snapshot.currentItem?.trackId)
        assertEquals(listOf(0, 1, 2), snapshot.queue.map { it.position })
    }

    @Test
    fun playNextInsertsDirectlyAfterTheCurrentItemWithoutInterrupting() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val extra = addTrack("Extra")
        val commands = commands()
        commands.play(ids)

        commands.playNext(extra)

        assertEquals(
            listOf(idAt(0, ids), MediaItemIds.track(extra), idAt(1, ids), idAt(2, ids)),
            mediaIds()
        )
        assertEquals("the playing track must not change", ids[0], currentTrackId())
        assertTrue(player.playWhenReady)
    }

    @Test
    fun addingToTheEndAppendsWithoutChangingWhatIsPlaying() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val extra = addTrack("Extra")
        val commands = commands()
        commands.play(ids)
        val before = player.currentMediaItemIndex

        commands.addToEnd(extra)

        assertEquals(3, player.mediaItemCount)
        assertEquals(MediaItemIds.track(extra), mediaIds().last())
        assertEquals(before, player.currentMediaItemIndex)
        assertEquals(ids[0], currentTrackId())
    }

    @Test
    fun removingTheCurrentItemAdvancesToTheSuccessorSafely() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val commands = commands()
        commands.play(ids, startIndex = 1)

        commands.removeCurrent()

        assertEquals(2, player.mediaItemCount)
        assertEquals(ids[2], currentTrackId())
        assertEquals(1, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
    }

    /** The documented bound: the last item removed leaves an empty player, not a stopped ghost. */
    @Test
    fun removingTheLastItemEmptiesThePlayerSoControlsCanHide() = runBlocking {
        val only = addTrack("Only")
        val commands = commands()
        commands.play(listOf(only))

        val snapshot = commands.removeCurrent()

        assertEquals(0, player.mediaItemCount)
        assertTrue(snapshot.queue.isEmpty())
        assertNull(snapshot.currentItemId)
        assertNull(currentTrackId())
    }

    @Test
    fun removingSomethingElseKeepsTheCurrentTrackWhereItIs() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val commands = commands()
        val played = commands.play(ids, startIndex = 1)
        val third = played.queue.last().id

        commands.remove(third)

        assertEquals(2, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(ids[1], currentTrackId())
    }

    @Test
    fun reorderingWhilePlayingKeepsTheSameItemCurrent() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val commands = commands()
        val played = commands.play(ids, startIndex = 1)
        val first = played.queue.first().id

        commands.move(first, toIndex = 2)

        assertEquals(
            listOf(idAt(1, ids), idAt(2, ids), idAt(0, ids)),
            mediaIds()
        )
        assertEquals(ids[1], currentTrackId())
        assertEquals("the current item follows the reorder", 0, player.currentMediaItemIndex)
    }

    @Test
    fun clearingEmptiesBothSides() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        commands.play(ids)

        commands.clear()

        assertEquals(0, player.mediaItemCount)
        assertTrue(queue.snapshot().queue.isEmpty())
        assertFalse(player.playWhenReady)
    }

    // ---- seeking and next/previous ---------------------------------------------------------------

    @Test
    fun seekingCheckpointsThePositionForARestore() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        commands.play(ids)

        val snapshot = commands.seekTo(12_000)

        assertEquals(12_000L, snapshot.progressMs)
        assertEquals("a seek is written through, not only held in the player", 12_000L, queue.snapshot().progressMs)
    }

    @Test
    fun aNegativeSeekIsClampedRatherThanSentToThePlayer() = runBlocking {
        val commands = commands()
        commands.play(listOf(addTrack("One")))

        val snapshot = commands.seekTo(-5_000)

        assertEquals(0L, snapshot.progressMs)
    }

    @Test
    fun nextAtTheEndWithRepeatOffStopsInsteadOfCycling() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        commands.play(ids, startIndex = 1)

        val snapshot = commands.next()

        assertEquals("looping back would be the endless-cycling trap", ids[1], snapshot.currentItem?.trackId)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun nextWithRepeatAllWrapsToTheFirstItem() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        commands.play(ids, startIndex = 1)
        commands.cycleRepeat()

        val snapshot = commands.next()

        assertEquals(ids[0], snapshot.currentItem?.trackId)
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun nextWithRepeatOneRepeatsTheCurrentItemRatherThanAdvancing() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        commands.play(ids)
        commands.cycleRepeat()
        commands.cycleRepeat()

        val snapshot = commands.next()

        assertEquals(ids[0], snapshot.currentItem?.trackId)
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun previousBeyondTheThresholdRestartsTheCurrentTrack() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val commands = commands(positionMs = { 5_000 })
        commands.play(ids, startIndex = 1)

        val snapshot = commands.previous()

        assertEquals(ids[1], snapshot.currentItem?.trackId)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun previousBelowTheThresholdStepsBackOneItem() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"))
        val commands = commands(positionMs = { 1_000 })
        commands.play(ids, startIndex = 1)

        val snapshot = commands.previous()

        assertEquals(ids[0], snapshot.currentItem?.trackId)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun previousAtTheFirstItemRestartsInsteadOfWrappingToTheEnd() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands(positionMs = { 500 })
        commands.play(ids)

        val snapshot = commands.previous()

        assertEquals(ids[0], snapshot.currentItem?.trackId)
        assertEquals(0, player.currentMediaItemIndex)
    }

    // ---- repeat and shuffle ----------------------------------------------------------------------

    @Test
    fun cycleRepeatWalksOffAllAndOneIntoBothThePlayerAndTheLibrary() = runBlocking {
        val commands = commands()

        assertEquals(RepeatMode.Off, queue.snapshot().repeatMode)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)

        assertEquals(RepeatMode.All, commands.cycleRepeat())
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertEquals(RepeatMode.All, queue.snapshot().repeatMode)

        assertEquals(RepeatMode.One, commands.cycleRepeat())
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)

        assertEquals(RepeatMode.Off, commands.cycleRepeat())
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        assertEquals(RepeatMode.Off, queue.snapshot().repeatMode)
    }

    @Test
    fun shuffleMaterialisesTheSeededOrderWithTheCurrentItemFirst() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"), addTrack("Four"), addTrack("Five"))
        val commands = commands()
        val played = commands.play(ids)
        val storedIds = played.queue.map { it.id }
        val seed = 20_261_016

        val snapshot = commands.setShuffle(enabled = true, seed = seed)

        val expected = listOf(storedIds.first()) +
            ShuffleOrder.order(storedIds, seed).filter { it != storedIds.first() }
        assertEquals(
            "the stored order is the permutation a restore must reproduce",
            expected,
            snapshot.queue.map { it.id }
        )
        assertEquals(
            "the player must be given that order rather than shuffling a second time",
            expected.map { id -> snapshot.queue.first { it.id == id }.trackId }.map { MediaItemIds.track(it) },
            mediaIds()
        )
        assertFalse("the permutation lives in the queue, so the player's own shuffle stays off", player.shuffleModeEnabled)
        assertTrue(snapshot.shuffleEnabled)
        assertEquals(seed, snapshot.shuffleSeed)
        assertEquals(ids[0], currentTrackId())
    }

    @Test
    fun turningShuffleOffRestoresTheOrderTheUserBuilt() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"), addTrack("Three"), addTrack("Four"))
        val commands = commands()
        commands.play(ids)
        commands.setShuffle(enabled = true, seed = 7)

        val restored = commands.setShuffle(enabled = false, seed = 7)

        assertEquals(ids.map { MediaItemIds.track(it) }, mediaIds())
        assertFalse(restored.shuffleEnabled)
        assertNull(restored.shuffleSeed)
        assertEquals(ids[0], currentTrackId())
    }

    // ---- failure path ----------------------------------------------------------------------------

    /**
     * A resolve failure must be visible, and it must not desynchronise the library from the player
     * for good: the stored queue is ahead of the player at that moment, and the next command re-applies
     * it (which is also what TUN-0205's restore does after a restart).
     */
    @Test
    fun anUnresolvableItemFailsTheCommandAndTheNextCommandSyncsThePlayer() = runBlocking {
        val ids = listOf(addTrack("One"), addTrack("Two"))
        val commands = commands()
        failure = UnresolvedMediaItemException(MediaItemIds.track(ids[0]), error = ProviderError.UnsupportedFormat)

        val failureSeen = runCatching { commands.play(ids) }.exceptionOrNull()

        assertTrue(failureSeen is UnresolvedMediaItemException)
        assertEquals(ProviderError.UnsupportedFormat, (failureSeen as UnresolvedMediaItemException).error)
        assertEquals(0, player.mediaItemCount)

        failure = null
        val extra = addTrack("Extra")
        commands.addToEnd(extra)

        assertEquals(3, player.mediaItemCount)
        assertEquals(MediaItemIds.track(extra), mediaIds().last())
    }

    private companion object {
        /** The lyrics-free placeholder the stubbed resolution hands the player; no file is opened. */
        const val FIXTURE_URI = "file:///music/fixture.mp3"
    }
}
