package com.tunenagi.core.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast maths and the contrast contract the Tunenagi palette must satisfy.
 *
 * `SPEC/UX_DESIGN.md` states that accent backgrounds "must be audited against text WCAG
 * ratios; use actual contrast check". This is that check: the maths is real code and the
 * required pairs below are asserted by `TunenagiContrastTest`, so a palette edit that
 * breaks readability fails a unit test instead of shipping.
 */
object TunenagiContrast {

    /** WCAG 1.4.3 AA for normal-size text. */
    const val AA_NORMAL_TEXT = 4.5

    /** WCAG 1.4.3 AA for large (>=18pt / 14pt bold) text. */
    const val AA_LARGE_TEXT = 3.0

    /** WCAG 1.4.11 for non-text UI components, boundaries and graphical objects. */
    const val AA_NON_TEXT = 3.0

    /** A foreground/background pair with the threshold it must clear and why it is needed. */
    data class Pair(
        val foreground: Int,
        val background: Int,
        val threshold: Double,
        val purpose: String
    )

    /**
     * Pairs the product actually renders. Thresholds are AA for their role rather than a
     * blanket 4.5, so a decorative boundary is not held to a text standard and vice versa.
     */
    val REQUIRED_PAIRS: List<Pair> = listOf(
        Pair(TunenagiPalette.OnBackground, TunenagiPalette.Background, AA_NORMAL_TEXT, "body text on app background"),
        Pair(TunenagiPalette.OnSurface, TunenagiPalette.Surface, AA_NORMAL_TEXT, "body text on raised surface"),
        Pair(TunenagiPalette.Secondary, TunenagiPalette.Surface, AA_NORMAL_TEXT, "supporting text on raised surface"),
        Pair(TunenagiPalette.Secondary, TunenagiPalette.Background, AA_NORMAL_TEXT, "supporting text on app background"),
        Pair(TunenagiPalette.OnBackground, TunenagiPalette.Overlay, AA_NORMAL_TEXT, "text on overlay surface"),
        Pair(TunenagiPalette.Secondary, TunenagiPalette.Overlay, AA_NORMAL_TEXT, "supporting text on overlay surface"),
        Pair(TunenagiPalette.Primary, TunenagiPalette.Background, AA_NORMAL_TEXT, "accent label on app background"),
        Pair(TunenagiPalette.Primary, TunenagiPalette.Surface, AA_NORMAL_TEXT, "accent label on raised surface"),
        Pair(TunenagiPalette.Primary, TunenagiPalette.Overlay, AA_NORMAL_TEXT, "accent label on overlay surface"),
        Pair(TunenagiPalette.Background, TunenagiPalette.Primary, AA_NORMAL_TEXT, "label on accent-filled control"),
        Pair(TunenagiPalette.Background, TunenagiPalette.Positive, AA_NORMAL_TEXT, "label on positive-status control"),
        Pair(TunenagiPalette.Background, TunenagiPalette.Danger, AA_NORMAL_TEXT, "label on destructive control"),
        Pair(TunenagiPalette.OnBackground, TunenagiPalette.PrimarySubdued, AA_NORMAL_TEXT, "text on subdued accent fill"),
        Pair(TunenagiPalette.OutlineStrong, TunenagiPalette.Background, AA_NON_TEXT, "meaningful boundary on app background"),
        Pair(TunenagiPalette.OutlineStrong, TunenagiPalette.Surface, AA_NON_TEXT, "meaningful boundary on raised surface"),
        Pair(TunenagiPalette.OutlineStrong, TunenagiPalette.Overlay, AA_NON_TEXT, "meaningful boundary on overlay surface")
    )

    /**
     * Pairs that measure below their role's threshold. These are recorded as explicit
     * prohibitions (and asserted to fail) so nobody "fixes" the audit by quietly adding
     * them, and so the reason survives in code rather than in a review comment.
     */
    val PROHIBITED_TEXT_PAIRS: List<Pair> = listOf(
        Pair(
            TunenagiPalette.Secondary,
            TunenagiPalette.PrimarySubdued,
            AA_NORMAL_TEXT,
            "secondary text on subdued accent fill (3.12:1); use OnBackground or a darker fill"
        )
    )

    /** sRGB channel (0-255) to linear light. */
    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of an opaque ARGB colour. Alpha is ignored (tokens are opaque). */
    fun relativeLuminance(argb: Int): Double {
        val r = linearize((argb shr 16) and 0xFF)
        val g = linearize((argb shr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Contrast ratio in [1.0, 21.0] between two opaque colours, in either order. */
    fun ratio(first: Int, second: Int): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** True when the pair clears [threshold]. Equality passes, matching WCAG wording. */
    fun meets(foreground: Int, background: Int, threshold: Double): Boolean =
        ratio(foreground, background) >= threshold

    /**
     * Returns the measured ratio, or throws naming the exact pair, role and shortfall.
     * The message carries the numbers because a bare "contrast too low" is not actionable.
     */
    fun require(foreground: Int, background: Int, threshold: Double, purpose: String): Double {
        val measured = ratio(foreground, background)
        check(measured >= threshold) {
            "contrast ${format(measured)} below ${format(threshold)} required for $purpose " +
                "(foreground ${hex(foreground)} on background ${hex(background)})"
        }
        return measured
    }

    /** Every required pair that currently fails, as human-readable messages. */
    fun failures(): List<String> = REQUIRED_PAIRS.mapNotNull { pair ->
        val measured = ratio(pair.foreground, pair.background)
        if (measured >= pair.threshold) {
            null
        } else {
            "${hex(pair.foreground)} on ${hex(pair.background)} = ${format(measured)} " +
                "(needs ${format(pair.threshold)} for ${pair.purpose})"
        }
    }

    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    fun format(value: Double): String = "%.2f".format(value)
}
