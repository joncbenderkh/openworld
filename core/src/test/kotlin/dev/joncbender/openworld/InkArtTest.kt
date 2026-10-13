package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkArtTest {

    private fun paint(biome: Biome, variant: Int): IntArray =
        IntArray(64 * 64).also { InkArt.paint(biome, variant, 64, it) }

    @Test
    fun `painting is deterministic, opaque, and differs between variants`() {
        for (biome in Biome.entries) {
            val cells = (0 until BiomeArt.VARIANTS).map { paint(biome, it) }
            for ((variant, cell) in cells.withIndex()) {
                assertTrue(cell.contentEquals(paint(biome, variant)), "$biome/$variant")
                assertTrue(cell.all { it and 0xFF == 0xFF }, "$biome/$variant opaque")
            }
            assertTrue(cells.map { it.toList() }.toSet().size == cells.size, "$biome variants differ")
        }
    }

    @Test
    fun `mean colors are valid colors`() {
        for (biome in Biome.entries) {
            val mean = InkArt.meanColor(biome)
            assertEquals(3, mean.size)
            assertTrue(mean.all { it in 0f..1f }, "$biome")
        }
    }

    @Test
    fun `upright UVs put a face's northern corners above its center and its eastern corners to the right`() {
        val sphere = GeodesicSphere.generate(4)
        val uvs = FloatArray(12)
        var checked = 0
        for (face in 0 until sphere.faceCount) {
            if (kotlin.math.abs(sphere.centerY(face)) > 0.5f) continue
            val n = sphere.cornerCount(face)
            BiomeTextures.uprightUVs(Biome.DESERT, 0, sphere, face, n, uvs)
            val cu = BiomeTextures.centerU(Biome.DESERT, 0)
            val cv = BiomeTextures.centerV(Biome.DESERT, 0)
            val px = sphere.centerX(face)
            val pz = sphere.centerZ(face)
            val len = kotlin.math.sqrt(px * px + pz * pz)
            for (c in 0 until n) {
                // north is the sky-ward tangent: the world's +Y projected onto the face's tangent plane
                val py = sphere.centerY(face)
                val northward = ((sphere.cornerX(face, c) - px) * -py * px + (sphere.cornerY(face, c) - py) * (1 - py * py) +
                    (sphere.cornerZ(face, c) - pz) * -py * pz) / kotlin.math.sqrt(1 - py * py)
                val eastward = ((sphere.cornerX(face, c) - px) * pz - (sphere.cornerZ(face, c) - pz) * px) / len
                if (kotlin.math.abs(northward) > 1e-3f) assertEquals(northward > 0, uvs[c * 2 + 1] < cv, "north face $face corner $c")
                if (kotlin.math.abs(eastward) > 1e-3f) assertEquals(eastward > 0, uvs[c * 2] > cu, "east face $face corner $c")
                checked++
            }
        }
        assertTrue(checked > 0)
    }
}
