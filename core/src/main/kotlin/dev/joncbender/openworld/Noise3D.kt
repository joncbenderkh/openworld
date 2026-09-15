package dev.joncbender.openworld

import kotlin.math.floor

/**
 * Hash-based 3D value noise. Sampling directly in 3D space (rather than on a
 * flat grid that has to be stitched at its edges) has no seams to begin with
 * - that's the whole point of generating the world on a sphere.
 */
class Noise3D(private val seed: Long) {

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = seed
        h = h * 6364136223846793005L + x * 1442695040888963407L
        h = h * 6364136223846793005L + y * 1442695040888963407L
        h = h * 6364136223846793005L + z * 1442695040888963407L
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        return (h and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun lattice(x: Float, y: Float, z: Float): Float {
        val x0 = floor(x).toInt(); val tx = smooth(x - floor(x))
        val y0 = floor(y).toInt(); val ty = smooth(y - floor(y))
        val z0 = floor(z).toInt(); val tz = smooth(z - floor(z))

        fun corner(dx: Int, dy: Int, dz: Int) = hash(x0 + dx, y0 + dy, z0 + dz)

        val x00 = corner(0, 0, 0) * (1 - tx) + corner(1, 0, 0) * tx
        val x10 = corner(0, 1, 0) * (1 - tx) + corner(1, 1, 0) * tx
        val x01 = corner(0, 0, 1) * (1 - tx) + corner(1, 0, 1) * tx
        val x11 = corner(0, 1, 1) * (1 - tx) + corner(1, 1, 1) * tx

        val y0i = x00 * (1 - ty) + x10 * ty
        val y1i = x01 * (1 - ty) + x11 * ty

        return y0i * (1 - tz) + y1i * tz
    }

    fun fbm(x: Float, y: Float, z: Float, octaves: Int = 4, persistence: Float = 0.5f): Float {
        var total = 0f
        var amplitude = 1f
        var maxAmplitude = 0f
        var frequency = 1f
        for (o in 0 until octaves) {
            total += lattice(x * frequency, y * frequency, z * frequency) * amplitude
            maxAmplitude += amplitude
            amplitude *= persistence
            frequency *= 2f
        }
        return total / maxAmplitude
    }
}
