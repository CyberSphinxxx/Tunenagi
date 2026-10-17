package com.tunenagi.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WCAG maths to reference values and asserts the palette contract in
 * [TunenagiContrast.REQUIRED_PAIRS]. A palette edit that makes text unreadable now fails
 * here, and the recorded prohibitions cannot silently become "required" later.
 */
class TunenagiContrastTest {

    @Test
    fun `relative luminance matches the sRGB endpoints`() {
        assertEquals(0.0, TunenagiContrast.relativeLuminance(0xFF000000.toInt()), 0.0001)
        assertEquals(1.0, TunenagiContrast.relativeLuminance(0xFFFFFFFF.toInt()), 0.0001)
    }

    @Test
    fun `black and white are the maximum ratio`() {
        val ratio = TunenagiContrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun `a colour against itself is the minimum ratio`() {
        assertEquals(1.0, TunenagiContrast.ratio(TunenagiPalette.Surface, TunenagiPalette.Surface), 0.0001)
    }

    @Test
    fun `ratio is symmetric in argument order`() {
        val forward = TunenagiContrast.ratio(TunenagiPalette.Secondary, TunenagiPalette.Surface)
        val reverse = TunenagiContrast.ratio(TunenagiPalette.Surface, TunenagiPalette.Secondary)
        assertEquals(forward, reverse, 0.0001)
    }

    @Test
    fun `measured ratio matches the recorded reference value`() {
        // Reference computed from the WCAG formula for #B6B8C5 on #171923.
        val measured = TunenagiContrast.ratio(TunenagiPalette.Secondary, TunenagiPalette.Surface)
        assertEquals(8.88, measured, 0.05)
    }

    @Test
    fun `threshold comparison passes on equality`() {
        val ratio = TunenagiContrast.ratio(TunenagiPalette.Secondary, TunenagiPalette.Surface)
        assertTrue(TunenagiContrast.meets(TunenagiPalette.Secondary, TunenagiPalette.Surface, ratio))
        assertFalse(TunenagiContrast.meets(TunenagiPalette.Secondary, TunenagiPalette.Surface, ratio + 0.01))
    }

    @Test
    fun `require returns the measured ratio for a passing pair`() {
        val measured = TunenagiContrast.require(
            TunenagiPalette.OnBackground,
            TunenagiPalette.Background,
            TunenagiContrast.AA_NORMAL_TEXT,
            "test pair"
        )
        assertTrue(measured >= TunenagiContrast.AA_NORMAL_TEXT)
    }

    /** Failure path: the audit must reject a pair below its threshold and say why. */
    @Test
    fun `require rejects a pair below the threshold and names the colours`() {
        val pair = TunenagiContrast.PROHIBITED_TEXT_PAIRS.single()
        val error = assertThrows(IllegalStateException::class.java) {
            TunenagiContrast.require(pair.foreground, pair.background, pair.threshold, pair.purpose)
        }
        val message = error.message.orEmpty()
        assertTrue(message, message.contains(TunenagiContrast.hex(pair.foreground)))
        assertTrue(message, message.contains(TunenagiContrast.hex(pair.background)))
        assertTrue(message, message.contains(pair.purpose))
    }

    @Test
    fun `every required palette pair meets its threshold`() {
        assertEquals(emptyList<String>(), TunenagiContrast.failures())
    }

    /**
     * The prohibitions are documentation only if they are still true. If a later palette
     * change makes one of them compliant, this test fails and forces the entry to be moved
     * into [TunenagiContrast.REQUIRED_PAIRS] deliberately instead of lingering as folklore.
     */
    @Test
    fun `recorded prohibitions still measure below their threshold`() {
        assertTrue(TunenagiContrast.PROHIBITED_TEXT_PAIRS.isNotEmpty())
        TunenagiContrast.PROHIBITED_TEXT_PAIRS.forEach { pair ->
            assertFalse(
                "${pair.purpose} is no longer a violation; move it to REQUIRED_PAIRS",
                TunenagiContrast.meets(pair.foreground, pair.background, pair.threshold)
            )
        }
    }

    @Test
    fun `decorative outline is intentionally not claimed as a meaningful boundary`() {
        // 1.4.11 needs 3:1; the divider token is documented as failing it, and the strong
        // outline token exists so callers have a compliant option.
        assertFalse(
            TunenagiContrast.meets(TunenagiPalette.Outline, TunenagiPalette.Surface, TunenagiContrast.AA_NON_TEXT)
        )
        assertTrue(
            TunenagiContrast.meets(TunenagiPalette.OutlineStrong, TunenagiPalette.Surface, TunenagiContrast.AA_NON_TEXT)
        )
    }
}
