package com.tunenagi.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tunenagi.player.discovery.RemoteDiscoveryViewModel
import com.tunenagi.player.libraryimport.LocalImportViewModel
import com.tunenagi.player.ui.playback.rememberPlaybackUi
import com.tunenagi.player.ui.shell.TunenagiShell
import com.tunenagi.player.ui.theme.TunenagiTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Tunenagi main activity — owned.
 *
 * Host for the Compose shell: three tabs, the player route and the mini-player slot. It holds no
 * navigation state of its own, so the shell survives configuration changes, and it owns no player:
 * the playback state and the transport command come from the playback view model, which is bound to
 * the media session service. This activity never imports a Media3 player type.
 *
 * The one system permission it does ask for is the Android 13+ media-notification permission, and
 * the answer is deliberately ignored: the result callback is empty because playback must not depend
 * on it (`NotificationPermission.kt`).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Nothing to do either way: the service keeps playing without a visible notification.
    }

    private val importViewModel: LocalImportViewModel by viewModels()

    private val discoveryViewModel: RemoteDiscoveryViewModel by viewModels()

    /**
     * The system folder picker, and the grant that outlives it.
     *
     * `OpenDocumentTree` is the one way this app reads a folder the user chose: no storage
     * permission, no scanning of anything they did not point at. The persistable permission is taken
     * here because only an Activity can take it; without that, tomorrow's rescan of the same folder
     * would fail with `PermissionRequired` and the user would be asked to pick it again with no
     * explanation. A provider that refuses to persist the grant still imports now - the scan runs
     * while the transient grant lasts - so a failure here is not turned into a user-facing error.
     */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        importViewModel.importFolder(uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermissionOnce()
        setContent {
            TunenagiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val playback = rememberPlaybackUi()
                    val importState by importViewModel.state.collectAsStateWithLifecycle()
                    val searchState by discoveryViewModel.searchState.collectAsStateWithLifecycle()
                    val trendingState by discoveryViewModel.trendingState.collectAsStateWithLifecycle()
                    TunenagiShell(
                        playerState = playback.state,
                        onTogglePlayPause = playback.onTogglePlayPause,
                        onRetryPlayback = playback.onRetry,
                        importState = importState,
                        onImportMusic = { folderPicker.launch(null) },
                        searchState = searchState,
                        onSearchQuery = discoveryViewModel::search,
                        onRetrySearch = discoveryViewModel::retrySearch,
                        onPlayRemoteTrack = discoveryViewModel::playRemoteTrack,
                        trendingState = trendingState,
                        onBrowseAudius = discoveryViewModel::loadTrending,
                        onPlayTrending = discoveryViewModel::playRemoteTrack,
                        onRetryTrending = discoveryViewModel::retryTrending
                    )
                }
            }
        }
    }

    // Same inlined-API note as `NotificationPermission.kt`: the constant only exists from API 33,
    // and the guard inside `shouldAskForNotificationPermission` is what keeps this from running there.
    @SuppressLint("InlinedApi")
    private fun askForNotificationPermissionOnce() {
        if (!shouldAskForNotificationPermission(this)) return
        // Marked before the dialog: a result that never arrives (the activity is recreated while
        // the system dialog is up) must not cause a second request on the next launch.
        markNotificationPermissionAsked(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
