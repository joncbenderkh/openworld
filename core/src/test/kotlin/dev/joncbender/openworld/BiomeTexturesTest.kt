package dev.joncbender.openworld

import com.badlogic.gdx.math.MathUtils
import kotlin.test.Test
import kotlin.test.assertEquals

class BiomeTexturesTest {

    // Atlas geometry, mirrored from BiomeTextures (private there): 5x4 cells,
    // corners 0.42 of a cell from the cell center.
    private val cols = 5
    private val rows = 4
    private val radius = 0.42f

    private fun cellCenter(index: Int) = (index % cols + 0.5f) / cols to (index / cols + 0.5f) / rows

    @Test
    fun `center UVs sit in the middle of each biome's atlas cell`() {
        for (biome in Biome.entries) {
            val (u, v) = cellCenter(biome.ordinal)
            assertEquals(u, BiomeTextures.centerU(biome), 1e-6f, "$biome u")
            assertEquals(v, BiomeTextures.centerV(biome), 1e-6f, "$biome v")
        }
    }

    @Test
    fun `corner UVs are evenly spaced around the cell center for pentagons and hexagons`() {
        for (biome in Biome.entries) {
            val (cu, cv) = cellCenter(biome.ordinal)
            for (cornerCount in 5..6) {
                val uvs = BiomeTextures.cornerUVs(biome, cornerCount)
                assertEquals(cornerCount * 2, uvs.size)
                for (c in 0 until cornerCount) {
                    val angle = MathUtils.PI2 * c / cornerCount
                    assertEquals(cu + MathUtils.cos(angle) * radius / cols, uvs[c * 2], 1e-6f, "$biome/$cornerCount corner $c u")
                    assertEquals(cv + MathUtils.sin(angle) * radius / rows, uvs[c * 2 + 1], 1e-6f, "$biome/$cornerCount corner $c v")
                }
            }
        }
    }
}
