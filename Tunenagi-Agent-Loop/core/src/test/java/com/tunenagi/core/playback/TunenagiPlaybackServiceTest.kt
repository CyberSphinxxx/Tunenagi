package com.tunenagi.core.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tunenagi.core.model.PlaybackSnapshot
import com.tunenagi.core.model.QueueItem
import com.tunenagi.core.model.QueueItemId
import com.tunenagi.core.model.RepeatMode
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The single-owner contract of TUN-0201, checked against the real `ExoPlayer` and the real
 * `MediaLibrarySession` on the JVM (ADR-008: this project has no Android runtime to test on).
 *
 * What this file does *not* prove is written down in `OPERATIONS/DEFERRED_VERIFICATION.md`: real
 * codecs, real audio focus, background survival and the media notification are not reachable from a
 * JVM test, and nothing here pretends otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class TunenagiPlaybackServiceTest {

    private fun startedService(): TunenagiPlaybackService =
        Robolectric.buildService(TunenagiPlaybackService::class.java).create().get()

    /**
     * A real Room database, created only when a test needs one.
     *
     * The queue commands must be built over *some* repository, and the assertions that matter here
     * (which player they use, and that they are dropped) do not depend on persistence, so this is the
     * one place a simpler in-memory queue is used instead of dragging Room into every service test.
     */
    private class InMemoryQueueRepository : QueueRepository {
        private var snapshot = PlaybackSnapshot()

        override fun observe(): Flow<PlaybackSnapshot> = flowOf(snapshot)

        override suspend fun snapshot(): PlaybackSnapshot = snapshot

        override suspend fun replaceQueue(
            trackIds: List<TrackId>,
            startIndex: Int,
            at: Instant
        ): PlaybackSnapshot {
            val items = trackIds.mapIndexed { index, trackId ->
                QueueItem(
                    id = QueueItemId.random(),
                    trackId = trackId,
                    position = index,
                    addedAt = at
                )
            }
            snapshot = PlaybackSnapshot(queue = items, currentItemId = items[startIndex].id)
            return snapshot
        }

        override suspend fun append(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId?): QueueItem =
            throw UnsupportedOperationException("not needed by this test")

        override suspend fun playNext(trackId: TrackId, at: Instant, sourceOverrideId: TrackSourceId?): QueueItem =
            throw UnsupportedOperationException("not needed by this test")

        override suspend fun remove(itemId: QueueItemId) = Unit

        override suspend fun move(itemId: QueueItemId, toIndex: Int) = Unit

        override suspend fun clear() {
            snapshot = PlaybackSnapshot()
        }

        override suspend fun setCurrent(itemId: QueueItemId) = Unit

        override suspend fun setRepeatMode(mode: RepeatMode) {
            snapshot = snapshot.copy(repeatMode = mode)
        }

        override suspend fun setShuffle(enabled: Boolean, seed: Int, at: Instant) = Unit

        override suspend fun saveProgress(progressMs: Long, at: Instant) {
            snapshot = snapshot.copy(progressMs = progressMs)
        }

        override suspend fun shuffledOrder(seed: Int): List<QueueItemId> = snapshot.queue.map { it.id }
    }

    private fun controllerInfo(trusted: Boolean): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            /* packageName = */ "com.tunenagi.player",
            /* pid = */ 1,
            /* uid = */ 1,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            /* trusted = */ trusted,
            Bundle.EMPTY
        )

    @Test
    fun theServiceOwnsExactlyOnePlayerAndOneSessionOverIt() {
        val service = startedService()
        try {
            val player = service.ownedPlayer
            val session = service.ownedSession
            assertNotNull("the service must own a player", player)
            assertNotNull("the service must own a session", session)

            // The identity assertions are the point: one player, one session, and the session's
            // player *is* the service's player. A second ExoPlayer anywhere would leave two objects
            // competing for the audio output, which is the failure the phase document names.
            assertSame(player, session!!.player)
            assertEquals(TunenagiPlaybackService.SESSION_ID, session.id)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun everyControllerReceivesTheSameSession() {
        val service = startedService()
        try {
            val first = service.onGetSession(controllerInfo(trusted = true))
            val second = service.onGetSession(controllerInfo(trusted = true))

            assertNotNull(first)
            assertSame("a second controller must not get a second session", first, second)
            assertSame(service.ownedSession, first)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun destroyingTheServiceReleasesThePlayerItOwned() {
        val service = startedService()
        val engine = service.ownedEngine
        assertNotNull(engine)
        assertFalse(engine!!.isReleased)

        service.onDestroy()

        assertTrue("the service must release the player it built", engine.isReleased)
        assertNull("the service must drop the released engine", service.ownedEngine)
        assertNull("the service must drop the released player", service.ownedPlayer)
        assertNull("the service must drop the released session", service.ownedSession)

        // A destroy path that runs twice (stop, then a later destroy) must not be a crash:
        // nothing can report a failure during service shutdown.
        engine.release()
        assertTrue(engine.isReleased)
    }

    @Test
    fun recreatingTheServiceBuildsAFreshPlayerRatherThanReusingAReleasedOne() {
        val first = startedService()
        val firstPlayer = first.ownedPlayer
        first.onDestroy()

        val second = startedService()
        try {
            assertNotNull(second.ownedPlayer)
            assertFalse(
                "the second service must not reuse a released player",
                second.ownedPlayer === firstPlayer
            )
        } finally {
            second.onDestroy()
        }
    }

    @Test
    fun anItemWithoutAPlayableSourceFailsWithATypedError() {
        val service = startedService()
        try {
            val requested = MediaItem.Builder().setMediaId("local:not-imported-yet").build()

            val future = service.sessionCallback.onAddMediaItems(
                service.ownedSession!!,
                controllerInfo(trusted = true),
                listOf(requested)
            )

            val failure = assertRequestFails(future)
            assertTrue(
                "the failure must be the typed one, so the UI can report it: $failure",
                failure is UnresolvedMediaItemException
            )
            assertEquals("local:not-imported-yet", (failure as UnresolvedMediaItemException).mediaId)

            // Nothing was half-applied: a rejected request must not leave a broken entry in the
            // player's queue that only fails later, mid-playback.
            assertEquals(0, service.ownedPlayer!!.mediaItemCount)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun anItemThatAlreadyCarriesAPlayableSourceIsPassedThrough() {
        val service = startedService()
        try {
            val requested = MediaItem.Builder()
                .setMediaId("local:1")
                .setUri("file:///music/fixture.mp3")
                .build()

            val resolved = service.sessionCallback.onAddMediaItems(
                service.ownedSession!!,
                controllerInfo(trusted = true),
                listOf(requested)
            ).get()

            assertEquals(1, resolved.size)
            assertEquals(requested, resolved.single())
        } finally {
            service.onDestroy()
        }
    }

    /**
     * The wiring itself, which no other test covers: the OS creates this service, so the resolution
     * it resolves through is installed before it exists (ADR-013). If the service stopped reading
     * that install point, its session would quietly fall back to "only items that already carry a
     * uri" and every library item would fail — the exact defect this test fails on.
     */
    @Test
    fun theSessionResolvesThroughTheGraphTheHostInstalled() {
        val installed = PlaybackGraph(
            resolution = MediaItemResolution { requested ->
                Futures.immediateFuture(
                    requested.map { it.buildUpon().setUri("content://installed/1").build() }
                )
            }
        )
        PlaybackResolution.install(installed)
        try {
            val service = startedService()
            try {
                val requested = MediaItem.Builder()
                    .setMediaId("tunenagi:source:${UUID.randomUUID()}")
                    .build()

                val resolved = service.sessionCallback.onAddMediaItems(
                    service.ownedSession!!,
                    controllerInfo(trusted = true),
                    listOf(requested)
                ).get()

                assertEquals(
                    "content://installed/1",
                    resolved.single().localConfiguration!!.uri.toString()
                )
                // A graph with a resolution but no library leaves the session without a queue to
                // persist, which is a state the session reports instead of assuming away.
                assertNull(service.queueCommands)
            } finally {
                service.onDestroy()
            }
        } finally {
            PlaybackResolution.reset()
        }
    }

    /**
     * A host that installs a library gets the queue commands built over *its* player, and they do not
     * outlive it: a command holding a released player would be a seek into nothing.
     */
    @Test
    fun theServiceBuildsQueueCommandsOverItsOwnPlayerAndDropsThemWithIt() {
        val earlierService = startedService()
        val earlierPlayer = earlierService.ownedPlayer!!
        earlierService.onDestroy()
        val resolution = MediaItemResolution { requested ->
            Futures.immediateFuture(
                requested.map { it.buildUpon().setUri("file:///music/fixture.mp3").build() }
            )
        }
        PlaybackResolution.install(PlaybackGraph(resolution = resolution, queue = InMemoryQueueRepository()))
        try {
            val service = startedService()
            try {
                val commands = service.queueCommands
                assertNotNull("a host with a library must get queue commands", commands)

                val snapshot = runBlocking { commands!!.play(listOf(TrackId.random())) }

                assertEquals(1, snapshot.queue.size)
                assertEquals(1, service.ownedPlayer!!.mediaItemCount)
                assertFalse(
                    "a recreated service must not command the previous player",
                    earlierPlayer === service.ownedPlayer
                )
            } finally {
                val engine = service.ownedEngine!!
                service.onDestroy()
                assertNull("commands must not survive their player", service.queueCommands)
                assertTrue(engine.isReleased)
            }
        } finally {
            PlaybackResolution.reset()
        }
    }

    /**
     * The other half of that contract: with nothing installed, a session refuses an item it cannot
     * resolve on its own rather than inventing a uri for it.
     */
    @Test
    fun aHostThatInstallsNothingGetsTheRefusingDefault() {
        PlaybackResolution.reset()
        val mediaId = "tunenagi:source:${UUID.randomUUID()}"
        val requested = MediaItem.Builder().setMediaId(mediaId).build()

        val failure = assertRequestFails(
            PlaybackResolution.current().resolution.resolve(listOf(requested))
        )

        assertTrue(failure is UnresolvedMediaItemException)
        assertEquals(mediaId, (failure as UnresolvedMediaItemException).mediaId)
        assertTrue(failure.error == null && failure.availability == null)
    }

    @Test
    fun anUntrustedBrowserCannotReadTheLibrary() {
        val service = startedService()
        try {
            val denied = service.sessionCallback.onGetLibraryRoot(
                session = service.ownedSession!!,
                browser = controllerInfo(trusted = false),
                params = null
            ).get()

            assertEquals(SessionError.ERROR_PERMISSION_DENIED, denied.resultCode)
            assertNull("a denied request must not carry a media item", denied.value)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun aTrustedBrowserSeesARootWithNoInventedChildren() {
        val service = startedService()
        val session: MediaLibraryService.MediaLibrarySession = service.ownedSession!!
        val callback = service.sessionCallback
        try {
            val root = callback.onGetLibraryRoot(session, controllerInfo(trusted = true), null).get()
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertEquals(TunenagiMediaLibraryCallback.ROOT_MEDIA_ID, root.value!!.mediaId)
            assertEquals(java.lang.Boolean.TRUE, root.value!!.mediaMetadata.isBrowsable)

            val children = callback.onGetChildren(
                session = session,
                browser = controllerInfo(trusted = true),
                parentId = TunenagiMediaLibraryCallback.ROOT_MEDIA_ID,
                page = 0,
                pageSize = 50,
                params = null
            ).get()
            assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
            assertTrue("phase 02 has discovered no local media yet", children.value!!.isEmpty())

            // An unknown parent is a caller error, not an empty library.
            val unknown = callback.onGetChildren(
                session = session,
                browser = controllerInfo(trusted = true),
                parentId = "tunenagi:unknown",
                page = 0,
                pageSize = 50,
                params = null
            ).get()
            assertEquals(SessionError.ERROR_BAD_VALUE, unknown.resultCode)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun aControllerInfoThatIsNotTrustedIsRecognisedAsSuch() {
        // Guards the test seam itself: if `createTestOnlyControllerInfo` ever stopped carrying the
        // trusted flag, the security assertions above would pass for the wrong reason.
        assertTrue(controllerInfo(trusted = true).isTrusted)
        assertFalse(controllerInfo(trusted = false).isTrusted)
    }

    private fun assertRequestFails(future: ListenableFuture<*>): Throwable {
        try {
            future.get()
        } catch (expected: ExecutionException) {
            return expected.cause ?: expected
        }
        throw AssertionError("expected the request to fail, but it succeeded")
    }
}
