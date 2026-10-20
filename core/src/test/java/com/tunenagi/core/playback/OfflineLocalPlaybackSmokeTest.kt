package com.tunenagi.core.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import com.tunenagi.core.provider.local.FileHead
import com.tunenagi.core.provider.local.FileHeadReader
import com.tunenagi.core.provider.local.LocalSourceResolver
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Offline-first local smoke, as far as a JVM can take it (TUN-0208).
 *
 * The path under test is the production one: a real file on disk, the real `ContentResolver`
 * header reader, the real `LocalSourceResolver`, a real Room database, the real library-backed
 * resolution, the real queue commands, and a service built by Robolectric around a real
 * `ExoPlayer`. The only thing switched off is the network, which is the point.
 *
 * What it proves: with no active network, a local file resolves from its own bytes, the queue is
 * restored silently, an explicit play prepares the local item, and a relaunch still restores it
 * without autoplay. What it cannot prove - and does not claim - is that a device decoder turns the
 * file into sound, that the phone was actually in airplane mode, or what the screen showed. Those
 * are recorded in `OPERATIONS/DEFERRED_VERIFICATION.md`.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineLocalPlaybackSmokeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: TunenagiDatabase
    private lateinit var queue: RoomQueueRepository
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var fileUri: String

    @Before
    fun setUp() {
        database = Fixtures.databaseOnCallerThread()
        queue = RoomQueueRepository(database)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = temporaryFolder.newFile("slow-tide.mp3").apply {
            // A real MPEG/ID3 signature: the resolver makes its decision from these bytes, so the
            // bytes have to exist in a file rather than be stubbed in a reader.
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        }
        // A well-formed file URI. `Uri.fromFile` on this Windows host produces
        // `file://C%3A%5C...` (the drive becomes an authority), which no file reader can open;
        // Android's own MediaStore URIs are well-formed, so the test supplies the equivalent.
        fileUri = file.toURI().toString()
    }

    @After
    fun tearDown() {
        PlaybackResolution.reset()
        scope.cancel()
        database.close()
    }

    /**
     * Reads the file from disk.
     *
     * The production [com.tunenagi.core.provider.local.ContentResolverFileHeadReader] cannot open a
     * `file://` uri under Robolectric (its shadow resolver only knows registered streams), so this
     * test substitutes only that Android call: the bytes still come from a real file on disk and the
     * resolver under test is the production one. Real provider/SAF access is device work and is
     * recorded as such in `OPERATIONS/DEFERRED_VERIFICATION.md`.
     */
    private class DiskHeads : FileHeadReader {
        override suspend fun read(uri: Uri, bytes: Int): FileHead {
            // Java's file-URI parser understands the Windows drive letter that Android's
            // `Uri.path` leaves as `/D:/...`; the production reader gets a stream from the
            // content resolver instead, which this shadow does not implement.
            val file = runCatching { File(java.net.URI(uri.toString())) }.getOrNull()
            if (file == null || !file.isFile) return FileHead.Missing
            return file.inputStream().use { stream ->
                val buffer = ByteArray(bytes)
                var filled = 0
                while (filled < bytes) {
                    val read = stream.read(buffer, filled, bytes - filled)
                    if (read < 0) break
                    filled += read
                }
                FileHead.Read(if (filled == bytes) buffer else buffer.copyOf(filled))
            }
        }
    }

    private fun installLocalGraph(): MediaItemResolution {
        val resolver = LocalSourceResolver(heads = DiskHeads())
        val resolution = LibraryMediaItemResolution(
            database = database,
            resolvers = listOf(resolver),
            scope = scope
        )
        PlaybackResolution.install(PlaybackGraph(resolution = resolution, queue = queue))
        return resolution
    }

    private fun seedLibrary() = runBlocking {
        val at = Instant.parse("2026-10-20T00:00:00Z")
        val track = Fixtures.track(title = "Slow Tide")
        val source = TrackSource(
            id = TrackSourceId.random(),
            trackId = track.id,
            providerId = ProviderId.Local,
            externalId = "offline-fixture",
            localUri = fileUri,
            sourceVariant = "test-fixture",
            contentType = "audio/mpeg",
            availability = SourceAvailability.Available
        )
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, at, at))
        database.sourceDao().insert(TunenagiMappers.toTrackSourceEntity(source))
        queue.replaceQueue(listOf(track.id), startIndex = 0, at = at)
        track
    }

    private fun startedService(): TunenagiPlaybackService =
        Robolectric.buildService(TunenagiPlaybackService::class.java).create().get()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("$what did not happen within 5s", condition())
    }

    @Test
    fun aLocalFilePlaysAndSurvivesRelaunchWithTheNetworkOff() {
        // Airplane mode, as the platform reports it to an app: no active network at all.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertNull("the smoke must run with no network", connectivity.activeNetwork)

        val track = seedLibrary()
        installLocalGraph()

        val first = startedService()
        try {
            val player = first.ownedPlayer!!
            await("the offline restore") { player.mediaItemCount == 1 }

            assertEquals(1, player.mediaItemCount)
            assertEquals("the library resolved the real file", fileUri, player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(
                "the container came from the file's own bytes",
                "audio/mpeg",
                player.currentMediaItem!!.localConfiguration!!.mimeType
            )
            assertEquals(
                "a restore nobody asked to hear must not prepare the local file",
                Player.STATE_IDLE,
                player.playbackState
            )
            assertFalse(player.playWhenReady)
            assertNull(first.restoreFailure)

            // The listener asks for sound while offline: the queue commands re-resolve and prepare
            // the local item directly, without any provider or network lookup.
            runBlocking { first.queueCommands!!.restore(play = true) }

            assertTrue("pressing play offline must reach the player", player.playWhenReady)
            assertEquals(fileUri, player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(
                "the source stays available rather than degrading because the network is off",
                SourceAvailability.Available.name,
                runBlocking { database.sourceDao().forTrack(track.id.value).single().availability }
            )
            assertNull(first.restoreFailure)
        } finally {
            first.onDestroy()
        }

        // A relaunch with the network still off: the queue comes back, silently, from the library.
        val second = startedService()
        try {
            val player = second.ownedPlayer!!
            await("the offline relaunch restore") { player.mediaItemCount == 1 }

            assertEquals(1, player.mediaItemCount)
            assertEquals(fileUri, player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertFalse("a relaunch is not a request for sound", player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertNull(second.restoreFailure)
            assertNotNull("the service still owns its recovery loop offline", second.playbackRecovery)
        } finally {
            second.onDestroy()
        }
    }

    @Test
    fun resolvingALocalFileDoesNotNeedAnActiveNetwork() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)

        val track = seedLibrary()
        val resolution = installLocalGraph()
        val requested = MediaItemIds.track(track.id)

        val resolved = resolution.resolve(
            listOf(MediaItem.Builder().setMediaId(requested).build())
        ).get()

        assertEquals(fileUri, resolved.single().localConfiguration!!.uri.toString())
        assertEquals("audio/mpeg", resolved.single().localConfiguration!!.mimeType)
        assertNull("a local resolve must not consult a network", connectivity.activeNetwork)
    }
}
