package dev.joncbender.openworld

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tile art in the manner of a hand-inked fantasy map: parchment tinted per biome, with pen-drawn glyphs
 * (peaks, trees, reeds, waves) standing upright. Same contract as [BiomeArt]. Glyphs have a "up", so these
 * tiles are turned to face north (see [BiomeTextures.uprightUVs]) rather than rotated at random.
 */
internal object InkArt {
    internal val INK = floatArrayOf(0.20f, 0.13f, 0.08f)
    internal val WATER_INK = floatArrayOf(0.22f, 0.30f, 0.38f)

    private fun parchment(biome: Biome): FloatArray = when (biome) {
        Biome.OCEAN -> floatArrayOf(0.66f, 0.73f, 0.72f)
        Biome.RIVER -> floatArrayOf(0.62f, 0.74f, 0.76f)
        Biome.LAKE -> floatArrayOf(0.62f, 0.73f, 0.76f)
        Biome.DESERT -> floatArrayOf(0.91f, 0.81f, 0.58f)
        Biome.SAVANNAH -> floatArrayOf(0.85f, 0.78f, 0.52f)
        Biome.PLAINS -> floatArrayOf(0.82f, 0.80f, 0.56f)
        Biome.FOREST -> floatArrayOf(0.69f, 0.72f, 0.48f)
        Biome.JUNGLE -> floatArrayOf(0.62f, 0.72f, 0.46f)
        Biome.DEEP_FOREST -> floatArrayOf(0.57f, 0.64f, 0.42f)
        Biome.TAIGA -> floatArrayOf(0.64f, 0.70f, 0.55f)
        Biome.SWAMP -> floatArrayOf(0.64f, 0.67f, 0.50f)
        Biome.MOUNTAIN -> floatArrayOf(0.78f, 0.72f, 0.60f)
        Biome.FOOTHILLS -> floatArrayOf(0.80f, 0.73f, 0.54f)
        Biome.VOLCANO -> floatArrayOf(0.74f, 0.58f, 0.46f)
        Biome.TUNDRA -> floatArrayOf(0.80f, 0.79f, 0.68f)
        Biome.ARCTIC -> floatArrayOf(0.94f, 0.93f, 0.88f)
    }

    private val means = arrayOfNulls<FloatArray>(Biome.entries.size)

    /** The average color of [biome]'s cells (measured, so the flat ocean underlay can match the ocean art exactly). */
    @Synchronized
    fun meanColor(biome: Biome): FloatArray = (means[biome.ordinal] ?: measure(biome).also { means[biome.ordinal] = it }).copyOf()

    private fun measure(biome: Biome): FloatArray {
        val size = 64
        val cell = IntArray(size * size)
        val sum = DoubleArray(3)
        for (variant in 0 until BiomeArt.VARIANTS) {
            paint(biome, variant, size, cell)
            for (p in cell) {
                sum[0] += (p ushr 24) and 255
                sum[1] += (p ushr 16) and 255
                sum[2] += (p ushr 8) and 255
            }
        }
        val n = 255.0 * cell.size * BiomeArt.VARIANTS
        return FloatArray(3) { (sum[it] / n).toFloat() }
    }

    fun paint(biome: Biome, variant: Int, size: Int, out: IntArray) {
        require(variant in 0 until BiomeArt.VARIANTS)
        require(size >= 32 && size % 8 == 0)
        require(out.size.toLong() == size.toLong() * size)
        val pen = Pen(size, biome.ordinal * 31 + variant)
        pen.paper(parchment(biome))
        when (biome) {
            Biome.OCEAN -> waves(pen, variant, if (variant == 1) 4 else 5)
            Biome.RIVER -> river(pen, variant)
            Biome.LAKE -> lake(pen, variant)
            Biome.DESERT -> dunes(pen, variant)
            Biome.SAVANNAH -> { scatter(pen, 16f) { x, y, i -> grass(pen, x, y, 3.2f) }; pen.dots(10, 0.7f, 0.5f) }
            Biome.PLAINS -> { scatter(pen, 18f) { x, y, i -> grass(pen, x, y, 2.4f) }; pen.dots(6, 0.6f, 0.4f) }
            Biome.FOREST -> scatter(pen, 12f) { x, y, i -> roundTree(pen, x, y, 4.2f + (i % 2)) }
            Biome.JUNGLE -> scatter(pen, 10f) { x, y, i -> roundTree(pen, x, y, 4.8f + (i % 2)) }
            Biome.DEEP_FOREST -> scatter(pen, 8.5f) { x, y, i -> roundTree(pen, x, y, 5.2f) }
            Biome.TAIGA -> scatter(pen, 11f) { x, y, i -> conifer(pen, x, y, 9f + (i % 3)) }
            Biome.SWAMP -> { scatter(pen, 13f) { x, y, i -> reeds(pen, x, y) }; swampDashes(pen) }
            Biome.MOUNTAIN -> mountains(pen, variant)
            Biome.FOOTHILLS -> hills(pen, variant)
            Biome.VOLCANO -> volcano(pen, variant)
            Biome.TUNDRA -> { scatter(pen, 14f) { x, y, i -> grass(pen, x, y, 2f) }; pen.dots(14, 0.6f, 0.5f) }
            Biome.ARCTIC -> { scatter(pen, 22f) { x, y, i -> pen.stroke(x - 3, y, x + 3, y - 1, 0.5f, 0.5f, WATER_INK) }; pen.dots(5, 0.5f, 0.3f) }
        }
        pen.pack(out)
    }

