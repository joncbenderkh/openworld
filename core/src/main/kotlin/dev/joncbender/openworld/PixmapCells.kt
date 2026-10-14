package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Pixmap
import java.nio.ByteOrder

/**
 * Copies a square cell of packed RGBA8888 pixels into an RGBA8888 pixmap at ([x0], [y0]), a row at a time. Calling
 * `drawPixel` for each of a cell's pixels costs one native call apiece, which measured as most of the atlas build.
 * The copy replaces what is there (no blending), which for the opaque terrain cells is what drawing them would do.
 */
internal fun Pixmap.putCell(x0: Int, y0: Int, size: Int, cell: IntArray) {
    require(format == Pixmap.Format.RGBA8888)
    require(cell.size == size * size)
    require(x0 >= 0 && y0 >= 0 && x0 + size <= width && y0 + size <= height)
    // Pixels are R, G, B, A in memory, so a big-endian view reads them as 0xRRGGBBAA, the packing the cells use.
    val ints = pixels.duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer()
    for (y in 0 until size) {
        ints.position((y0 + y) * width + x0)
        ints.put(cell, y * size, size)
    }
}
