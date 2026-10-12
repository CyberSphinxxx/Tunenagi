package com.tunenagi.player.ui.theme

import androidx.compose.ui.graphics.Color
import com.tunenagi.core.designsystem.TunenagiPalette

/**
 * Compose view of the Tunenagi palette — owned.
 *
 * The values themselves live in `:core` ([TunenagiPalette]) so every module shares one
 * source of truth and the WCAG audit can run as a plain JVM unit test. This object only
 * lifts those ARGB values into Compose `Color`s and must not introduce values of its own.
 *
 * Mirrors SPEC/UX_DESIGN.md design tokens. `res/values/themes.xml` is a thin Android entry
 * point and must not drift from these.
 */
object TunenagiColors {
    // Core surfaces
    val Background = Color(TunenagiPalette.Background)
    val Surface = Color(TunenagiPalette.Surface)
    val Overlay = Color(TunenagiPalette.Overlay)

    /** Decorative dividers only; 1.70:1 on Background, below the 3:1 non-text threshold. */
    val Outline = Color(TunenagiPalette.Outline)

    /** Meaningful boundaries (fields, focus rings, selected state); clears 3:1 everywhere. */
    val OutlineStrong = Color(TunenagiPalette.OutlineStrong)

    // Text
    val OnBackground = Color(TunenagiPalette.OnBackground)
    val OnSurface = Color(TunenagiPalette.OnSurface)
    val Secondary = Color(TunenagiPalette.Secondary)

    // Accent
    val Primary = Color(TunenagiPalette.Primary)
    val Danger = Color(TunenagiPalette.Danger)
    val Positive = Color(TunenagiPalette.Positive)

    /** Subdued accent fill: safe with [OnBackground], not with [Secondary]. */
    val PrimarySubdued = Color(TunenagiPalette.PrimarySubdued)
}
