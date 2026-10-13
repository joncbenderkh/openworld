package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Hand-coded glyphs for every [Resource]: simple inked shapes with a dark outline, on a transparent
 * background. Pure Kotlin like [BiomeArt], so it can be painted and tested without a GL context.
 *
 * Shapes are written in a 32 x 32 design space (y down) and scaled to whatever size is asked for.
 * Keep them 3 units clear of the cell's edge: the outline needs room, and the atlas is filtered.
 */
internal object ResourceIconArt {
    private const val DESIGN = 32f
    private const val SAMPLES = 4
    private const val OUTLINE = 0xFF241A12.toInt()

    private class Layer(val color: Int, val contains: (Float, Float) -> Boolean)

    private class Glyph {
        val layers = ArrayList<Layer>()

        fun ellipse(color: Int, cx: Float, cy: Float, rx: Float, ry: Float) {
            layers += Layer(color) { x, y -> sq((x - cx) / rx) + sq((y - cy) / ry) <= 1f }
        }

        fun circle(color: Int, cx: Float, cy: Float, r: Float) = ellipse(color, cx, cy, r, r)

        fun poly(color: Int, vararg p: Float) {
            layers += Layer(color) { x, y -> inside(p, x, y) }
        }

        fun seg(color: Int, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
            layers += Layer(color) { x, y -> distanceToSegment(x, y, x1, y1, x2, y2) <= width / 2f }
        }

        fun rect(color: Int, x: Float, y: Float, w: Float, h: Float) {
            layers += Layer(color) { px, py -> px >= x && px <= x + w && py >= y && py <= y + h }
        }
    }

    private fun sq(v: Float) = v * v

