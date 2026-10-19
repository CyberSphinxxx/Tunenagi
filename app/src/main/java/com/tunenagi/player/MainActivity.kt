package com.tunenagi.player

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
                    TunenagiShell(
                        playerState = playback.state,
                        onTogglePlayPause = playback.onTogglePlayPause
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