    // --- glyphs; coordinates are in a 64-unit cell, whatever the pixel size ---

    private inline fun scatter(pen: Pen, spacing: Float, glyph: (Float, Float, Int) -> Unit) {
        // Jittered grid, kept inside the disc of the cell that a tile actually samples.
        var placed = 0
        var gy = 14f
        var row = 0
        while (gy <= 52f) {
            var gx = 14f + (row and 1) * spacing / 2
            while (gx <= 52f) {
                val x = gx + (pen.rnd(placed * 2) - 0.5f) * spacing * 0.5f
                val y = gy + (pen.rnd(placed * 2 + 1) - 0.5f) * spacing * 0.5f
                if (hypot(x - 32f, y - 32f) < 23f) { glyph(x, y, placed); placed++ }
                gx += spacing
            }
            gy += spacing * 0.9f
            row++
        }
    }

    private fun roundTree(pen: Pen, x: Float, y: Float, r: Float) {
        pen.arc(x, y - r * 0.3f, r, r, 0f, (2 * PI).toFloat(), 0.55f, INK)
        pen.stroke(x, y + r * 0.7f, x, y + r * 1.5f, 0.55f, 1f, INK)
    }

    private fun conifer(pen: Pen, x: Float, y: Float, h: Float) {
        pen.stroke(x, y - h * 0.6f, x - h * 0.3f, y + h * 0.2f, 0.55f, 1f, INK)
        pen.stroke(x, y - h * 0.6f, x + h * 0.3f, y + h * 0.2f, 0.55f, 1f, INK)
        pen.stroke(x - h * 0.3f, y + h * 0.2f, x + h * 0.3f, y + h * 0.2f, 0.5f, 1f, INK)
        pen.stroke(x, y + h * 0.2f, x, y + h * 0.5f, 0.5f, 1f, INK)
    }

    private fun grass(pen: Pen, x: Float, y: Float, h: Float) {
        pen.stroke(x - h * 0.5f, y, x - h * 0.2f, y - h, 0.45f, 0.9f, INK)
        pen.stroke(x, y, x, y - h * 1.2f, 0.45f, 0.9f, INK)
        pen.stroke(x + h * 0.5f, y, x + h * 0.2f, y - h, 0.45f, 0.9f, INK)
    }

    private fun reeds(pen: Pen, x: Float, y: Float) {
        for (k in -1..1) pen.stroke(x + k * 1.6f, y + 3f, x + k * 1.9f, y - 4f + abs(k), 0.45f, 0.9f, INK)
        pen.stroke(x - 4f, y + 3.5f, x + 4f, y + 3.5f, 0.4f, 0.8f, WATER_INK)
    }

    private fun abs(k: Int) = if (k < 0) -k else k

    private fun swampDashes(pen: Pen) {
        for (i in 0 until 6) {
            val x = 14f + pen.rnd(40 + i) * 36f
            val y = 16f + pen.rnd(60 + i) * 32f
            pen.stroke(x, y, x + 4f, y, 0.4f, 0.7f, WATER_INK)
        }
    }

