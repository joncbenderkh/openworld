package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileHighlightTest {
    private val sphere = GeodesicSphere.generate(6)
    private val buffer = FloatArray(36 * 3)

    private fun pentagon() = sphere.indices.first { sphere.cornerCount(it) == 5 }
    private fun hexagon() = sphere.indices.first { sphere.cornerCount(it) == 6 }

    private fun distance(x: Float, y: Float, z: Float, tile: Int): Float {
        val dx = x - sphere.centerX(tile)
        val dy = y - sphere.centerY(tile)
        val dz = z - sphere.centerZ(tile)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `a ring is six values per corner per triangle pair`() {
        assertEquals(5 * 6 * 3, TileHighlight.ring(sphere, pentagon(), 0.01f, buffer))
        assertEquals(6 * 6 * 3, TileHighlight.ring(sphere, hexagon(), 0.01f, buffer))
    }

    @Test
    fun `every point lies on the sphere`() {
        val tile = hexagon()
        val used = TileHighlight.ring(sphere, tile, 0.01f, buffer)
        for (i in 0 until used step 3) {
            val length = sqrt(buffer[i] * buffer[i] + buffer[i + 1] * buffer[i + 1] + buffer[i + 2] * buffer[i + 2])
            assertEquals(TileHighlight.RADIUS, length, 1e-5f)
        }
    }

    @Test
    fun `the ring straddles the tile's edge`() {
        val tile = hexagon()
        val used = TileHighlight.ring(sphere, tile, 0.002f, buffer)
        val edge = (0 until sphere.cornerCount(tile)).map { distance(sphere.cornerX(tile, it), sphere.cornerY(tile, it), sphere.cornerZ(tile, it), tile) }.average().toFloat()
        val distances = (0 until used step 3).map { distance(buffer[it], buffer[it + 1], buffer[it + 2], tile) }
        assertTrue(distances.min() < edge && distances.max() > edge)
    }

    @Test
    fun `a ring thicker than the tile fills it without inverting`() {
        val tile = hexagon()
        val used = TileHighlight.ring(sphere, tile, 10f, buffer)
        for (i in 0 until used) assertFalse(buffer[i].isNaN())
        val inner = (0 until used step 3).minOf { distance(buffer[it], buffer[it + 1], buffer[it + 2], tile) }
        assertTrue(inner < 0.01f, "the inner edge collapses to the tile's center, got $inner")
    }
}
