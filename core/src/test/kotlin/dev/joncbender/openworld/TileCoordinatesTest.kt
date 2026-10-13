package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileCoordinatesTest {
    @Test
    fun `the view's center is the equator at longitude zero`() {
        assertEquals(0f, TileCoordinates.latitudeDeg(0f), 1e-4f)
        assertEquals(0f, TileCoordinates.longitudeDeg(0f, 1f), 1e-4f)
    }

    @Test
    fun `the poles are 90 degrees and a point to the right is east`() {
        assertEquals(90f, TileCoordinates.latitudeDeg(1f), 1e-3f)
        assertEquals(-90f, TileCoordinates.latitudeDeg(-1f), 1e-3f)
        assertEquals(90f, TileCoordinates.longitudeDeg(1f, 0f), 1e-3f)
        assertEquals(-90f, TileCoordinates.longitudeDeg(-1f, 0f), 1e-3f)
        assertEquals(180f, kotlin.math.abs(TileCoordinates.longitudeDeg(0f, -1f)), 1e-3f)
    }

    @Test
    fun `a slightly out of range y does not give NaN`() {
        assertTrue(!TileCoordinates.latitudeDeg(1.0000001f).isNaN())
    }

    @Test
    fun `coordinates read with hemispheres and two decimals`() {
        assertEquals("46.52° N · 9.84° E", TileCoordinates.format(46.52f, 9.84f))
        assertEquals("12.50° S · 120.00° W", TileCoordinates.format(-12.5f, -120f))
        assertEquals("0.00° N · 0.00° E", TileCoordinates.format(0f, 0f))
    }

    @Test
    fun `every resource has a glyph that paints something`() {
        for (resource in Resource.entries) {
            val pixels = ResourceGlyphs.rgba(resource, 48)
            assertEquals(48 * 48, pixels.size)
            assertTrue(pixels.any { (it and 0xFF) > 0 }, "$resource")
        }
    }
}
