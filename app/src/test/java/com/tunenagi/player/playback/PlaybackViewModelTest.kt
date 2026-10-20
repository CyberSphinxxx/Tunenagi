package com.tunenagi.player.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tunenagi.player.ui.shell.MiniPlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presentation layer in front of playback, with the source replaced by a controllable double.
 *
 * The double is honest about its scope: Media3 itself is exercised in `NowPlayingTest` (a real
 * player) and in `:core`'s `TunenagiPlaybackServiceTest` (a real service and session). What is under
 * test *here* is the mapping and the lifecycle — that nothing loaded shows no bar, that the bar
 * follows the source, and that the controller's lifetime is bounded by the view model's.
 */
class PlaybackViewModelTest {

    private class FakeSource : NowPlayingSource {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val connection = MutableStateFlow<NowPlayingSource.Connection>(
            NowPlayingSource.Connection.Closed
        )
        var connects = 0
        var disconnects = 0
        var toggles = 0
        var retries = 0

        override fun connect() {
            connects++
        }

        override fun disconnect() {
            disconnects++
        }

        override fun togglePlayPause() {
            toggles++
        }

        override fun retry() {
            retries++
        }
    }

    private val source = FakeSource()
    private val store = ViewModelStore()

    private fun viewModel(): PlaybackViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return PlaybackViewModel(source) as T
                }
            }
        )[PlaybackViewModel::class.java]

    @Test
    fun theConnectionIsOpenedWhenTheViewModelIsCreatedAndClosedWhenItsOwnerClears() {
        viewModel()
        assertEquals(1, source.connects)

        store.clear()

        assertEquals(
            "the controller must not outlive the screen that bound it",
            1,
            source.disconnects
        )
    }

    @Test
    fun aLoadedTrackIsRenderedInTheBarAndTracksThePlayState() = runTest {
        val viewModel = viewModel()

        source.nowPlaying.value = NowPlaying(
            mediaId = "local:7",
            title = "Slow Tide",
            artist = "Kaori Ito",
            isPlaying = true
        )

        val state = viewModel.miniPlayerState.first()
        assertTrue("the mini-player must appear once something is loaded", state is MiniPlayerState.Track)
        assertEquals("Slow Tide", (state as MiniPlayerState.Track).title)
        assertEquals("Kaori Ito", state.artist)
        assertTrue("the control must show Pause while audio is playing", state.isPlaying)
    }

    @Test
    fun nothingLoadedRendersNoBarAtAll() = runTest {
        val viewModel = viewModel()

        assertTrue(viewModel.miniPlayerState.first() is MiniPlayerState.None)

        source.nowPlaying.value = NowPlaying("local:7", "Slow Tide", "Kaori Ito", isPlaying = false)
        assertTrue(viewModel.miniPlayerState.first() is MiniPlayerState.Track)

        // The queue emptied again: the bar must go away rather than show the last thing it saw.
        source.nowPlaying.value = null
        assertTrue(viewModel.miniPlayerState.first() is MiniPlayerState.None)
    }

    @Test
    fun theTransportCommandIsDelegatedToTheSource() {
        val viewModel = viewModel()

        viewModel.togglePlayPause()
        viewModel.togglePlayPause()

        assertEquals(2, source.toggles)
    }

    @Test
    fun theRetryCommandIsDelegatedToTheSource() {
        val viewModel = viewModel()

        viewModel.retry()

        assertEquals(1, source.retries)
        assertEquals("retry is not a play/pause toggle", 0, source.toggles)
    }

    @Test
    fun theConnectionStateIsExposedSoAFailureCanBeExplained() {
        val viewModel = viewModel()
        assertTrue(viewModel.connection.value is NowPlayingSource.Connection.Closed)

        source.connection.value = NowPlayingSource.Connection.Failed("service not found")

        val failed = viewModel.connection.value
        assertTrue(failed is NowPlayingSource.Connection.Failed)
        assertEquals("service not found", (failed as NowPlayingSource.Connection.Failed).reason)
    }
}
