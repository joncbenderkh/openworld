package dev.joncbender.openworld

import dev.joncbender.openworld.geo.Sphere
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * A bounding cap on the unit sphere: every point of some piece of the globe
 * lies within [chordRadius] (straight-line distance) of the unit vector
 * ([ax], [ay], [az]). Lets the renderer skip a whole mesh without looking at
 * its triangles - at ~396k tiles, roughly half the globe is always on the
 * far side, and drawing it costs as much as drawing the visible half.
 *
 * Everything here is in the globe's own frame (before its rotation is applied).
 */
class SphereCap(val ax: Float, val ay: Float, val az: Float, val chordRadius: Float) {

    /** Angle, seen from the sphere's center, between the axis and the cap's edge. */
    val halfAngle: Float = 2f * asin((chordRadius / 2f).coerceAtMost(1f))

    /**
     * True if every point of the cap is hidden behind the globe from a camera
     * [cameraDistance] from the center, in the unit direction ([cx], [cy], [cz]).
     * A point on the unit sphere is visible exactly when its angle from the
     * camera direction is under acos(1 / distance) - the horizon - so the cap is
     * hidden when its axis is further than that plus the cap's own half-angle.
     * Conservative: never reports a visible point as hidden.
     */
    fun isBeyondHorizon(cx: Float, cy: Float, cz: Float, cameraDistance: Float): Boolean {
        if (cameraDistance <= 1f) return false // camera at or inside the surface: don't cull
        val angleToAxis = acos((ax * cx + ay * cy + az * cz).coerceIn(-1f, 1f))
        return angleToAxis > halfAngle + acos(1f / cameraDistance) + MARGIN_RADIANS
    }

    companion object {
        // Absorbs float rounding in the angle math, so a cap sitting exactly on
        // the horizon is drawn rather than flickering in and out.
        private const val MARGIN_RADIANS = 1e-3f

        /** A cap around the mean direction of faces [fromFace] until [toFace], large enough to contain all their centers and corners. */
        fun enclosing(sphere: Sphere, fromFace: Int, toFace: Int): SphereCap =
            enclosingOf(sphere, toFace - fromFace) { fromFace + it }

        /** Like the range version, for the faces `faces[from until to]` - a patch need not be a run of consecutive faces. */
        fun enclosing(sphere: Sphere, faces: IntArray, from: Int, to: Int): SphereCap =
            enclosingOf(sphere, to - from) { faces[from + it] }

        private inline fun enclosingOf(sphere: Sphere, count: Int, faceAt: (Int) -> Int): SphereCap {
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (k in 0 until count) {
                val i = faceAt(k)
                sx += sphere.centerX(i)
                sy += sphere.centerY(i)
                sz += sphere.centerZ(i)
            }
            val length = sqrt(sx * sx + sy * sy + sz * sz)
            // A piece of the globe spanning more than a hemisphere can average out to
            // nothing; fall back to an axis and let the radius cover everything.
            val ax = if (length > 1e-6f) sx / length else 0f
            val ay = if (length > 1e-6f) sy / length else 0f
            val az = if (length > 1e-6f) sz / length else 1f

            var maxSquared = 0f
            for (k in 0 until count) {
                val i = faceAt(k)
                maxSquared = maxOf(maxSquared, squaredDistance(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i), ax, ay, az))
                for (c in 0 until sphere.cornerCount(i)) {
                    maxSquared = maxOf(maxSquared, squaredDistance(sphere.cornerX(i, c), sphere.cornerY(i, c), sphere.cornerZ(i, c), ax, ay, az))
                }
            }
            return SphereCap(ax, ay, az, sqrt(maxSquared))
        }

        private fun squaredDistance(x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float): Float {
            val dx = x - ax
            val dy = y - ay
            val dz = z - az
            return dx * dx + dy * dy + dz * dz
        }
    }
}
