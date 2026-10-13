package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture

/**
 * The resource glyphs as their own small atlas (8 x 4 cells of 32 px), kept apart from the biome atlas so the
 * icons can be filtered and blended on their own. Which cell holds which resource is its ordinal.
 */
internal object ResourceIcons {
    const val CELL_SIZE = 32
    const val COLS = 8
    const val ROWS = 4

    init {
        check(Resource.entries.size <= COLS * ROWS) { "more resources than icon cells" }
    }

    // Stay half a texel inside the cell, so filtering at its edge never reads the neighbor.
    private const val INSET = 0.5f

    /** Writes the (u0, v0, u1, v1) of [resource]'s cell into [out] at [at]; v0 is the top. */
    fun uvRect(resource: Resource, out: FloatArray, at: Int) {
        val col = resource.ordinal % COLS
        val row = resource.ordinal / COLS
        val width = (COLS * CELL_SIZE).toFloat()
        val height = (ROWS * CELL_SIZE).toFloat()
        out[at] = (col * CELL_SIZE + INSET) / width
        out[at + 1] = (row * CELL_SIZE + INSET) / height
        out[at + 2] = ((col + 1) * CELL_SIZE - INSET) / width
        out[at + 3] = ((row + 1) * CELL_SIZE - INSET) / height
    }

    /** Builds the atlas texture. Needs GL. */
    fun build(): Texture {
        val pixmap = Pixmap(COLS * CELL_SIZE, ROWS * CELL_SIZE, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        val cell = IntArray(CELL_SIZE * CELL_SIZE)
        for (resource in Resource.entries) {
            ResourceIconArt.paint(resource, CELL_SIZE, cell)
            val x0 = resource.ordinal % COLS * CELL_SIZE
            val y0 = resource.ordinal / COLS * CELL_SIZE
            for (y in 0 until CELL_SIZE) for (x in 0 until CELL_SIZE) pixmap.drawPixel(x0 + x, y0 + y, cell[y * CELL_SIZE + x])
        }
        val texture = Texture(pixmap)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
        return texture
    }
}
