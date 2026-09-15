package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexGeometryTest {

    @Test
    fun `columns wrap around the world width`() {
        val coord = HexCoord(col = -1, row = 3)
        assertEquals(HexCoord(9, 3), coord.wrapped(width = 10))
    }

    @Test
    fun `neighbors at the wrap seam land on the opposite edge`() {
        val neighbors = HexCoord(0, 5).neighbors(width = 10, height = 20)
        assertTrue(neighbors.any { it.col == 9 })
    }

    @Test
    fun `world generation covers the full grid with valid biomes`() {
        val world = WorldGenerator(seed = 42L).generate(width = 16, height = 12)
        for (row in 0 until 12) {
            for (col in 0 until 16) {
                // Just asserting this doesn't throw and returns a real enum value.
                val biome = world[HexCoord(col, row)]
                assertTrue(Biome.entries.contains(biome))
            }
        }
    }
}
