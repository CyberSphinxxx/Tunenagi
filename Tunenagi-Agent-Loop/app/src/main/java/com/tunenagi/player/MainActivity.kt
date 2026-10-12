package com.tunenagi.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tunenagi.player.ui.shell.TunenagiShell
import com.tunenagi.player.ui.theme.TunenagiTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Tunenagi main activity — owned.
 *
 * Host for the Compose shell: three tabs, the player route and the mini-player slot. It
 * holds no navigation state of its own, so the shell survives configuration changes and
 * the player route can later be driven from a media session without touching the activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TunenagiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TunenagiShell()
                }
            }
        }
    }
}
