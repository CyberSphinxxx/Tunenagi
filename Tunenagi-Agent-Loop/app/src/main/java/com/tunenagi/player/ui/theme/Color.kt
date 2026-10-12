package com.tunenagi.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Tunenagi color tokens — owned.
 *
 * Mirrors SPEC/UX_DESIGN.md design tokens. These are the Compose source of truth; the
 * XML theme in res/values/themes.xml is a thin Android entry point and must not drift
 * from these.
 */
object TunenagiColors {
    // Core surfaces
    val Background = Color(0xFF0C0D12)
    val Surface = Color(0xFF171923)
    val Overlay = Color(0xFF252837)
    val Outline = Color(0xFF35394A)

    // Text
    val OnBackground = Color(0xFFF4F3FA)
    val OnSurface = Color(0xFFF4F3FA)
    val Secondary = Color(0xFFB6B8C5)

    // Accent
    val Primary = Color(0xFFA99BFA)
    val Danger = Color(0xFFFF7B85)
    val Positive = Color(0xFF7FE0BB)

    // Subdued variant used for non-interactive accent backgrounds; must be audited
    // against text contrast before shipping any screen that uses it with text on top.
    val PrimarySubdued = Color(0xFF5C54B8)
}