    private fun peak(pen: Pen, cx: Float, top: Float, w: Float, h: Float) {
        pen.stroke(cx - w, top + h, cx, top, 0.75f, 1f, INK)
        pen.stroke(cx, top, cx + w, top + h, 0.75f, 1f, INK)
        // Hatching on the shaded (right) flank.
        for (k in 1..5) {
            val t = k / 6f
            pen.stroke(cx + w * t, top + h * t, cx + w * t * 0.35f, top + h * t + h * 0.22f, 0.4f, 0.85f, INK)
        }
        pen.stroke(cx - w * 0.2f, top + h * 0.35f, cx - w * 0.6f, top + h * 0.85f, 0.35f, 0.7f, INK)
    }

    private fun mountains(pen: Pen, variant: Int) {
        when (variant) {
            0 -> peak(pen, 32f, 12f, 16f, 30f)
            1 -> { peak(pen, 21f, 20f, 12f, 22f); peak(pen, 41f, 15f, 13f, 27f) }
            else -> { peak(pen, 32f, 10f, 12f, 24f); peak(pen, 16f, 28f, 10f, 17f); peak(pen, 47f, 29f, 9f, 16f) }
        }
    }

    private fun hump(pen: Pen, cx: Float, cy: Float, rx: Float, ry: Float) {
        pen.arc(cx, cy, rx, ry, 0f, PI.toFloat(), 0.7f, INK, up = true)
        for (k in 1..3) pen.stroke(cx + rx * (0.15f + 0.22f * k), cy - ry * 0.55f, cx + rx * (0.05f + 0.22f * k), cy - ry * 0.1f, 0.4f, 0.8f, INK)
    }

    private fun hills(pen: Pen, variant: Int) {
        when (variant) {
            0 -> { hump(pen, 32f, 38f, 15f, 12f) }
            1 -> { hump(pen, 22f, 36f, 11f, 9f); hump(pen, 41f, 40f, 11f, 9f) }
            else -> { hump(pen, 32f, 28f, 10f, 8f); hump(pen, 18f, 44f, 10f, 8f); hump(pen, 46f, 44f, 10f, 8f) }
        }
    }

    private fun volcano(pen: Pen, variant: Int) {
        val vent = 8f + variant * 2f
        pen.stroke(32f - vent, 18f, 15f, 46f, 0.75f, 1f, INK)
        pen.stroke(32f + vent, 18f, 49f, 46f, 0.75f, 1f, INK)
        pen.stroke(32f - vent, 18f, 32f + vent, 18f, 0.6f, 0.9f, INK)
        for (k in 1..4) pen.stroke(32f + vent + k * 3f, 18f + k * 5.5f, 32f + vent + k * 1.2f, 22f + k * 5.5f, 0.4f, 0.8f, INK)
        pen.arc(32f, 14f, 3f + variant, 2.5f, 0.3f, 5.5f, 0.45f, INK)
        pen.arc(34f, 10f, 2.5f, 2f, 0.3f, 5.5f, 0.4f, INK)
    }

    private fun dunes(pen: Pen, variant: Int) {
        val rows = 3 + variant
        for (i in 0 until rows) {
            val y = 16f + i * (32f / rows) + pen.rnd(i) * 3f
            val x = 14f + pen.rnd(10 + i) * 10f
            pen.arc(x + 9f, y, 9f, 3f, 0f, PI.toFloat(), 0.55f, INK, up = true)
            pen.stroke(x + 18f, y, x + 22f, y + 1f, 0.45f, 0.8f, INK)
        }
        pen.dots(5, 0.5f, 0.5f, 16f)
    }

    private fun waves(pen: Pen, variant: Int, rows: Int) {
        for (i in 0 until rows) {
            val y = 10f + i * (46f / rows) + pen.rnd(i) * 3f
            val x = 12f + pen.rnd(20 + i) * 18f + (i and 1) * 6f
            val len = if (variant == 2) 9f else 14f
            pen.wavy(x, y, len, 1.6f, 0.5f, 0.75f, WATER_INK)
        }
    }

    private fun river(pen: Pen, variant: Int) {
        val phase = variant * 1.7f
        for (side in intArrayOf(-3, 3)) {
            var px = 0f; var py = 0f
            for (s in 0..40) {
                val t = s / 40f
                val y = 4f + t * 56f
                val x = 32f + sin(t * 6.4f + phase) * 9f + side
                if (s > 0) pen.stroke(px, py, x, y, 0.45f, 0.85f, WATER_INK)
                px = x; py = y
            }
        }
        pen.wavy(14f, 14f + variant * 3f, 8f, 1.2f, 0.4f, 0.6f, WATER_INK)
        pen.wavy(40f, 46f - variant * 3f, 8f, 1.2f, 0.4f, 0.6f, WATER_INK)
    }

