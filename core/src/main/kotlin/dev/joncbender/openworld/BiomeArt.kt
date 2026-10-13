package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Procedural pixel art for one biome tile, 3 variants each. Pure and deterministic: same arguments, same pixels. */
internal object BiomeArt {
    const val VARIANTS = 3

    /**
     * The color every cell of [biome] averages to. Open ocean is drawn as one flat color (the globe's underlay),
     * so the ocean's art has to average to that color for the seam between the two not to show; the ocean is
     * otherwise just its [Biome.color] and the underlay takes its color from here.
     */
    fun meanColor(biome: Biome): FloatArray =
        if (biome == Biome.OCEAN) OCEAN_MEAN.copyOf() else floatArrayOf(biome.color.r, biome.color.g, biome.color.b)

    private val OCEAN_MEAN = floatArrayOf(0.20f, 0.37f, 0.60f)

    /** Biomes drawn with a fixed light from the upper left; their tiles must never be rotated. */
    fun isRelief(biome: Biome): Boolean = when (biome) {
        Biome.MOUNTAIN, Biome.FOOTHILLS, Biome.VOLCANO -> true
        Biome.OCEAN, Biome.RIVER, Biome.LAKE, Biome.DESERT,
        Biome.SAVANNAH, Biome.PLAINS, Biome.FOREST, Biome.JUNGLE,
        Biome.DEEP_FOREST, Biome.TAIGA, Biome.SWAMP, Biome.TUNDRA,
        Biome.ARCTIC -> false
    }

