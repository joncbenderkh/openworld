package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.MathUtils
import kotlin.random.Random

/**
 * Procedurally generated tile art: a small texture atlas with one hand-coded
 * pixel pattern per biome (no external art assets or image-gen tool
 * available), plus a reserved solid-white cell so non-terrain geometry
 * (the graticule lines) can share the same textured shader by sampling white
 * and relying on its own vertex color.
 */
object BiomeTextures {

    private const val CELL_SIZE = 32
    private const val COLS = 5
    private const val ROWS = 4 // 16 biomes + 1 reserved white cell = 17, fits in 5*4 = 20

    // Corners sit this far from a face's center, as a fraction of one atlas
    // cell - small enough that neighboring cells' patterns never bleed in.
    private const val CORNER_RADIUS_FRACTION = 0.42f

    private val biomeIndex: Map<Biome, Int> = Biome.entries.withIndex().associate { (i, b) -> b to i }
    private val whiteIndex = Biome.entries.size

    fun build(): Texture {
        val pixmap = Pixmap(COLS * CELL_SIZE, ROWS * CELL_SIZE, Pixmap.Format.RGBA8888)
        val rng = Random(1234) // fixed: this is a visual art asset, not world data - no need to vary with the world seed

        for (biome in Biome.entries) {
            val (x0, y0) = cellOrigin(biomeIndex.getValue(biome))
            paintBiome(pixmap, x0, y0, biome, rng)
        }
        val (wx, wy) = cellOrigin(whiteIndex)
        pixmap.setColor(Color.WHITE)
        pixmap.fillRectangle(wx, wy, CELL_SIZE, CELL_SIZE)

        val texture = Texture(pixmap)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
        return texture
    }

    // A face's UVs depend only on its biome and whether it's a pentagon or
    // hexagon, so they're computed once here and read by array index per
    // face. The per-face alternative (a biome -> index map lookup plus a
    // Pair<Float, Float> of boxed floats, twice per face) measured ~2.2s of
    // a ~2.5s mesh build at ~396k tiles on the Android emulator - nearly all
    // of it hashing enums and allocating, none of it actual work.
    private val CENTER_UVS = FloatArray(Biome.entries.size * 2).also { out ->
        for (biome in Biome.entries) {
            val (u, v) = cellCenterUV(biomeIndex.getValue(biome))
            out[biome.ordinal * 2] = u
            out[biome.ordinal * 2 + 1] = v
        }
    }

    // [cornerCount][biome ordinal] -> (u0, v0, u1, v1, ...). Only 5 (pentagon)
    // and 6 (hexagon) are populated, matching the only face shapes there are.
    private val CORNER_UVS: Array<Array<FloatArray>?> = arrayOfNulls<Array<FloatArray>>(7).also { table ->
        for (cornerCount in 5..6) {
            val offsets = buildCornerOffsets(cornerCount)
            table[cornerCount] = Array(Biome.entries.size) { ordinal ->
                val (cu, cv) = cellCenterUV(biomeIndex.getValue(Biome.entries[ordinal]))
                FloatArray(cornerCount * 2) { k -> (if (k % 2 == 0) cu else cv) + offsets[k] }
            }
        }
    }

    fun centerU(biome: Biome): Float = CENTER_UVS[biome.ordinal * 2]
    fun centerV(biome: Biome): Float = CENTER_UVS[biome.ordinal * 2 + 1]

    /**
     * The (u0, v0, u1, v1, ...) of every corner of a [cornerCount]-gon face
     * (5 or 6) in [biome]. Shared and precomputed - read only, never modify.
     */
    fun cornerUVs(biome: Biome, cornerCount: Int): FloatArray =
        (CORNER_UVS.getOrNull(cornerCount) ?: error("no UVs for a $cornerCount-corner face"))[biome.ordinal]

    // Per-corner (cos, sin) offsets from the cell center, scaled to UV space.
    private fun buildCornerOffsets(cornerCount: Int): FloatArray {
        val ru = CORNER_RADIUS_FRACTION / COLS
        val rv = CORNER_RADIUS_FRACTION / ROWS
        return FloatArray(cornerCount * 2).also { offsets ->
            for (c in 0 until cornerCount) {
                val angle = MathUtils.PI2 * c / cornerCount
                offsets[c * 2] = MathUtils.cos(angle) * ru
                offsets[c * 2 + 1] = MathUtils.sin(angle) * rv
            }
        }
    }

    /** UV of the reserved solid-white cell, for geometry that just wants its own vertex color. */
    fun whiteUV(): Pair<Float, Float> = cellCenterUV(whiteIndex)

    private fun cellOrigin(index: Int): Pair<Int, Int> = (index % COLS) * CELL_SIZE to (index / COLS) * CELL_SIZE

    private fun cellCenterUV(index: Int): Pair<Float, Float> {
        val col = index % COLS
        val row = index / COLS
        return (col + 0.5f) / COLS to (row + 0.5f) / ROWS
    }

