package com.tunenagi.core.playback

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.Track
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * TUN-0206's machine-verifiable half: the media notification, the headphone/Bluetooth interruption,
 * audio focus ownership, and the one path that may start audio after the app is gone.
 *
 * The part no JVM test can reach is recorded in `OPERATIONS/DEFERRED_VERIFICATION.md`: a real
 * notification on a lockscreen, a real Bluetooth AVRCP round trip, focus loss during a phone call and
 * the audible duck. What is asserted here is the wiring those behaviours depend on, against a real
 * `ExoPlayer`, the real `MediaSessionService` notification machinery and a real Room database.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackControlsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private val service: TunenagiPlaybackService
        get() = Robolectric.buildService(TunenagiPlaybackService::class.java).create().get()

    // ---- notification -------------------------------------------------------------------------

    /**
     * The notification Media3 would post is ours and is quiet by construction.
     *
     * The service installs this provider before its session exists; calling the provider directly is
     * what makes the identity assertable on the JVM, because Media3 only posts a notification while
     * a *connected* controller has a non-idle queue - and a real controller connection needs the
     * platform's service binding, not a Robolectric-instantiated service. The channel is still the
     * real one the platform would create: `IMPORTANCE_LOW` and no sound, so a track change never
     * buzzes.
     */
    @Test
    fun theInstalledProviderBuildsASilentLowImportanceNotification() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val service = service
        try {
            val built = TunenagiNotifications.provider(application).createNotification(
                service.ownedSession!!,
                ImmutableList.of(),
                actionFactory,
                MediaNotification.Provider.Callback { }
            )

            assertEquals(TunenagiNotifications.NOTIFICATION_ID, built.notificationId)
            assertEquals(
                "the notification must name the channel the provider was configured with",
                TunenagiNotifications.CHANNEL_ID,
                built.notification.channelId
            )

            val shadow = shadowOf(application.getSystemService(NotificationManager::class.java))
            val channel = shadow.notificationChannels
                .singleOrNull { it.id == TunenagiNotifications.CHANNEL_ID }
            assertNotNull(
                "the media channel must exist (found: ${shadow.notificationChannels.map { it.id }})",
                channel
            )
            // IMPORTANCE_LOW is what makes the notification silent: the platform does not play a
            // channel's sound at that importance, which is why this is asserted instead of the
            // channel's `sound` field (which still holds the system default uri).
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
            assertFalse("a playback control must not vibrate the phone", channel.shouldVibrate())
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    // ---- headsets and Bluetooth ---------------------------------------------------------------

    /**
     * `SPEC/EDGE_CASES.md`: "wired headphones removed". The player is asked to play, the route goes
     * noisy, and the result must be pause - not a phone that keeps singing in a bag. Reconnecting is
     * then a no-op: nothing in the app starts audio on its own, so a user who unplugged on purpose is
     * not surprised by sound later.
     */
    @Test
    fun unpluggingHeadphonesPausesAndReconnectingDoesNotResume() {
        val service = service
        try {
            val player = service.ownedPlayer!!
            player.playWhenReady = true
            idle()
            assertTrue("precondition: the player was asked to play", player.playWhenReady)

            application.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            idle()

            assertFalse(
                "losing the audio route must pause rather than keep playing into the speaker",
                player.playWhenReady
            )

            application.sendBroadcast(
                Intent(Intent.ACTION_HEADSET_PLUG).putExtra("state", 1)
            )
            idle()
            assertFalse("reconnecting must not start playback by itself", player.playWhenReady)
        } finally {
            service.onDestroy()
        }
    }

    // ---- audio focus --------------------------------------------------------------------------

    /**
     * Audio focus belongs to the player, not to a hand-rolled listener: playing takes
     * `AUDIOFOCUS_GAIN`, and the focus is given back when the player stops.
     *
     * Not on a pause: Media3 deliberately holds focus across play/pause so a rapid pause-and-play
     * does not ping-pong the request, and gives it back when the player is stopped or released (the
     * service's teardown path). The test asserts the policy Media3 actually implements rather than a
     * friendlier one.
     *
     * Robolectric records the platform calls the real player makes. The focus *loss* policy (pause on
     * a call, duck for navigation) is Media3's `AudioFocusManager` acting on those same requests and
     * cannot be delivered by a shadow; it remains on the device list.
     */
    @Test
    fun playingRequestsAudioFocusAndStoppingGivesItBack() {
        val (queue, tracks) = storedQueue(listOf("One"))
        installLibrary(queue)
        val service = service
        try {
            runBlocking { service.queueCommands!!.play(tracks.map { it.id }) }
            idle()

            val audioManager = application.getSystemService(AudioManager::class.java)
            val request = shadowOf(audioManager).lastAudioFocusRequest
            assertNotNull("playing must ask the platform for audio focus", request)
            assertEquals(
                AudioManager.AUDIOFOCUS_GAIN,
                request!!.audioFocusRequest.focusGain
            )

            service.ownedPlayer!!.stop()
            idle()

            assertNotNull(
                "stopping must give audio focus back (state=${service.ownedPlayer!!.playbackState}, " +
                    "playWhenReady=${service.ownedPlayer!!.playWhenReady})",
                shadowOf(audioManager).lastAbandonedAudioFocusRequest
            )
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    // ---- platform resumption ------------------------------------------------------------------

    /**
     * `SPEC/DATA_MODEL.md`'s "media-resumption contract": a trusted system client asking to resume
     * gets the stored queue with the stored item and position. This is the one route that is allowed
     * to start sound without the user opening the app, which is why every other restore path stays
     * silent (TUN-0205).
     */
    @Test
    fun thePlatformResumesTheStoredQueueForATrustedController() {
        val (queue, tracks) = storedQueue(
            titles = listOf("One", "Two", "Three"),
            startIndex = 1,
            progressMs = 42_000
        )
        installLibrary(queue)
        val service = service
        try {
            val resumed = await(
                service.sessionCallback.onPlaybackResumption(service.ownedSession!!, trusted())
            )

            assertNotNull("a stored queue is a valid resumption answer", resumed)
            assertEquals(3, resumed!!.mediaItems.size)
            assertEquals("the item the user was on comes back as the current one", 1, resumed.startIndex)
            assertEquals(42_000L, resumed.startPositionMs)
            assertEquals(
                MediaItemIds.track(tracks[1].id),
                resumed.mediaItems[1].mediaId
            )
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    /** A first launch has nothing to resume, and that is an answer (null), not an error. */
    @Test
    fun resumingAnEmptyStoreAnswersNothing() {
        val queue = RoomQueueRepository(Fixtures.databaseOnCallerThread())
        installLibrary(queue)
        val service = service
        try {
            assertNull(await(service.sessionCallback.onPlaybackResumption(service.ownedSession!!, trusted())))
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    /**
     * Any installed app can connect to a media session; only a trusted one may ask it to produce
     * sound. Refusing with a failure (rather than an empty answer, which looks like "nothing to
     * resume") is what keeps an untrusted controller from learning about - or starting - the queue.
     */
    @Test
    fun resumptionIsRefusedForAnUntrustedController() {
        val (queue, _) = storedQueue(listOf("One"))
        installLibrary(queue)
        val service = service
        try {
            val failure = assertRequestFails(
                service.sessionCallback.onPlaybackResumption(service.ownedSession!!, untrusted())
            )
            assertTrue(
                "the refusal must be a security failure, not a broken queue: $failure",
                failure is SecurityException
            )
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    /**
     * A queue whose first file moved must fail as the typed resolution error, not resume with a
     * broken item that fails later, mid-track, with nothing on screen to explain it.
     */
    @Test
    fun aQueueThatCannotBeResolvedFailsResumptionInsteadOfPlayingSomethingElse() {
        val (queue, _) = storedQueue(listOf("Gone"))
        val refusing = MediaItemResolution { requested ->
            Futures.immediateFailedFuture<List<MediaItem>>(
                UnresolvedMediaItemException(requested.first().mediaId, "the file is not on this device")
            )
        }
        PlaybackResolution.install(PlaybackGraph(resolution = refusing, queue = queue))
        val service = service
        try {
            val failure = assertRequestFails(
                service.sessionCallback.onPlaybackResumption(service.ownedSession!!, trusted())
            )
            assertTrue(
                "the resolution's typed failure must survive the resumption path: $failure",
                failure is UnresolvedMediaItemException
            )
        } finally {
            service.onDestroy()
            PlaybackResolution.reset()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun installLibrary(queue: QueueRepository) {
        PlaybackResolution.install(
            PlaybackGraph(
                resolution = MediaItemResolution { requested ->
                    Futures.immediateFuture(
                        requested.map { it.buildUpon().setUri("file:///music/fixture.mp3").build() }
                    )
                },
                queue = queue
            )
        )
    }

    /** A real Room-backed queue with real tracks: the resumption read is the production one. */
    private fun storedQueue(
        titles: List<String>,
        startIndex: Int = 0,
        progressMs: Long = 0L
    ): Pair<RoomQueueRepository, List<Track>> {
        val database = Fixtures.databaseOnCallerThread()
        val tracks = titles.map { title -> Fixtures.track(title = title) }
        val queue = RoomQueueRepository(database)
        runBlocking {
            seedTracks(database, tracks)
            queue.replaceQueue(tracks.map { it.id }, startIndex, Fixtures.now)
            if (progressMs > 0) queue.saveProgress(progressMs, Fixtures.now)
        }
        return queue to tracks
    }

    private fun seedTracks(database: TunenagiDatabase, tracks: List<Track>) = runBlocking {
        tracks.forEach { database.trackDao().insert(TunenagiMappers.toTrackEntity(it, Fixtures.now, Fixtures.now)) }
    }

    private fun trusted(): MediaSession.ControllerInfo = controllerInfo(trusted = true)

    private fun untrusted(): MediaSession.ControllerInfo = controllerInfo(trusted = false)

    private fun controllerInfo(trusted: Boolean): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            /* packageName = */ "com.tunenagi.player",
            /* pid = */ 1,
            /* uid = */ 1,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            /* trusted = */ trusted,
            android.os.Bundle.EMPTY
        )

    /**
     * Waits for a future whose work runs on the service's main scope.
     *
     * `.get()` alone would deadlock: the service answers on `Dispatchers.Main.immediate`, and the test
     * thread *is* the paused Robolectric main thread. Idling the looper lets the answer land while the
     * wait stays bounded.
     */
    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue("the answer did not arrive within 5s", future.isDone)
        return try {
            future.get()
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        }
    }

    private fun assertRequestFails(future: ListenableFuture<*>): Throwable {
        try {
            await(future)
        } catch (failure: Throwable) {
            // A future's failure can arrive wrapped more than once (Guava's `get` and the coroutine
            // that produced the answer each have a layer); the type the caller promised is the root.
            return generateSequence(failure) { it.cause }.last()
        }
        throw AssertionError("expected the request to fail, but it succeeded")
    }

    /**
     * The notification provider needs an action factory to build play/pause buttons. The actions are
     * inert in this test - no controller is connected to receive them - but a real one is built so
     * the provider follows its production path rather than a stub branch.
     */
    private val actionFactory = object : MediaNotification.ActionFactory {

        override fun createMediaAction(
            session: MediaSession,
            icon: IconCompat,
            title: CharSequence,
            command: Int
        ): NotificationCompat.Action = action(session, icon, title)

        override fun createCustomAction(
            session: MediaSession,
            icon: IconCompat,
            title: CharSequence,
            customAction: String,
            extras: android.os.Bundle
        ): NotificationCompat.Action = action(session, icon, title)

        override fun createCustomActionFromCustomCommandButton(
            session: MediaSession,
            customCommandButton: androidx.media3.session.CommandButton
        ): NotificationCompat.Action = action(
            session,
            IconCompat.createWithResource(application, android.R.drawable.ic_media_play),
            customCommandButton.displayName ?: "Play"
        )

        override fun createMediaActionPendingIntent(
            session: MediaSession,
            command: Long
        ): PendingIntent = PendingIntent.getActivity(
            application,
            command.toInt(),
            Intent(Intent.ACTION_VIEW),
            PendingIntent.FLAG_IMMUTABLE
        )

        private fun action(
            session: MediaSession,
            icon: IconCompat,
            title: CharSequence
        ): NotificationCompat.Action = NotificationCompat.Action.Builder(
            icon,
            title,
            createMediaActionPendingIntent(session, 0L)
        ).build()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
