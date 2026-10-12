package com.tunenagi.core.designsystem

/**
 * Tunenagi non-colour design tokens — owned, shared by every module.
 *
 * Kept as plain numbers (dp / ms) so they carry no Compose dependency and can be asserted
 * by a JVM unit test. `SPEC/UX_DESIGN.md` fixes the ranges; the objects below are the
 * machine-readable form of them.
 */
object TunenagiSpacing {

    /** The scale is 4pt-based; every step must be a multiple of this. */
    const val BaseDp = 4

    const val XsDp = 4
    const val SmDp = 8
    const val MdDp = 12
    const val LgDp = 16
    const val XlDp = 24
    const val XxlDp = 32

    /** Ascending list of the scale, used by the invariant test and by layout helpers. */
    val ScaleDp = listOf(XsDp, SmDp, MdDp, LgDp, XlDp, XxlDp)

    /** Minimum touch target for a control, per the accessibility contract. */
    const val MinTouchTargetDp = 48
}

object TunenagiShapes {

    /** Cards and sheets: the spec allows 12-16dp. */
    const val CardCornerDp = 16

    /** Album artwork: the spec allows 8-12dp and it must stay tighter than a card corner. */
    const val ArtworkCornerDp = 12

    const val MinCardCornerDp = 12
    const val MaxCardCornerDp = 16
    const val MinArtworkCornerDp = 8
    const val MaxArtworkCornerDp = 12
}

object TunenagiMotion {

    /** Navigation transitions: the spec band is 180-240ms. */
    const val NavigationShortMs = 180
    const val NavigationLongMs = 240

    const val MinNavigationMs = 180
    const val MaxNavigationMs = 240

    /**
     * Duration actually used for a transition. Returns zero when the user has asked the
     * platform to remove animations, so "honor reduced motion" is enforced in one place
     * instead of being an undocumented habit at each call site.
     */
    fun navigationDurationMs(animationsEnabled: Boolean): Int =
        if (animationsEnabled) NavigationShortMs else 0
}

/** How the shell lays out at the current width. */
enum class ShellLayout { Compact, Wide }

object TunenagiWindowSize {

    /** Material's compact/medium-to-expanded boundary. */
    const val WideMinWidthDp = 600

    /**
     * Single decision point for the responsive scaffold. Non-positive widths cannot come
     * from a real configuration, but treating them as [ShellLayout.Compact] keeps previews
     * and tests from crashing on an unmeasured size.
     */
    fun layoutFor(widthDp: Int): ShellLayout =
        if (widthDp >= WideMinWidthDp) ShellLayout.Wide else ShellLayout.Compact
}
