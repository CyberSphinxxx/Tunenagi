package com.tunenagi.player.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.tunenagi.core.playback.PlaybackFailure
import com.tunenagi.player.ui.shell.MiniPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The snapshots the shell renders, read off a real `ExoPlayer`.
 *
 * The player is real because the claim being tested is that the app never invents playback state:
 * `isPlaying` must be the player's answer. A fake player would let a wrong implementation pass.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun playerWith(metadata: MediaMetadata): ExoPlayer =
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("local:7")
                        .setUri("file:///music/slow-tide.mp3")
                        .setMediaMetadata(metadata)
                        .build()
                )
            }

    @Test
    fun nothingLoadedMeansNoMiniPlayer() {
        val player = ExoPlayer.Builder(context).build()
        try {
            assertNull("an empty player must not produce a snapshot", player.nowPlayingOrNull())
        } finally {
            player.release()
        }
    }

    @Test
    fun theSnapshotCarriesTheTitleArtistAndThePlayersOwnPlayState() {
        val player = playerWith(
            MediaMetadata.Builder().setTitle("Slow Tide").setArtist("Kaori Ito").build()
        )
        try {
            val now = player.nowPlayingOrNull()
            requireNotNull(now)
            assertEquals("local:7", now.mediaId)
            assertEquals("Slow Tide", now.title)
            assertEquals("Kaori Ito", now.artist)
            // Not "we asked it to play": the player itself reports whether audio is being produced.
            assertEquals(player.isPlaying, now.isPlaying)
            assertFalse(now.isPlaying)

            val miniPlayer = now.toMiniPlayerState()
            assertTrue(miniPlayer is MiniPlayerState.Track)
            assertEquals("Slow Tide", (miniPlayer as MiniPlayerState.Track).title)
            assertEquals("Kaori Ito", miniPlayer.artist)
            assertFalse(miniPlayer.isPlaying)
        } finally {
            player.release()
        }
    }

    @Test
    fun aMissingTitleFallsBackToTheMediaIdInsteadOfRenderingNothing() {
        val player = playerWith(MediaMetadata.Builder().build())
        try {
            val now = player.nowPlayingOrNull()
            requireNotNull(now)
            assertEquals("local:7", now.title)
            assertNull("no artist must stay absent rather than become a null", now.artist)

            val miniPlayer = now.toMiniPlayerState() as MiniPlayerState.Track
            assertEquals("", miniPlayer.artist)
        } finally {
            player.release()
        }
    }

    @Test
    fun aPlayersErrorIsTranslatedIntoTheTypedFailureTheBarShows() {
        assertNull("a player with no error must expose none", null.toPlaybackFailure())

        val missing = PlaybackException(
            "fixture is gone",
            null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        ).toPlaybackFailure()
        assertEquals(PlaybackFailure.MissingSource, missing)

        val codec = PlaybackException(
            "unsupported",
            null,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ).toPlaybackFailure()
        assertEquals(PlaybackFailure.UnsupportedFormat, codec)
    }

    @Test
    fun bufferingAndFailureSurviveTheMappingToTheMiniPlayer() {
        val state = NowPlaying(
            mediaId = "local:7",
            title = "Slow Tide",
            artist = "Kaori Ito",
            isPlaying = false,
            isBuffering = true,
            failure = PlaybackFailure.Timeout
        ).toMiniPlayerState() as MiniPlayerState.Track

        assertTrue(state.isBuffering)
        assertEquals(PlaybackFailure.Timeout, state.failure)
    }

    /**
     * The connection's own state machine, which is the part of playback the UI is allowed to see.
     *
     * These assert what is observable on the JVM without a device: `connect()` reports `Connecting`
     * before the service answers, and the teardown paths leave nothing behind. What a *successful*
     * binding looks like end to end is not reachable here — no Android runtime (ADR-008) — and
     * `OPERATIONS/DEFERRED_VERIFICATION.md` records that rather than implying otherwise.
     */
    @Test
    fun aConnectInFlightIsNotReportedAsClosed() {
        val source = PlaybackConnection(context)
        try {
            assertEquals(NowPlayingSource.Connection.Closed, source.connection.value)

            source.connect()

            assertEquals(
                "a connection that is still opening must not look closed",
                NowPlayingSource.Connection.Connecting,
                source.connection.value
            )
        } finally {
            source.disconnect()
        }
    }

    /**
     * Regression for the release contract: a disconnect while the controller was still binding used
     * to release nothing, because the release was guarded on an *attached* controller. The pending
     * future survived it, so every later `connect()` short-circuited on that future and the app
     * stayed closed for the rest of the process with a bound service still behind it.
     */
    @Test
    fun disconnectingWhileConnectingLetsTheNextConnectTryAgain() {
        val source = PlaybackConnection(context)
        try {
            source.connect()
            source.disconnect()
            assertEquals(NowPlayingSource.Connection.Closed, source.connection.value)

            source.connect()

            assertEquals(
                "a disconnect must release the pending controller, or every later connect() is a no-op",
                NowPlayingSource.Connection.Connecting,
                source.connection.value
            )
        } finally {
            source.disconnect()
        }
    }

    @Test
    fun togglingPlayPauseWithNothingAttachedIsAHarmlessNoOp() {
        val source = PlaybackConnection(context)

        source.togglePlayPause()

        assertEquals(NowPlayingSource.Connection.Closed, source.connection.value)
        assertNull(source.nowPlaying.value)
    }

    @Test
    fun retryingWithNothingAttachedIsAHarmlessNoOp() {
        val source = PlaybackConnection(context)

        source.retry()

        assertEquals(NowPlayingSource.Connection.Closed, source.connection.value)
        assertNull(source.nowPlaying.value)
    }
}
