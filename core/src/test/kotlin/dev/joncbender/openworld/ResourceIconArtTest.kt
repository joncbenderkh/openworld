package dev.joncbender.openworld

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceIconArtTest {
    private val size = ResourceIcons.CELL_SIZE

    private fun paint(resource: Item): IntArray = IntArray(size * size).also { ResourceIconArt.paint(resource, size, it) }

    private fun alpha(pixel: Int) = pixel and 0xFF

    @Test
    fun `painting is deterministic and every resource has its own glyph`() {
        val glyphs = Items.all.map { paint(it) }
        for ((i, glyph) in glyphs.withIndex()) assertTrue(glyph.contentEquals(paint(Items.all[i])), "${Items.all[i]}")
        assertEquals(glyphs.size, glyphs.map { it.toList() }.toSet().size, "two resources share a glyph")
    }

    @Test
    fun `a glyph is a visible shape with room around it`() {
        for (resource in Items.all) {
            val pixels = paint(resource)
            val covered = pixels.count { alpha(it) > 128 }
            assertTrue(covered in 90..700, "$resource covers $covered of ${size * size} pixels")
            // Nothing touches the cell's border: filtering there would read the neighbor.
            for (k in 0 until size) {
                for (edge in intArrayOf(0, size - 1)) {
                    assertEquals(0, alpha(pixels[edge * size + k]), "$resource row $edge")
                    assertEquals(0, alpha(pixels[k * size + edge]), "$resource column $edge")
                }
            }
        }
    }

    @Test
    fun `a glyph has a dark outline`() {
        for (resource in Items.all) {
            val pixels = paint(resource)
            val dark = pixels.count { alpha(it) > 200 && ((it ushr 24) and 0xFF) < 70 && ((it ushr 16) and 0xFF) < 70 && ((it ushr 8) and 0xFF) < 70 }
            assertTrue(dark > 20, "$resource has only $dark outline pixels")
        }
    }

    @Test
    fun `every resource has a cell inside the atlas, with its UVs inside that cell`() {
        val uv = FloatArray(4)
        for (resource in Items.all) {
            ResourceIcons.uvRect(resource, uv, 0)
            val col = Items.slot(resource) % ResourceIcons.COLS
            val row = Items.slot(resource) / ResourceIcons.COLS
            assertTrue(uv[0] > col / ResourceIcons.COLS.toFloat() && uv[2] < (col + 1) / ResourceIcons.COLS.toFloat(), "$resource u")
            assertTrue(uv[1] > row / ResourceIcons.ROWS.toFloat() && uv[3] < (row + 1) / ResourceIcons.ROWS.toFloat(), "$resource v")
            assertTrue(uv[0] < uv[2] && uv[1] < uv[3])
        }
    }

    @Test
    fun `writes a preview of every icon at 4x`() {
        val scale = 4
        val cols = ResourceIcons.COLS
        val image = BufferedImage(cols * size * scale, ((Items.all.size + cols - 1) / cols) * size * scale, BufferedImage.TYPE_INT_ARGB)
        for (resource in Items.all) {
            val pixels = paint(resource)
            val x0 = Items.slot(resource) % cols * size * scale
            val y0 = Items.slot(resource) / cols * size * scale
            for (y in 0 until size * scale) {
                for (x in 0 until size * scale) {
                    val p = pixels[(y / scale) * size + x / scale]
                    val checker = if (((x / 8) + (y / 8)) % 2 == 0) 0xFFB8D8A0.toInt() else 0xFFA0C088.toInt()
                    val a = alpha(p) / 255f
                    fun mix(shift: Int) = (((p ushr shift) and 0xFF) * a + ((checker ushr (shift - 8)) and 0xFF) * (1 - a)).toInt()
                    image.setRGB(x0 + x, y0 + y, (0xFF shl 24) or (mix(24) shl 16) or (mix(16) shl 8) or mix(8))
                }
            }
        }
        val file = File("build/icon-preview.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