    private fun inside(p: FloatArray, x: Float, y: Float): Boolean {
        var odd = false
        var j = p.size / 2 - 1
        for (i in 0 until p.size / 2) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]
            val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) odd = !odd
            j = i
        }
        return odd
    }

    private fun distanceToSegment(x: Float, y: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val t = (((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
    }

    /** Paints [resource]'s glyph into [out] as [size] x [size] RGBA8888 (packed 0xRRGGBBAA), transparent around it. */
    fun paint(resource: Resource, size: Int, out: IntArray) {
        require(size >= 16 && out.size >= size * size) { "bad icon buffer: size $size, ${out.size} ints" }
        val glyph = Glyph().also { draw(resource, it) }
        val scale = DESIGN / size

        // Coverage and premultiplied color of the fill, supersampled.
        val alpha = FloatArray(size * size)
        val red = FloatArray(size * size)
        val green = FloatArray(size * size)
        val blue = FloatArray(size * size)
        for (py in 0 until size) {
            for (px in 0 until size) {
                var hits = 0
                var r = 0f; var g = 0f; var b = 0f
                for (sy in 0 until SAMPLES) {
                    for (sx in 0 until SAMPLES) {
                        val x = (px + (sx + 0.5f) / SAMPLES) * scale
                        val y = (py + (sy + 0.5f) / SAMPLES) * scale
                        val layer = glyph.layers.lastOrNull { it.contains(x, y) } ?: continue
                        hits++
                        r += (layer.color ushr 16 and 0xFF); g += (layer.color ushr 8 and 0xFF); b += (layer.color and 0xFF)
                    }
                }
                val i = py * size + px
                alpha[i] = hits / (SAMPLES * SAMPLES).toFloat()
                if (hits > 0) { red[i] = r / hits; green[i] = g / hits; blue[i] = b / hits }
            }
        }

        // The outline is the fill's coverage grown by about a design unit and a half.
        val reach = (1.5f / scale).coerceAtLeast(1.2f)
        val span = reach.toInt() + 1
        val outlineRed = (OUTLINE ushr 16 and 0xFF).toFloat()
        val outlineGreen = (OUTLINE ushr 8 and 0xFF).toFloat()
        val outlineBlue = (OUTLINE and 0xFF).toFloat()
        for (py in 0 until size) {
            for (px in 0 until size) {
                var grown = 0f
                for (dy in -span..span) {
                    for (dx in -span..span) {
                        val x = px + dx
                        val y = py + dy
                        if (x < 0 || y < 0 || x >= size || y >= size) continue
                        if (hypot(dx.toFloat(), dy.toFloat()) <= reach) grown = max(grown, alpha[y * size + x])
                    }
                }
                val i = py * size + px
                val fill = alpha[i]
                val total = fill + (1f - fill) * grown
                // Fill over outline; a pixel with no coverage at all keeps the outline's color so that
                // filtering at the glyph's edge fades to dark instead of to a stray color.
                val r = if (total > 0f) (red[i] * fill + outlineRed * (1f - fill) * grown) / total else outlineRed
                val g = if (total > 0f) (green[i] * fill + outlineGreen * (1f - fill) * grown) / total else outlineGreen
                val b = if (total > 0f) (blue[i] * fill + outlineBlue * (1f - fill) * grown) / total else outlineBlue
                out[i] = (r.toInt().coerceIn(0, 255) shl 24) or (g.toInt().coerceIn(0, 255) shl 16) or
                    (b.toInt().coerceIn(0, 255) shl 8) or (total * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun draw(resource: Resource, g: Glyph) {
        when (resource) {
            Resource.FISH -> {
                g.poly(0x4FA3D9, 21f, 16f, 28f, 9f, 28f, 23f)
                g.ellipse(0x6CC0F0, 14f, 16f, 10f, 6.5f)
                g.circle(0x1B2A38, 9f, 14.5f, 1.3f)
                g.seg(0x3C86B8, 13f, 11f, 13f, 21f, 1.2f)
            }
            Resource.WHALES -> {
                g.ellipse(0x3A5F8A, 14f, 20f, 11f, 6f)
                g.poly(0x3A5F8A, 22f, 20f, 29f, 12f, 29f, 17f, 25f, 22f)
                g.ellipse(0xD8E6F0, 12f, 23f, 7f, 2.4f)
                g.seg(0x9ED8F2, 8f, 13f, 8f, 8f, 1.8f)
                g.seg(0x9ED8F2, 8f, 8f, 5f, 6f, 1.6f)
                g.seg(0x9ED8F2, 8f, 8f, 11f, 6f, 1.6f)
            }
            Resource.PEARLS -> {
                g.ellipse(0xB8A6C8, 16f, 21f, 11f, 6f)
                g.circle(0xF5F1EA, 16f, 14f, 6.5f)
                g.circle(0xFFFFFF, 14f, 12f, 2f)
            }
            Resource.WOOD -> {
                g.rect(0x8A5A2B, 8f, 11f, 19f, 10f)
                g.ellipse(0xD9B27A, 8f, 16f, 3.6f, 5f)
                g.ellipse(0xB88B52, 8f, 16f, 1.8f, 2.6f)
                g.seg(0x5E3C1B, 13f, 13f, 24f, 13f, 1f)
                g.seg(0x5E3C1B, 12f, 18f, 25f, 18f, 1f)
            }
            Resource.FURS -> {
                g.ellipse(0x9A6B3E, 16f, 16f, 11f, 7f)
                for (leg in floatArrayOf(7f, 12f, 20f, 25f)) g.ellipse(0x9A6B3E, leg, 9f, 2.2f, 2.8f)
                for (leg in floatArrayOf(7f, 12f, 20f, 25f)) g.ellipse(0x9A6B3E, leg, 23f, 2.2f, 2.8f)
                g.ellipse(0xC79A68, 16f, 16f, 6f, 3.4f)
            }
            Resource.GAME -> {
                g.ellipse(0x8B5E3C, 16f, 21f, 5.5f, 6f)
                g.circle(0xA0724A, 16f, 14f, 3.8f)
                g.seg(0xE0C9A0, 13f, 12f, 9f, 5f, 1.5f)
                g.seg(0xE0C9A0, 11f, 8f, 7f, 8f, 1.3f)
                g.seg(0xE0C9A0, 19f, 12f, 23f, 5f, 1.5f)
                g.seg(0xE0C9A0, 21f, 8f, 25f, 8f, 1.3f)
            }
            Resource.GRAIN -> {
                g.seg(0x9A7B2E, 16f, 28f, 16f, 8f, 1.6f)
                for (k in 0 until 3) {
                    val y = 10f + k * 6f
                    g.ellipse(0xE8C547, 12.5f, y + 1f, 2.2f, 3.4f)
                    g.ellipse(0xE8C547, 19.5f, y + 1f, 2.2f, 3.4f)
                }
                g.ellipse(0xE8C547, 16f, 6f, 2.2f, 3.4f)
            }
            Resource.GOLD -> {
                g.poly(0xE3B100, 5f, 24f, 9f, 13f, 23f, 13f, 27f, 24f)
                g.poly(0xFFD84A, 9f, 13f, 23f, 13f, 22f, 17f, 10f, 17f)
                g.seg(0xFFF1A0, 11f, 15f, 17f, 15f, 1.3f)
            }
            Resource.GEMS -> {
                g.poly(0x2FB8B0, 16f, 27f, 4f, 13f, 9f, 6f, 23f, 6f, 28f, 13f)
                g.poly(0x6FE3DC, 9f, 6f, 23f, 6f, 28f, 13f, 4f, 13f)
                g.poly(0xA4F4EF, 16f, 6f, 12f, 13f, 20f, 13f)
            }
            Resource.ORE -> {
                g.poly(0x7A7F86, 5f, 24f, 8f, 12f, 17f, 6f, 26f, 11f, 28f, 24f)
                g.poly(0x9AA0A8, 8f, 12f, 17f, 6f, 20f, 13f, 12f, 16f)
                g.circle(0xD9822B, 12f, 21f, 2.1f)
                g.circle(0xD9822B, 21f, 19f, 2.6f)
                g.circle(0xD9822B, 18f, 13f, 1.4f)
            }
            Resource.STONE -> {
                g.poly(0x8E8B84, 4f, 25f, 6f, 15f, 15f, 12f, 17f, 25f)
                g.poly(0xB0ADA5, 15f, 25f, 17f, 11f, 27f, 9f, 28f, 25f)
                g.seg(0x6E6B65, 10f, 16f, 10f, 24f, 0.9f)
            }
            Resource.OIL -> {
                g.poly(0x1E1E26, 16f, 4f, 6f, 20f, 26f, 20f)
                g.circle(0x1E1E26, 16f, 20f, 9.5f)
                g.ellipse(0x6D6D86, 12.5f, 20f, 2f, 3.6f)
            }
            Resource.SPICES -> {
                g.poly(0xC8431F, 6f, 22f, 12f, 11f, 22f, 8f, 26f, 12f, 17f, 15f, 12f, 25f)
                g.seg(0x4F8A2F, 22f, 8f, 25f, 4f, 2f)
                g.seg(0xE86A3A, 12f, 22f, 18f, 13f, 1f)
            }
            Resource.EXOTIC_FRUIT -> {
                g.circle(0xF28C28, 15f, 18f, 8.5f)
                g.circle(0xFFB45C, 12.5f, 15.5f, 2.6f)
                g.seg(0x6A4A2A, 15f, 10f, 17f, 5f, 1.4f)
                g.poly(0x4F9A3A, 17f, 6f, 25f, 5f, 21f, 11f)
            }
            Resource.PEAT -> {
                g.rect(0x4A3426, 5f, 7f, 22f, 7f)
                g.rect(0x5B4130, 5f, 16f, 22f, 8f)
                g.seg(0x2E2018, 11f, 10f, 22f, 10f, 0.9f)
                g.seg(0x2E2018, 9f, 20f, 20f, 20f, 0.9f)
            }
            Resource.ICE_CRYSTALS -> {
                for (a in 0 until 3) {
                    val angle = Math.PI.toFloat() * a / 3f
                    val dx = kotlin.math.cos(angle) * 11f
                    val dy = kotlin.math.sin(angle) * 11f
                    g.seg(0xBFE6F8, 16f - dx, 16f - dy, 16f + dx, 16f + dy, 2.4f)
                }
                g.circle(0xEAF8FF, 16f, 16f, 3.2f)
            }
            Resource.OBSIDIAN -> {
                g.poly(0x22202E, 16f, 3f, 24f, 18f, 18f, 28f, 9f, 24f, 8f, 12f)
                g.poly(0x4A4468, 16f, 3f, 24f, 18f, 17f, 14f)
                g.seg(0x8C84B8, 14f, 9f, 11f, 18f, 1f)
            }
            Resource.SULFUR -> {
                g.poly(0xD8C61E, 6f, 26f, 9f, 14f, 14f, 26f)
                g.poly(0xEEDC3A, 12f, 26f, 17f, 8f, 22f, 26f)
                g.poly(0xD8C61E, 20f, 26f, 24f, 15f, 27f, 26f)
                g.seg(0xFFF28A, 17f, 12f, 17f, 22f, 1f)
            }
        }
    }

    /** True when [color]'s alpha says it is painted (for tests). */
    fun isPainted(pixel: Int): Boolean = abs(pixel and 0xFF) > 8
}
