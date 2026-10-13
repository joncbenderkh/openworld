package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.MathUtils

/**
 * Procedurally generated tile art: a texture atlas with [BiomeArt.VARIANTS] hand-coded pixel
 * patterns per biome (no external art assets or image-gen tool available), plus a reserved
 * solid-white cell so non-terrain geometry (the graticule lines) can share the same textured
 * shader by sampling white and relying on its own vertex color.
 *
 * A tile's look is picked from its id: one of the biome's variants, and - for biomes that are not
 * painted with a baked-in light (see [BiomeArt.isRelief]) - one of six rotations, so neighboring
 * tiles of one biome do not repeat each other.
 *
 * The atlas has mipmaps, built by halving, which never mixes two cells while a cell is at least one
 * texel across, i.e. down to level [CELL_LEVELS]; a tile samples a disc nearly a whole cell wide, so
 * that is as far as it ever needs. Minification picks the nearest texel within a level and only
 * blends between levels, so a faraway tile ends up as its biome's average color.
 */
object BiomeTextures {

    const val CELL_SIZE = 64
    const val CELL_LEVELS = 6 // CELL_SIZE = 2^6
    const val COLS = 8
    const val ROWS = 8 // 16 biomes x 3 variants + 1 reserved white cell = 49, fits in 8 * 8 = 64 (a power of two for mipmaps)
    const val ROTATIONS = 6

    /** Inked-map tiles (see [InkArt]) instead of the pixel-art ones; their tiles are turned to face north. */
    const val INK = true

    /** The average color of [biome]'s art. */
    fun meanColor(biome: Biome): FloatArray = if (INK) InkArt.meanColor(biome) else BiomeArt.meanColor(biome)

    // Corners sit this far from a face's center, as a fraction of one atlas cell - close enough
    // to the edge to use most of the cell's art, far enough that the nearest-filtered samples
    // never reach a neighbor's cell.
    const val CORNER_RADIUS_FRACTION = 0.45f

    private val variants = BiomeArt.VARIANTS
    private val whiteIndex = Biome.entries.size * variants

    /** Index of the atlas cell holding [biome]'s [variant]. */
    fun cellOf(biome: Biome, variant: Int): Int = biome.ordinal * variants + variant

    fun build(): Texture {
        val pixmap = Pixmap(COLS * CELL_SIZE, ROWS * CELL_SIZE, Pixmap.Format.RGBA8888)
        val cell = IntArray(CELL_SIZE * CELL_SIZE)
        for (biome in Biome.entries) {
            for (variant in 0 until variants) {
                if (INK) InkArt.paint(biome, variant, CELL_SIZE, cell) else BiomeArt.paint(biome, variant, CELL_SIZE, cell)
                val (x0, y0) = cellOrigin(cellOf(biome, variant))
                for (y in 0 until CELL_SIZE) {
                    for (x in 0 until CELL_SIZE) pixmap.drawPixel(x0 + x, y0 + y, cell[y * CELL_SIZE + x])
                }
            }
        }
        val (wx, wy) = cellOrigin(whiteIndex)
        pixmap.setColor(Color.WHITE)
        pixmap.fillRectangle(wx, wy, CELL_SIZE, CELL_SIZE)

        val texture = Texture(pixmap, true)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.MipMapNearestLinear, Texture.TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
        return texture
    }

    // A face's UVs depend only on its biome, variant, rotation and whether it's a pentagon or
    // hexagon, so they're computed once here and read by array index per face. The per-face
    // alternative (a map lookup plus a Pair<Float, Float> of boxed floats, twice per face) measured
    // ~2.2s of a ~2.5s mesh build at ~396k tiles on the Android emulator - nearly all of it hashing
    // enums and allocating, none of it actual work.
    private val CENTER_UVS = FloatArray(Biome.entries.size * variants * 2).also { out ->
        for (biome in Biome.entries) {
            for (variant in 0 until variants) {
                val (u, v) = cellCenterUV(cellOf(biome, variant))
                out[cellOf(biome, variant) * 2] = u
                out[cellOf(biome, variant) * 2 + 1] = v
            }
        }
    }