    private fun paintBiome(pixmap: Pixmap, x0: Int, y0: Int, biome: Biome, rng: Random) {
        val base = biome.color
        when (biome) {
            Biome.OCEAN -> waves(pixmap, x0, y0, base, lighten(base, 0.25f), waveCount = 3, rng = rng)
            Biome.RIVER -> waves(pixmap, x0, y0, base, lighten(base, 0.3f), waveCount = 5, rng = rng)
            Biome.LAKE -> waves(pixmap, x0, y0, base, lighten(base, 0.2f), waveCount = 2, rng = rng)
            Biome.DESERT -> dunes(pixmap, x0, y0, base, darken(base, 0.85f))
            Biome.SAVANNAH -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.8f), lighten(base, 0.2f)), 0.08f, rng)
            Biome.PLAINS -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.85f), lighten(base, 0.2f)), 0.12f, rng)
            Biome.FOREST -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.6f), darken(base, 0.8f)), 0.28f, rng)
            Biome.JUNGLE -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.6f), lighten(base, 0.3f)), 0.32f, rng)
            Biome.DEEP_FOREST -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.5f), darken(base, 0.75f)), 0.35f, rng)
            Biome.TAIGA -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.7f), lighten(base, 0.15f)), 0.20f, rng)
            Biome.SWAMP -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.7f), Color(0.24f, 0.22f, 0.12f, 1f)), 0.18f, rng)
            Biome.MOUNTAIN -> cracks(pixmap, x0, y0, base, darken(base, 0.6f), lighten(base, 0.25f), rng)
            Biome.FOOTHILLS -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.65f), lighten(base, 0.2f)), 0.22f, rng)
            Biome.VOLCANO -> cracks(pixmap, x0, y0, base, darken(base, 0.5f), Color(1f, 0.55f, 0.1f, 1f), rng)
            Biome.TUNDRA -> speckles(pixmap, x0, y0, base, listOf(darken(base, 0.75f), lighten(base, 0.25f)), 0.06f, rng)
            Biome.ARCTIC -> cracks(pixmap, x0, y0, base, darken(base, 0.85f), Color.WHITE, rng)
        }
    }

    private fun darken(c: Color, factor: Float) = Color(c.r * factor, c.g * factor, c.b * factor, 1f)

    private fun lighten(c: Color, factor: Float) =
        Color(c.r + (1f - c.r) * factor, c.g + (1f - c.g) * factor, c.b + (1f - c.b) * factor, 1f)

    private fun waves(pixmap: Pixmap, x0: Int, y0: Int, base: Color, highlight: Color, waveCount: Int, rng: Random) {
        val phase = rng.nextFloat() * MathUtils.PI2
        for (y in 0 until CELL_SIZE) {
            for (x in 0 until CELL_SIZE) {
                val t = MathUtils.sin((x.toFloat() / CELL_SIZE) * waveCount * MathUtils.PI2 + y * 0.3f + phase)
                pixmap.drawPixel(x0 + x, y0 + y, Color.rgba8888(if (t > 0.2f) highlight else base))
            }
        }
    }

    private fun dunes(pixmap: Pixmap, x0: Int, y0: Int, base: Color, dark: Color) {
        for (y in 0 until CELL_SIZE) {
            val offset = (MathUtils.sin(y * 0.5f) * 3f).toInt()
            for (x in 0 until CELL_SIZE) {
                val stripe = ((x + offset) / 4) % 2 == 0
                pixmap.drawPixel(x0 + x, y0 + y, Color.rgba8888(if (stripe) base else dark))
            }
        }
    }

    private fun speckles(pixmap: Pixmap, x0: Int, y0: Int, base: Color, speckleColors: List<Color>, density: Float, rng: Random) {
        pixmap.setColor(base)
        pixmap.fillRectangle(x0, y0, CELL_SIZE, CELL_SIZE)
        val count = (CELL_SIZE * CELL_SIZE * density).toInt()
        repeat(count) {
            val x = rng.nextInt(CELL_SIZE)
            val y = rng.nextInt(CELL_SIZE)
            pixmap.drawPixel(x0 + x, y0 + y, Color.rgba8888(speckleColors[rng.nextInt(speckleColors.size)]))
        }
    }

    private fun cracks(pixmap: Pixmap, x0: Int, y0: Int, base: Color, dark: Color, light: Color, rng: Random) {
        speckles(pixmap, x0, y0, base, listOf(dark, light), 0.15f, rng)
        repeat(3) {
            var x = rng.nextInt(CELL_SIZE)
            var y = 0
            while (y < CELL_SIZE) {
                pixmap.drawPixel(x0 + x.coerceIn(0, CELL_SIZE - 1), y0 + y, Color.rgba8888(dark))
                x += rng.nextInt(3) - 1
                y += 1
            }
        }
    }
}
