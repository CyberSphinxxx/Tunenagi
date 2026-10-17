package com.tunenagi.core.designsystem

/**
 * Tunenagi colour palette — owned, single source of truth.
 *
 * Values are ARGB `Int`s rather than Compose `Color`s on purpose:
 *  * the palette is consumed by `:core` and `:app`, and a Compose `Color` here would force
 *    every consumer onto the graphics artifact for what is just data; and
 *  * the WCAG audit in [TunenagiContrast] has to run as a fast JVM unit test, with no
 *    Android runtime and no instrumentation.
 *
 * Mirrors the design tokens in `SPEC/UX_DESIGN.md`. `app/src/main/res/values/themes.xml` is
 * a thin Android entry point and must not drift from these values.
 */
object TunenagiPalette {

    // Core surfaces
    val Background: Int = 0xFF0C0D12.toInt()
    val Surface: Int = 0xFF171923.toInt()
    val Overlay: Int = 0xFF252837.toInt()

    /**
     * Decorative divider / subtle separation only. Measured 1.70:1 against [Background]
     * and 1.53:1 against [Surface], so it is **below** the WCAG 1.4.11 3:1 non-text
     * threshold and must never be the only thing identifying an interactive boundary or
     * control state. Use [OutlineStrong] for those.
     */
    val Outline: Int = 0xFF35394A.toInt()

    /**
     * Boundary colour for controls whose outline carries meaning (text fields, focus
     * rings, selected chips). Chosen so it clears 3:1 against all three surfaces:
     * 4.09:1 on [Background], 3.69:1 on [Surface], 3.08:1 on [Overlay].
     */
    val OutlineStrong: Int = 0xFF6E7288.toInt()

    // Text
    val OnBackground: Int = 0xFFF4F3FA.toInt()
    val OnSurface: Int = 0xFFF4F3FA.toInt()
    val Secondary: Int = 0xFFB6B8C5.toInt()

    // Accent
    val Primary: Int = 0xFFA99BFA.toInt()
    val Danger: Int = 0xFFFF7B85.toInt()
    val Positive: Int = 0xFF7FE0BB.toInt()

    /**
     * Subdued accent fill. Safe for text in [OnBackground] (5.58:1), **not** for
     * [Secondary] (3.12:1, below AA). See [TunenagiContrast.PROHIBITED_TEXT_PAIRS].
     */
    val PrimarySubdued: Int = 0xFF5C54B8.toInt()
}