    // [cornerCount][cell][rotation] -> (u0, v0, u1, v1, ...). Only 5 (pentagon) and 6 (hexagon) are
    // populated, matching the only face shapes there are. Relief biomes have the same entry for every
    // rotation: they are never rotated.
    private val CORNER_UVS: Array<Array<Array<FloatArray>>?> = arrayOfNulls<Array<Array<FloatArray>>>(7).also { table ->
        for (cornerCount in 5..6) {
            table[cornerCount] = Array(Biome.entries.size * variants) { cell ->
                val biome = Biome.entries[cell / variants]
                val (cu, cv) = cellCenterUV(cell)
                Array(ROTATIONS) { rotation ->
                    val offsets = buildCornerOffsets(cornerCount, if (BiomeArt.isRelief(biome)) 0 else rotation)
                    FloatArray(cornerCount * 2) { k -> (if (k % 2 == 0) cu else cv) + offsets[k] }
                }
            }
        }
    }

    /** A tile's look, from its id: pass to [variantOf] and [rotationOf]. A cheap integer hash, so nearby ids differ. */
    fun look(tile: Int): Int {
        var h = tile * -0x61c88647
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        return h xor (h ushr 12)
    }

    fun variantOf(look: Int): Int = ((look ushr 8) and 0xFFFF) % variants

    fun rotationOf(look: Int): Int = ((look ushr 24) and 0xFF) % ROTATIONS

    fun centerU(biome: Biome, variant: Int): Float = CENTER_UVS[cellOf(biome, variant) * 2]
    fun centerV(biome: Biome, variant: Int): Float = CENTER_UVS[cellOf(biome, variant) * 2 + 1]

    /**
     * The (u0, v0, u1, v1, ...) of every corner of a [cornerCount]-gon face (5 or 6) painted with
     * [biome]'s [variant], turned [rotation] corners (0 until [ROTATIONS]). Shared and
     * precomputed - read only, never modify.
     */
    fun cornerUVs(biome: Biome, cornerCount: Int, variant: Int, rotation: Int): FloatArray =
        (CORNER_UVS.getOrNull(cornerCount) ?: error("no UVs for a $cornerCount-corner face"))[cellOf(biome, variant)][rotation]

    // Per-corner (cos, sin) offsets from the cell center, scaled to UV space; corner c sits where corner c + rotation would.
    private fun buildCornerOffsets(cornerCount: Int, rotation: Int): FloatArray {
        val ru = CORNER_RADIUS_FRACTION / COLS
        val rv = CORNER_RADIUS_FRACTION / ROWS
        return FloatArray(cornerCount * 2).also { offsets ->
            for (c in 0 until cornerCount) {
                val angle = MathUtils.PI2 * ((c + rotation) % cornerCount) / cornerCount
                offsets[c * 2] = MathUtils.cos(angle) * ru
                offsets[c * 2 + 1] = MathUtils.sin(angle) * rv
            }
        }
    }

    /**
     * Writes into [out] the UV of corner [c] of a face so that the art's up is north: the corner's bearing on the
     * sphere (seen from outside, east to the right) becomes its angle in the cell. [px],[py],[pz] is the face
     * center (a unit vector), [dx],[dy],[dz] the offset of the corner from it.
     */
    fun uprightUV(biome: Biome, variant: Int, px: Float, py: Float, pz: Float, dx: Float, dy: Float, dz: Float, out: FloatArray, at: Int) {
        // east = Y x p, north = p x east
        var ex = pz
        val ey = 0f
        var ez = -px
        var el = kotlin.math.sqrt(ex * ex + ez * ez)
        if (el < 1e-6f) { ex = 1f; ez = 0f; el = 1f }
        ex /= el; ez /= el
        val nx = py * ez - pz * ey
        val ny = pz * ex - px * ez
        val nz = px * ey - py * ex
        val east = dx * ex + dy * ey + dz * ez
        val north = dx * nx + dy * ny + dz * nz
        val len = kotlin.math.sqrt(east * east + north * north)
        val k = if (len < 1e-9f) 0f else CORNER_RADIUS_FRACTION / len
        out[at] = centerU(biome, variant) + east * k / COLS
        out[at + 1] = centerV(biome, variant) - north * k / ROWS
    }

    /** UV of the reserved solid-white cell, for geometry that just wants its own vertex color. */
    fun whiteUV(): Pair<Float, Float> = cellCenterUV(whiteIndex)

    private fun cellOrigin(index: Int): Pair<Int, Int> = (index % COLS) * CELL_SIZE to (index / COLS) * CELL_SIZE

    private fun cellCenterUV(index: Int): Pair<Float, Float> {
        val col = index % COLS
        val row = index / COLS
        return (col + 0.5f) / COLS to (row + 0.5f) / ROWS
    }
}
