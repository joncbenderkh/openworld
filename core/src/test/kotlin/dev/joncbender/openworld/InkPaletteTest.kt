package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkPaletteTest {
    private fun channels(color: Int) = floatArrayOf(
        ((color shr 16) and 255) / 255f,
        ((color shr 8) and 255) / 255f,
        (color and 255) / 255f,
    )

    private fun assertMatches(expected: FloatArray, color: Int) {
        val actual = channels(color)
        for (i in 0 until 3) assertEquals(expected[i], actual[i], 1f / 255f, "channel $i")
    }

    @Test
    fun `the chrome inks are the tile art's pen inks`() {
        assertMatches(InkArt.INK, InkPalette.INK)
        assertMatches(InkArt.WATER_INK, InkPalette.WATER_INK)
    }

    @Test
    fun `text is readable on the ivory surface`() {
        val surface = InkPalette.SURFACE
        assertTrue(InkPalette.contrastRatio(InkPalette.INK, surface) >= 7.0)
        assertTrue(InkPalette.contrastRatio(InkPalette.TEXT_2, surface) >= 7.0)
        assertTrue(InkPalette.contrastRatio(InkPalette.TEXT_MUTED, surface) >= 4.5)
        assertTrue(InkPalette.contrastRatio(InkPalette.TEXT_META, surface) >= 4.5)
        assertTrue(InkPalette.contrastRatio(InkPalette.DANGER, surface) >= 4.5)
    }

    @Test
    fun `secondary text stays readable on the warm surface and the backdrop`() {
        for (background in listOf(InkPalette.SURFACE_WARM, InkPalette.BACKDROP)) {
            assertTrue(InkPalette.contrastRatio(InkPalette.TEXT_MUTED, background) >= 4.5, "muted on $background")
            assertTrue(InkPalette.contrastRatio(InkPalette.TEXT_META, background) >= 4.5, "meta on $background")
        }
    }

    @Test
    fun `the accent carries readable text in every state`() {
        assertTrue(InkPalette.contrastRatio(InkPalette.ACCENT_ON, InkPalette.ACCENT) >= 4.5)
        assertTrue(InkPalette.contrastRatio(InkPalette.ACCENT_ON, InkPalette.ACCENT_ACTIVE) >= 4.5)
        assertTrue(InkPalette.contrastRatio(InkPalette.SURFACE, InkPalette.INK) >= 7.0)
    }

    @Test
    fun `a control's ink edge stands out against the ivory it sits on`() {
        assertTrue(InkPalette.contrastRatio(InkPalette.INK, InkPalette.SURFACE) >= 3.0)
        assertTrue(InkPalette.contrastRatio(InkPalette.INK, InkPalette.SURFACE_WARM) >= 3.0)
    }

    @Test
    fun `every color is opaque except the scrim`() {
        val opaque = listOf(
            InkPalette.INK, InkPalette.WATER_INK, InkPalette.SURFACE, InkPalette.BACKDROP, InkPalette.SURFACE_WARM,
            InkPalette.BORDER_SOFT, InkPalette.TEXT_2, InkPalette.TEXT_MUTED, InkPalette.TEXT_META, InkPalette.ACCENT,
            InkPalette.ACCENT_ON, InkPalette.ACCENT_ACTIVE, InkPalette.DANGER,
        )
        for (color in opaque) assertEquals(0xFF, (color shr 24) and 255)
        assertEquals(0x7A, (InkPalette.SCRIM shr 24) and 255)
        assertEquals(InkPalette.INK and 0xFFFFFF, InkPalette.SCRIM and 0xFFFFFF)
    }

    @Test
    fun `the secondary text steps fade in order`() {
        val surface = InkPalette.SURFACE
        val ratios = listOf(InkPalette.INK, InkPalette.TEXT_2, InkPalette.TEXT_MUTED, InkPalette.TEXT_META)
            .map { InkPalette.contrastRatio(it, surface) }
        assertEquals(ratios.sortedDescending(), ratios)
    }

    @Test
    fun `tappable things are at least 48dp`() {
        assertTrue(InkPalette.TOUCH_DP >= 48f)
        assertTrue(InkPalette.MAP_BUTTON_DP >= 48f)
    }

    @Test
    fun `a switch's track fits inside its touch area and its thumb inside the track`() {
        assertTrue(InkPalette.SWITCH_HEIGHT_DP <= InkPalette.TOUCH_DP)
        assertTrue(InkPalette.SWITCH_WIDTH_DP > InkPalette.SWITCH_HEIGHT_DP)
        val thumb = 20f
        assertTrue(thumb + 2 * InkPalette.LINE_DP < InkPalette.SWITCH_HEIGHT_DP)
    }

    @Test
    fun `contrast of black on white is 21 and a color on itself is 1`() {
        assertEquals(21.0, InkPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-6)
        assertEquals(1.0, InkPalette.contrastRatio(InkPalette.INK, InkPalette.INK), 1e-9)
    }
}