    /**
     * Fills [out] (size*size packed RGBA8888 ints, row-major, row 0 = top, 0xRRGGBBAA) with [biome]'s
     * [variant] (0 until VARIANTS). Every pixel is opaque (alpha 255). [size] is 64 in production but any
     * multiple of 8 >= 32 must work (tests use 32); scale features with size.
     */
    fun paint(biome: Biome, variant: Int, size: Int, out: IntArray) {
        require(variant in 0 until VARIANTS)
        require(size >= 32 && size % 8 == 0)
        require(out.size.toLong() == size.toLong() * size)

        // A 32-cell drawing grid gives crisp, chunky 2-pixel marks at production size.
        val c = Canvas(biome.ordinal, variant)
        for (y in 0..31) for (x in 0..31) {
            val n = c.random(x / 2, y / 2, 0, 100)
            c.put(x, y, if (n < 12) 1 else if (n > 88) 3 else 2)
        }

        when (biome) {
            Biome.OCEAN -> {
                val bands = if (variant == 1) 5 else 9
                for (i in 0 until bands) {
                    val y = 2 + i * (if (variant == 1) 7 else 4)
                    val start = if (variant == 2) c.random(i, y, 1, 20) else -3
                    val length = if (variant == 2) 10 else 38
                    c.wave(start, y, length, 1, i, 1 + i % 2)
                    c.wave(start + 2, y + 1, length - 5, 3, i, 1 + i % 2)
                }
                c.flecks(if (variant == 2) 18 else 7, 4, 12, 2)
            }
            Biome.RIVER -> {
                // Distinct bends, braided channels, and a broad meander.
                val strands = if (variant == 1) 3 else 1
                for (s in 0 until strands) {
                    var previous = 12 + s * 6
                    for (y in 0..31) {
                        val bend = when (variant) {
                            0 -> abs((y + 6) % 24 - 12) / 2 - 3
                            1 -> abs((y + s * 5) % 16 - 8) / 3 - 1
                            else -> abs(y - 16) / 2 - 4
                        }
                        val x = 12 + s * 6 + bend
                        c.line(previous, y - 1, x, y, 1, 3)
                        c.line(previous, y - 1, x, y, 3, 2)
                        c.put(x, y, 4)
                        previous = x
                    }
                }
                c.flecks(18, 3, 21, 2)
            }
            Biome.LAKE -> {
                for (i in 0 until 8) {
                    val x = c.random(i, 0, 30, 26)
                    val y = c.random(i, 0, 31, 30)
                    c.line(x, y, x + 3 + i % 3, y, 1, 0)
                }
                val cx = intArrayOf(16, 11, 21)[variant]
                val cy = intArrayOf(15, 20, 11)[variant]
                c.ellipse(cx, cy, 7, 4, 2)
                c.line(cx - 3, cy, cx + 3, cy, 3, 0)
                c.put(cx - 1, cy, 4)
                c.flecks(5, 4, 32, 2)
            }
            Biome.DESERT -> {
                if (variant != 2) {
                    for (i in 0 until 5) {
                        val y = i * 7 - 3
                        c.wave(-3, y, 38, 1, i + variant * 3, 3)
                        c.wave(-3, y + 1, 38, 3, i + variant * 3, 3)
                    }
                    c.flecks(22, 3, 40, 1)
                } else {
                    c.ellipse(16, 16, 9, 7, 1)
                    for (i in 0 until 25) {
                        val x = 8 + c.random(i, 0, 41, 17)
                        val y = 10 + c.random(i, 0, 42, 13)
                        c.ellipse(x, y, 1 + i % 2, 1, if (i % 3 == 0) 0 else 3)
                    }
                    c.wave(-2, 26, 36, 3, 2, 2)
                }
            }
            Biome.SAVANNAH -> {
                c.tufts(36, 1, 50)
                when (variant) {
                    0 -> {
                        // Top-down acacia: an irregular, horizontally broad crown.
                        c.ellipse(16, 16, 7, 4, 0)
                        c.blob(13, 15, 4, 1, 51)
                        c.blob(19, 16, 4, 1, 52)
                        c.ellipse(16, 16, 2, 1, 3)
                    }
                    1 -> for (i in 0 until 9) {
                        c.blob(4 + c.random(i, 0, 53, 24),
                            4 + c.random(i, 0, 54, 24), 2, 1, 55 + i)
                    }
                    else -> {
                        c.blob(12, 18, 6, 3, 56)
                        c.blob(22, 10, 4, 3, 57)
                        c.tufts(20, 0, 58)
                    }
                }
            }
            Biome.PLAINS -> {
                if (variant == 1) {
                    c.blob(10, 13, 7, 3, 60)
                    c.blob(23, 21, 7, 1, 61)
                } else if (variant == 2) {
                    c.blob(16, 16, 9, 3, 62)
                    c.flecks(30, 4, 63, 1)
                }
                c.tufts(45, 1, 64)
                c.flecks(if (variant == 0) 12 else 5, 4, 65, 1)
            }
            Biome.FOREST -> c.canopy(variant, 4, 70, false, false)
            Biome.JUNGLE -> {
                c.canopy(variant, 5, 80, true, false)
                for (i in 0 until 7) {
                    val x = 6 + c.random(i, 0, 81, 20)
                    val y = 6 + c.random(i, 0, 82, 20)
                    c.star(x, y, 2, 4)
                    c.put(x, y, 2)
                }
            }
            Biome.DEEP_FOREST -> {
                c.canopy(variant, 4, 90, true, true)
                c.flecks(8, 4, 91, 1)
            }
            Biome.TAIGA -> {
                c.flecks(24, 3, 100, 2)
                for (i in 0 until 28) {
                    val x = c.random(i, 0, 101, 32)
                    val y = c.random(i, 0, 102, 32)
                    val cleared = when (variant) {
                        0 -> abs(x - 16) < 3
                        1 -> (x - 16) * (x - 16) + (y - 16) * (y - 16) < 30
                        else -> abs(x + y - 31) < 4
                    }
                    if (!cleared) {
                        c.star(x, y, 2 + i % 2, 0)
                        c.star(x, y, 1, 1)
                        c.put(x, y, 2)
                    }
                }
                c.flecks(if (variant == 2) 26 else 9, 4, 103, 1)
            }
            Biome.SWAMP -> {
                when (variant) {
                    0 -> c.blob(16, 16, 9, 0, 110)
                    1 -> {
                        c.blob(10, 12, 6, 0, 111)
                        c.blob(22, 21, 7, 0, 112)
                    }
                    else -> for (i in 0..7) {
                        c.blob(4 + i * 4, 15 + (i % 3 - 1) * 3, 4, 0, 113 + i)
                    }
                }
                for (i in 0 until 19) {
                    val x = c.random(i, 0, 120, 32)
                    val y = c.random(i, 0, 121, 32)
                    c.blob(x, y, 2, 3, 122 + i)
                    c.put(x, y, 4)
                }
                c.tufts(14, 1, 143)
            }
            Biome.MOUNTAIN -> {
                when (variant) {
                    0 -> {
                        c.peak(11, 7, 7, 15, false)
                        c.peak(21, 12, 6, 13, false)
                    }
                    1 -> {
                        c.peak(8, 13, 5, 11, false)
                        c.peak(16, 5, 8, 20, false)
                        c.peak(25, 15, 5, 12, false)
                    }
                    else -> c.peak(16, 5, 11, 23, true)
                }
                c.flecks(26, 1, 150, 1)
            }
            Biome.FOOTHILLS -> {
                val count = if (variant == 0) 3 else if (variant == 1) 6 else 2
                for (i in 0 until count) {
                    val x = if (variant == 2) 12 + i * 9 else 7 + (i * 9) % 22
                    val y = if (variant == 2) 13 + i * 8 else 10 + (i * 7) % 17
                    val r = if (variant == 2) 8 else 5
                    c.ellipse(x + 2, y + 2, r, r - 1, 0)
                    c.ellipse(x, y, r, r - 1, 1)
                    c.ellipse(x - 2, y - 2, r - 2, r - 3, 3)
                    c.line(x - r + 2, y - 2, x - 1, y - r + 2, 4, 0)
                }
                c.tufts(14, 1, 160)
            }
            Biome.VOLCANO -> {
                when (variant) {
                    0 -> {
                        c.line(5, 8, 17, 16, 0, 2)
                        c.line(17, 16, 25, 27, 0, 2)
                        c.line(17, 16, 26, 10, 0, 1)
                        c.line(5, 8, 17, 16, 4, 0)
                        c.line(17, 16, 25, 27, 4, 0)
                        c.line(17, 16, 26, 10, 3, 0)
                    }
                    1 -> {
                        c.ellipse(18, 18, 11, 9, 0)
                        c.ellipse(16, 15, 10, 8, 1)
                        c.ellipse(14, 13, 8, 6, 3)
                        c.ellipse(16, 15, 6, 5, 0)
                        c.ellipse(16, 15, 4, 3, 4)
                        c.ellipse(16, 15, 2, 1, 3)
                        c.line(20, 19, 25, 27, 4, 0)
                    }
                    else -> {
                        c.triangle(13, 9, 31, 23, 19, 31, 1)
                        c.triangle(13, 9, 29, 22, 20, 27, 3)
                        c.peak(13, 7, 7, 13, false)
                        c.ellipse(13, 11, 3, 2, 0)
                        c.line(13, 12, 18, 20, 4, 0)
                    }
                }
                c.flecks(15, 1, 170, 1)
            }
            Biome.TUNDRA -> {
                val centers = when (variant) {
                    0 -> intArrayOf(10, 12, 23, 21)
                    1 -> intArrayOf(10, 22, 21, 10)
                    else -> intArrayOf(16, 16, 25, 25)
                }
                for (i in 0..1) {
                    c.blob(centers[i * 2], centers[i * 2 + 1],
                        if (variant == 2) 6 else 4, 1, 180 + i)
                }
                c.tufts(23, 3, 182)
                c.flecks(22, 4, 183, 2)
                c.ellipse(if (variant == 1) 16 else 22, 17, 3, 2, 0)
                c.put(if (variant == 1) 16 else 22, 17, 1)
            }
            Biome.ARCTIC -> {
                when (variant) {
                    0 -> {
                        c.line(2, 10, 17, 14, 1, 0)
                        c.line(17, 14, 28, 5, 1, 0)
                        c.line(17, 14, 22, 30, 0, 0)
                        c.line(22, 30, 29, 23, 1, 0)
                    }
                    1 -> for (i in 0..3) {
                        c.wave(-2, 5 + i * 8, 36, 1, i, 2)
                        c.wave(-2, 7 + i * 8, 36, 4, i, 2)
                    }
                    else -> {
                        c.blob(16, 16, 10, 1, 190)
                        c.blob(16, 16, 8, 4, 190)
                        c.line(11, 10, 18, 17, 0, 0)
                        c.line(18, 17, 15, 24, 1, 0)
                        c.line(18, 17, 24, 14, 1, 0)
                    }
                }
                c.flecks(16, 3, 191, 2)
            }
        }

        val base = meanColor(biome)
        val tones = Array(5) { FloatArray(3) }
        for (channel in 0..2) {
            val b = base[channel]
            tones[0][channel] = b * 0.57f
            tones[1][channel] = b * 0.80f
            tones[2][channel] = b
            tones[3][channel] = b + (1f - b) * 0.18f
            tones[4][channel] = b + (1f - b) * 0.36f
        }
        if (biome == Biome.VOLCANO) {
            tones[4][0] = base[0] + (1f - base[0]) * 0.95f
            tones[4][1] = base[1] + (1f - base[1]) * 0.32f
            tones[4][2] = base[2] * 0.5f
        }

        // Count the actual output samples, including uneven scaling at sizes like 40.
        // Shift each palette channel, preserving hard edges and the five-tone limit,
        // so every composition averages to its biome color within byte rounding.
        val counts = IntArray(5)
        for (y in 0 until size) for (x in 0 until size) {
            counts[c.pixels[(y * 32 / size) * 32 + x * 32 / size]]++
        }
        val packed = IntArray(5)
        for (channel in 0..2) {
            var low = -1f
            var high = 1f
            repeat(24) {
                val shift = (low + high) * 0.5f
                var sum = 0.0
                for (t in 0..4) {
                    sum += (tones[t][channel] + shift).coerceIn(0f, 1f) * counts[t].toDouble()
                }
                if (sum / out.size < base[channel]) low = shift else high = shift
            }
            val shift = (low + high) * 0.5f
            for (t in 0..4) {
                val byte = ((tones[t][channel] + shift).coerceIn(0f, 1f) * 255f).roundToInt()
                packed[t] = packed[t] or (byte shl (24 - channel * 8))
            }
        }
        for (t in 0..4) packed[t] = packed[t] or 255
        for (y in 0 until size) for (x in 0 until size) {
            out[y * size + x] = packed[c.pixels[(y * 32 / size) * 32 + x * 32 / size]]
        }
    }

