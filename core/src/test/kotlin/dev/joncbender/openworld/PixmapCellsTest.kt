package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.utils.GdxNativesLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class PixmapCellsTest {

    @Test
    fun `a copied cell reads back exactly where drawPixel would put it`() {
        GdxNativesLoader.load()
        val size = 8
        val cell = IntArray(size * size) { (it * 0x01030507) or 0x80 }
        val copied = Pixmap(32, 24, Pixmap.Format.RGBA8888)
        val drawn = Pixmap(32, 24, Pixmap.Format.RGBA8888)
        drawn.blending = Pixmap.Blending.None
        copied.putCell(8, 16, size, cell)
        for (y in 0 until size) for (x in 0 until size) drawn.drawPixel(8 + x, 16 + y, cell[y * size + x])
        for (y in 0 until 24) for (x in 0 until 32) assertEquals(drawn.getPixel(x, y), copied.getPixel(x, y), "at $x,$y")
        copied.dispose()
        drawn.dispose()
    }
}
