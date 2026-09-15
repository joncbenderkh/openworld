package dev.joncbender.openworld

import kotlin.math.floor

/**
 * Value noise that tiles exactly along x, so it can be sampled across a
 * hex-column range that wraps like a globe's east-west axis.
 *
 * Sampling normalizes x to a [0, cyclesX) range derived from a fraction of
 * the map width, so `sample(0, y)` and `sample(width, y)` land on the same
 * lattice phase by construction - no seam-stitching needed. Y is not
 * wrapped: the poles are edges, not a wraparound, like a Mercator-ish map.
 */
class TileableNoise(private val seed: Long) {

    private fun hash(x: Int, y: Int): Float {
        var h = seed
        h = h * 6364136223846793005L + x * 1442695040888963407L
        h = h * 6364136223846793005L + y * 1442695040888963407L
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        return ((h and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat())
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /** Single octave of tileable value noise. [cyclesX] must be a positive integer. */
    private fun latticeNoise(nx: Float, ny: Int, cyclesX: Int): Float {
        val x0 = floor(nx).toInt()
        val x1 = (x0 + 1) % cyclesX
        val xw = ((x0 % cyclesX) + cyclesX) % cyclesX
        val tx = smooth(nx - floor(nx))

        val v0 = hash(xw, ny)
        val v1 = hash(x1, ny)

        return v0 * (1 - tx) + v1 * tx
    }

    private fun sampleOctave(fracX: Float, y: Float, cyclesX: Int): Float {
        val nx = fracX * cyclesX
        val y0 = floor(y).toInt()
        val ty = smooth(y - floor(y))
        val top = latticeNoise(nx, y0, cyclesX)
        val bottom = latticeNoise(nx, y0 + 1, cyclesX)
        return top * (1 - ty) + bottom * ty
    }

    /**
     * Fractal Brownian motion, tileable in x.
     *
     * @param fracX column position expressed as a fraction of map width, in [0, 1).
     * @param y row position in hex rows (not normalized - poles don't wrap).
     * @param baseCyclesX number of noise cells spanning the full map width at octave 0.
     */
    fun fbm(fracX: Float, y: Float, baseCyclesX: Int, octaves: Int = 4, persistence: Float = 0.5f): Float {
        var total = 0f
        var amplitude = 1f
        var maxAmplitude = 0f
        var frequency = 1
        var yFreq = 1f / (baseCyclesX.toFloat())
        for (o in 0 until octaves) {
            total += sampleOctave(fracX, y * yFreq * frequency, baseCyclesX * frequency) * amplitude
            maxAmplitude += amplitude
            amplitude *= persistence
            frequency *= 2
        }
        return total / maxAmplitude
    }
}
