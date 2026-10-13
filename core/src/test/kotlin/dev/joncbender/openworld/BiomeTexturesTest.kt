package dev.joncbender.openworld

import com.badlogic.gdx.math.MathUtils
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiomeTexturesTest {

    private val cols = BiomeTextures.COLS
    private val rows = BiomeTextures.ROWS
    private val radius = BiomeTextures.CORNER_RADIUS_FRACTION

    private fun cellCenter(index: Int) = (index % cols + 0.5f) / cols to (index / cols + 0.5f) / rows

    @Test
    fun `every biome variant and the white texel have a cell of their own in the atlas`() {
        val cells = Biome.entries.flatMap { biome -> (0 until BiomeArt.VARIANTS).map { BiomeTextures.cellOf(biome, it) } }
        assertEquals(cells.size, cells.toSet().size)
        assertEquals((0 until cells.size).toList(), cells.sorted())
        assertTrue(cells.size < cols * rows, "the white cell needs a slot")
        val white = BiomeTextures.whiteUV()
        for (cell in cells) assertTrue(cellCenter(cell) != white)
    }

    @Test
    fun `the atlas is a power of two, as mipmaps need`() {
        for (size in intArrayOf(cols * BiomeTextures.CELL_SIZE, rows * BiomeTextures.CELL_SIZE)) {
            assertEquals(1, Integer.bitCount(size))
        }
        assertEquals(BiomeTextures.CELL_SIZE, 1 shl BiomeTextures.CELL_LEVELS)
    }

    @Test
    fun `center UVs sit in the middle of each variant's atlas cell`() {
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                val (u, v) = cellCenter(BiomeTextures.cellOf(biome, variant))
                assertEquals(u, BiomeTextures.centerU(biome, variant), 1e-6f, "$biome/$variant u")
                assertEquals(v, BiomeTextures.centerV(biome, variant), 1e-6f, "$biome/$variant v")
            }
        }
    }

    @Test
    fun `corner UVs are evenly spaced around the cell center, turned by the rotation, for pentagons and hexagons`() {
        for (biome in Biome.entries) {
            val (cu, cv) = cellCenter(BiomeTextures.cellOf(biome, 1))
            for (cornerCount in 5..6) {
                for (rotation in 0 until BiomeTextures.ROTATIONS) {
                    val uvs = BiomeTextures.cornerUVs(biome, cornerCount, 1, rotation)
                    assertEquals(cornerCount * 2, uvs.size)
                    val turned = if (BiomeArt.isRelief(biome)) 0 else rotation
                    for (c in 0 until cornerCount) {
                        val angle = MathUtils.PI2 * ((c + turned) % cornerCount) / cornerCount
                        assertEquals(cu + MathUtils.cos(angle) * radius / cols, uvs[c * 2], 1e-6f, "$biome/$cornerCount/$rotation corner $c u")
                        assertEquals(cv + MathUtils.sin(angle) * radius / rows, uvs[c * 2 + 1], 1e-6f, "$biome/$cornerCount/$rotation corner $c v")
                    }
                }
            }
        }
    }

    @Test
    fun `corner UVs stay inside their own cell by at least a texel`() {
        val texel = 1f / (cols * BiomeTextures.CELL_SIZE)
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                val (cu, cv) = cellCenter(BiomeTextures.cellOf(biome, variant))
                for (rotation in 0 until BiomeTextures.ROTATIONS) {
                    val uvs = BiomeTextures.cornerUVs(biome, 6, variant, rotation)
                    for (c in 0 until 6) {
                        assertTrue(abs(uvs[c * 2] - cu) <= 0.5f / cols - texel)
                        assertTrue(abs(uvs[c * 2 + 1] - cv) <= 0.5f / rows - texel)
                    }
                }
            }
        }
    }

    @Test
    fun `relief biomes are never rotated`() {
        for (biome in Biome.entries.filter(BiomeArt::isRelief)) {
            val first = BiomeTextures.cornerUVs(biome, 6, 0, 0)
            for (rotation in 1 until BiomeTextures.ROTATIONS) {
                assertTrue(first.contentEquals(BiomeTextures.cornerUVs(biome, 6, 0, rotation)), "$biome rotation $rotation")
            }
        }
    }

    @Test
    fun `a tile's look is a pure function of its id and spreads over variants and rotations`() {
        val variants = IntArray(BiomeArt.VARIANTS)
        val rotations = IntArray(BiomeTextures.ROTATIONS)
        for (tile in 0 until 60_000) {
            val look = BiomeTextures.look(tile)
            assertEquals(look, BiomeTextures.look(tile))
            variants[BiomeTextures.variantOf(look)]++
            rotations[BiomeTextures.rotationOf(look)]++
        }
        for (count in variants) assertTrue(count in 18_000..22_000, "variants ${variants.toList()}")
        for (count in rotations) assertTrue(count in 9_000..11_000, "rotations ${rotations.toList()}")
    }

    @Test
    fun `neighboring tiles rarely look alike`() {
        var same = 0
        val total = 20_000
        for (tile in 0 until total) {
            val a = BiomeTextures.look(tile)
            val b = BiomeTextures.look(tile + 1)
            if (BiomeTextures.variantOf(a) == BiomeTextures.variantOf(b) && BiomeTextures.rotationOf(a) == BiomeTextures.rotationOf(b)) same++
        }
        assertTrue(same < total * 0.08, "same look for $same of $total neighbors")
    }
}