    private fun hash(biome: Int, variant: Int, x: Int, y: Int, feature: Int): Int {
        var h = biome * 0x1f123bb5 xor variant * 0x05491333
        h = h xor (x * 0x45d9f3b) xor (y * 0x119de1f3) xor (feature * 0x27d4eb2d)
        h = (h xor (h ushr 16)) * 0x7feb352d
        h = (h xor (h ushr 15)) * 0x846ca68b.toInt()
        return h xor (h ushr 16)
    }

    private class Canvas(val biome: Int, val variant: Int) {
        val pixels = IntArray(32 * 32)

        fun random(x: Int, y: Int, feature: Int, bound: Int): Int =
            (hash(biome, variant, x, y, feature) ushr 1) % bound

        fun put(x: Int, y: Int, tone: Int) {
            if (x in 0..31 && y in 0..31) pixels[y * 32 + x] = tone
        }

        fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, tone: Int) {
            if (rx <= 0 || ry <= 0) return
            for (y in max(0, cy - ry)..min(31, cy + ry)) {
                for (x in max(0, cx - rx)..min(31, cx + rx)) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx * ry * ry + dy * dy * rx * rx <= rx * rx * ry * ry) {
                        put(x, y, tone)
                    }
                }
            }
        }

        fun blob(cx: Int, cy: Int, r: Int, tone: Int, feature: Int) {
            ellipse(cx, cy, r - 1, r - 1, tone)
            for (i in 0..5) {
                val dx = random(cx, cy, feature + i * 2, r * 2 + 1) - r
                val dy = random(cx, cy, feature + i * 2 + 1, r * 2 + 1) - r
                ellipse(cx + dx / 2, cy + dy / 2, max(1, r / 2), max(1, r / 2), tone)
            }
        }

        fun line(x0: Int, y0: Int, x1: Int, y1: Int, tone: Int, radius: Int) {
            var x = x0
            var y = y0
            val dx = abs(x1 - x0)
            val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var error = dx + dy
            while (true) {
                if (radius == 0) put(x, y, tone) else ellipse(x, y, radius, radius, tone)
                if (x == x1 && y == y1) break
                val e = error * 2
                if (e >= dy) { error += dy; x += sx }
                if (e <= dx) { error += dx; y += sy }
            }
        }

        fun wave(x: Int, y: Int, length: Int, tone: Int, phase: Int, amplitude: Int) {
            for (i in 0 until length) {
                val bend = abs((i + phase * 3) % 20 - 10) * amplitude / 5
                put(x + i, y + bend, tone)
            }
        }

        fun star(x: Int, y: Int, r: Int, tone: Int) {
            line(x - r, y, x + r, y, tone, 0)
            line(x, y - r, x, y + r, tone, 0)
            if (r > 1) {
                line(x - r + 1, y - r + 1, x + r - 1, y + r - 1, tone, 0)
                line(x - r + 1, y + r - 1, x + r - 1, y - r + 1, tone, 0)
            }
        }

        fun flecks(count: Int, tone: Int, feature: Int, width: Int) {
            for (i in 0 until count) {
                val x = random(i, 0, feature, 32)
                val y = random(i, 1, feature, 32)
                line(x, y, x + width - 1, y, tone, 0)
            }
        }

        fun tufts(count: Int, tone: Int, feature: Int) {
            for (i in 0 until count) {
                val x = random(i, 0, feature, 32)
                val y = random(i, 1, feature, 32)
                put(x, y, tone)
                put(x - 1, y - 1, tone)
                put(x + 1, y + 1, tone)
            }
        }

        fun canopy(layout: Int, r: Int, feature: Int, dense: Boolean, dark: Boolean) {
            for (i in pixels.indices) pixels[i] = if (dark) 0 else 1
            val spacing = if (dense) 5 else 7
            for (row in -1..6) for (column in -1..6) {
                var x = column * spacing + (row and 1) * 3
                var y = row * spacing
                x += random(column, row, feature, 3) - 1
                y += random(column, row, feature + 1, 3) - 1
                val clearing = when (layout) {
                    0 -> false
                    1 -> abs(x - 16) + abs(y - 16) < 6
                    else -> abs(x + y - 31) < 3
                }
                if (!clearing) {
                    blob(x, y, r, if (dark) 1 else 2, feature + 2)
                    // Concentric interiors and scattered marks imply no light direction.
                    ellipse(x, y, max(1, r - 2), max(1, r - 2), if (dark) 2 else 3)
                    if (random(column, row, feature + 3, 4) == 0) put(x, y, 4)
                }
            }
        }

        private fun edge(ax: Int, ay: Int, bx: Int, by: Int, x: Int, y: Int): Int =
            (x - ax) * (by - ay) - (y - ay) * (bx - ax)

        fun triangle(ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int, tone: Int) {
            for (y in max(0, min(ay, min(by, cy)))..min(31, max(ay, max(by, cy)))) {
                for (x in max(0, min(ax, min(bx, cx)))..min(31, max(ax, max(bx, cx)))) {
                    val a = edge(ax, ay, bx, by, x, y)
                    val b = edge(bx, by, cx, cy, x, y)
                    val c = edge(cx, cy, ax, ay, x, y)
                    if ((a >= 0 && b >= 0 && c >= 0) || (a <= 0 && b <= 0 && c <= 0)) {
                        put(x, y, tone)
                    }
                }
            }
        }

        fun peak(x: Int, y: Int, r: Int, height: Int, snow: Boolean) {
            triangle(x + 2, y + 3, x - r + 3, y + height + 3,
                x + r + 4, y + height + 3, 0)
            triangle(x, y, x - r, y + height, x + 1, y + height, 3)
            triangle(x, y, x + 1, y + height, x + r, y + height, 1)
            line(x, y, x - r, y + height, 4, 0)
            line(x + 1, y + 3, x + 2, y + height - 2, 0, 0)
            if (snow) {
                triangle(x, y, x - 3, y + 6, x + 1, y + 7, 4)
                triangle(x, y, x + 1, y + 7, x + 3, y + 6, 2)
            }
        }
    }
}