    private fun lake(pen: Pen, variant: Int) {
        val tau = (2 * PI).toFloat()
        pen.arc(32f, 32f, 17f + variant * 2f, 11f + variant, 0f, tau, 0.6f, WATER_INK)
        pen.arc(32f, 32f, 11f + variant, 6f + variant, 0f, tau, 0.45f, WATER_INK)
        pen.wavy(25f, 32f, 12f, 1.2f, 0.4f, 0.6f, WATER_INK)
    }

    /** A float canvas drawn with antialiased round pen strokes; [rnd] is a deterministic stream per cell. */
    private class Pen(val size: Int, val seed: Int) {
        private val px = FloatArray(size * size * 3)
        private val f = size / 64f

        fun rnd(i: Int): Float {
            var h = seed * 0x1f123bb5 xor i * 0x27d4eb2d
            h = (h xor (h ushr 16)) * 0x7feb352d
            h = (h xor (h ushr 15)) * -0x7b935975
            return ((h xor (h ushr 16)) ushr 8) / 16777216f
        }

        fun paper(base: FloatArray) {
            for (y in 0 until size) for (x in 0 until size) {
                // Fine grain plus slow blotches, like old paper.
                val grain = (rnd(1000 + y * size + x) - 0.5f) * 0.05f
                val blotch = (rnd(900000 + (y * 8 / size) * 8 + x * 8 / size) - 0.5f) * 0.035f
                for (c in 0..2) px[(y * size + x) * 3 + c] = (base[c] + grain + blotch).coerceIn(0f, 1f)
            }
        }

        fun dots(count: Int, radius: Float, alpha: Float, reach: Float = 24f) {
            for (i in 0 until count) {
                val x = 12f + rnd(300 + i * 2) * 40f
                val y = 12f + rnd(301 + i * 2) * 40f
                if (hypot(x - 32f, y - 32f) < reach) stamp(x, y, radius, alpha, INK)
            }
        }

        fun stamp(x: Float, y: Float, radius: Float, alpha: Float, ink: FloatArray) {
            val cx = x * f; val cy = y * f; val r = radius * f
            for (py in max(0, (cy - r - 1).toInt())..min(size - 1, (cy + r + 1).toInt())) {
                for (pxl in max(0, (cx - r - 1).toInt())..min(size - 1, (cx + r + 1).toInt())) {
                    val d = hypot(pxl + 0.5f - cx, py + 0.5f - cy)
                    val a = (r + 0.5f - d).coerceIn(0f, 1f) * alpha
                    if (a > 0f) for (c in 0..2) {
                        val i = (py * size + pxl) * 3 + c
                        px[i] += (ink[c] - px[i]) * a
                    }
                }
            }
        }

        fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, alpha: Float, ink: FloatArray) {
            val steps = max(1, ceil(hypot(x1 - x0, y1 - y0) * f).toInt())
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                stamp(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius, alpha, ink)
            }
        }

        /** An elliptical arc from angle [a0] to [a1]; with [up] it bulges upward (screen y grows downward). */
        fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, radius: Float, ink: FloatArray, up: Boolean = false) {
            val steps = max(8, ceil((a1 - a0) * max(rx, ry) * f).toInt())
            val sign = if (up) -1f else 1f
            for (s in 0..steps) {
                val a = a0 + (a1 - a0) * s / steps
                stamp(cx + cos(a) * rx, cy + sign * sin(a) * ry, radius, 1f, ink)
            }
        }

        /** A little "~" stroke. */
        fun wavy(x: Float, y: Float, length: Float, amplitude: Float, radius: Float, alpha: Float, ink: FloatArray) {
            val steps = max(6, (length * f).toInt())
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                stamp(x + t * length, y + sin(t * 2f * PI.toFloat()) * amplitude, radius, alpha, ink)
            }
        }

        fun pack(out: IntArray) {
            for (i in out.indices) {
                val r = (px[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (px[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (px[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i] = (r shl 24) or (g shl 16) or (b shl 8) or 255
            }
        }
    }
}
