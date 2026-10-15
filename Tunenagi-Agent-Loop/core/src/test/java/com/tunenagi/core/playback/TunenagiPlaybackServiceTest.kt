package com.tunenagi.core.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
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
