package com.tunenagi.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the non-colour token ranges from `SPEC/UX_DESIGN.md` so the numbers the UI uses
 * cannot drift outside the design contract unnoticed.
 */
class TunenagiTokensTest {

    @Test
    fun `spacing scale is 4pt based and ascending`() {
        assertEquals(0, TunenagiSpacing.ScaleDp.first() % TunenagiSpacing.BaseDp)
        TunenagiSpacing.ScaleDp.forEach {
            assertEquals("$it is not a ${TunenagiSpacing.BaseDp}dp multiple", 0, it % TunenagiSpacing.BaseDp)
        }
        assertEquals(TunenagiSpacing.ScaleDp.sorted(), TunenagiSpacing.ScaleDp)
        assertEquals(TunenagiSpacing.ScaleDp.distinct(), TunenagiSpacing.ScaleDp)
    }

    @Test
    fun `minimum touch target is at least 48dp`() {
        assertTrue(TunenagiSpacing.MinTouchTargetDp >= 48)
    }

    @Test
    fun `corner radii stay inside the documented bands`() {
        assertTrue(TunenagiShapes.CardCornerDp in TunenagiShapes.MinCardCornerDp..TunenagiShapes.MaxCardCornerDp)
        assertTrue(TunenagiShapes.ArtworkCornerDp in TunenagiShapes.MinArtworkCornerDp..TunenagiShapes.MaxArtworkCornerDp)
        assertTrue(TunenagiShapes.ArtworkCornerDp <= TunenagiShapes.CardCornerDp)
    }

    @Test
    fun `navigation motion stays inside the 180-240ms band`() {
        assertTrue(TunenagiMotion.NavigationShortMs in TunenagiMotion.MinNavigationMs..TunenagiMotion.MaxNavigationMs)
        assertTrue(TunenagiMotion.NavigationLongMs in TunenagiMotion.MinNavigationMs..TunenagiMotion.MaxNavigationMs)
        assertTrue(TunenagiMotion.NavigationShortMs <= TunenagiMotion.NavigationLongMs)
    }

    @Test
    fun `navigation duration is used when animations are enabled`() {
        assertEquals(TunenagiMotion.NavigationShortMs, TunenagiMotion.navigationDurationMs(animationsEnabled = true))
    }

    /** Failure path: reduced motion must collapse the transition, not shorten it a bit. */
    @Test
    fun `navigation duration is zero when the user disables animations`() {
        assertEquals(0, TunenagiMotion.navigationDurationMs(animationsEnabled = false))
    }

    @Test
    fun `wide layout starts at the 600dp boundary`() {
        assertEquals(ShellLayout.Compact, TunenagiWindowSize.layoutFor(599))
        assertEquals(ShellLayout.Wide, TunenagiWindowSize.layoutFor(600))
        assertEquals(ShellLayout.Wide, TunenagiWindowSize.layoutFor(1280))
    }

    @Test
    fun `unmeasured widths fall back to the compact layout`() {
        assertEquals(ShellLayout.Compact, TunenagiWindowSize.layoutFor(0))
        assertEquals(ShellLayout.Compact, TunenagiWindowSize.layoutFor(-1))
    }
}
