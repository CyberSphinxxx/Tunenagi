package com.tunenagi.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.tunenagi.player.ui.theme.TunenagiTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Tunenagi main activity — owned.
 *
 * Host for the Compose shell defined in the UX pass. Right now it publishes a single
 * neutral screen so the build targets a real activity, not a stub, while the 3-tab
 * navigation and player host are added in the shell task.
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
                    Text(
                        text = "Tunenagi — shell pending",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}
