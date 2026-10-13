package dev.joncbender.openworld

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiomeArtTest {

    private fun paint(biome: Biome, variant: Int, size: Int = 64): IntArray =
        IntArray(size * size).also { BiomeArt.paint(biome, variant, size, it) }

    private fun channel(pixel: Int, shift: Int) = ((pixel ushr shift) and 0xFF) / 255f

    @Test
    fun `painting is deterministic`() {
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                assertTrue(paint(biome, variant).contentEquals(paint(biome, variant)), "$biome/$variant")
            }
        }
    }

    @Test
    fun `every pixel is opaque`() {
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                assertTrue(paint(biome, variant).all { it and 0xFF == 0xFF }, "$biome/$variant")
            }
        }
    }

    @Test
    fun `a cell averages out near its biome's mean color, so a distant tile still reads as that biome`() {
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                val pixels = paint(biome, variant)
                val color = BiomeArt.meanColor(biome)
                for ((shift, expected) in listOf(24 to color[0], 16 to color[1], 8 to color[2])) {
                    val mean = pixels.map { channel(it, shift) }.average().toFloat()
                    assertTrue(abs(mean - expected) <= 0.02f, "$biome/$variant channel $shift: mean $mean, base $expected")
                }
            }
        }
    }

    @Test
    fun `a biome's variants are different pictures`() {
        for (biome in Biome.entries) {
            for (a in 0 until BiomeArt.VARIANTS) {
                for (b in a + 1 until BiomeArt.VARIANTS) {
                    val differing = paint(biome, a).zip(paint(biome, b)).count { (x, y) -> x != y }
                    assertTrue(differing > 64 * 64 / 4, "$biome variants $a and $b differ in only $differing pixels")
                }
            }
        }
    }

    @Test
    fun `different biomes are different pictures`() {
        val cells = Biome.entries.map { paint(it, 0).toList() }
        assertEquals(cells.size, cells.toSet().size)
    }

    @Test
    fun `smaller cells work too`() {
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) assertEquals(32 * 32, paint(biome, variant, 32).size)
        }
    }

    /** Not a check: writes the atlas art to build/atlas-preview.png (2x) so it can be looked at without a GL context. */
    @Test
    fun `writes a preview of every cell`() {
        val scale = 2
        val size = 64
        val image = BufferedImage(2 * BiomeArt.VARIANTS * size * scale, Biome.entries.size / 2 * size * scale, BufferedImage.TYPE_INT_RGB)
        for (biome in Biome.entries) {
            for (variant in 0 until BiomeArt.VARIANTS) {
                val pixels = IntArray(size * size).also { if (BiomeTextures.INK) InkArt.paint(biome, variant, size, it) else BiomeArt.paint(biome, variant, size, it) }
                val column = biome.ordinal / 8 * BiomeArt.VARIANTS + variant
                for (y in 0 until size * scale) {
                    for (x in 0 until size * scale) {
                        val p = pixels[(y / scale) * size + x / scale]
                        image.setRGB(column * size * scale + x, biome.ordinal % 8 * size * scale + y, p ushr 8)
                    }
                }
            }
        }
        val file = File("build/atlas-preview.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
