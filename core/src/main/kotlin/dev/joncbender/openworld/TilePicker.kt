package dev.joncbender.openworld

import dev.joncbender.openworld.geo.Sphere

/**
 * Finds the tile of a [CompactWorld] under a point on the unit sphere. Nearest-center would favor
 * the small fine tiles next to a big ocean tile, so the point's coarse cell is looked up first: an
 * ocean tile owns its whole cell, and anywhere else the nearest fine tile is the answer.
 *
 * [coarse] must be the sphere the world was compacted with (see [WorldCompactor.COARSE_FREQUENCY]).
 * A scan per tap is fine: this runs once per tap, not per frame.
 */
class TilePicker(val compact: CompactWorld, private val coarse: Sphere) {

    fun pick(x: Float, y: Float, z: Float): Int {
        val cell = nearestCenter(coarse, coarse.faceCount, x, y, z)
        val ocean = compact.coarseFace.binarySearch(cell)
        if (ocean >= 0) return compact.fineCount + ocean
        return nearestCenter(compact.world.sphere, compact.fineCount, x, y, z)
    }

    private fun nearestCenter(sphere: Sphere, count: Int, x: Float, y: Float, z: Float): Int {
        var best = 0
        var bestDot = -2f
        for (i in 0 until count) {
            val dot = sphere.centerX(i) * x + sphere.centerY(i) * y + sphere.centerZ(i) * z
            if (dot > bestDot) {
                bestDot = dot
                best = i
            }
        }
        return best
    }
}
