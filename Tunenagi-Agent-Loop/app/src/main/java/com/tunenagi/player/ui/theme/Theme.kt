package com.tunenagi.player.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val TunenagiDarkColorScheme = darkColorScheme(
    primary = TunenagiColors.Primary,
    onPrimary = TunenagiColors.Background,
    secondary = TunenagiColors.Secondary,
    tertiary = TunenagiColors.Primary,
    background = TunenagiColors.Background,
    surface = TunenagiColors.Surface,
    onBackground = TunenagiColors.OnBackground,
    onSurface = TunenagiColors.OnSurface,
    surfaceVariant = TunenagiColors.Overlay,
    outline = TunenagiColors.Outline
)

/**
 * Tunenagi theme — owned.
 *
 * Currently ships a single dark color scheme because the visual spec is a dark-first
 * product. If a light scheme is ever added it must be added here and audited for
 * contrast against the tokens in SPEC/UX_DESIGN.md.
 */
@Composable
fun TunenagiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = TunenagiDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.let {
                // System bars follow the scheme, not a hard-coded value, so a future
                // light scheme does not silently get unreadable status bars.
                WindowCompat.getInsetsController(it, it.decorView)
                    .isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TunenagiTypography,
        content = content
    )
}
