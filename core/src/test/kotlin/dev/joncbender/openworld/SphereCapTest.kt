package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SphereCapTest {

    private val cap = SphereCap(0f, 0f, 1f, chordRadius = 0f) // a single point at +Z

    @Test
    fun `a cap facing the camera is visible and one facing away is hidden`() {
        assertFalse(cap.isBeyondHorizon(0f, 0f, 1f, cameraDistance = 3f))
        assertTrue(cap.isBeyondHorizon(0f, 0f, -1f, cameraDistance = 3f))
    }

    @Test
    fun `the horizon is at acos of one over the distance`() {
        val distance = 3f
        val horizon = acos(1f / distance) // ~70.5 degrees
        fun cameraAt(angle: Float) = Triple(sin(angle), 0f, cos(angle))

        val (x1, y1, z1) = cameraAt(horizon - 0.02f)
        assertFalse(cap.isBeyondHorizon(x1, y1, z1, distance), "just inside the horizon is visible")
        val (x2, y2, z2) = cameraAt(horizon + 0.02f)
        assertTrue(cap.isBeyondHorizon(x2, y2, z2, distance), "just past the horizon is hidden")
    }

    @Test
    fun `a wider cap stays visible further past the horizon`() {
        val wide = SphereCap(0f, 0f, 1f, chordRadius = 1f) // half-angle 60 degrees
        val distance = 3f
        val angle = acos(1f / distance) + 0.5f // well past the horizon for a point
        assertTrue(cap.isBeyondHorizon(sin(angle), 0f, cos(angle), distance))
        assertFalse(wide.isBeyondHorizon(sin(angle), 0f, cos(angle), distance))
    }

    @Test
    fun `a camera at or inside the surface never culls`() {
        assertFalse(cap.isBeyondHorizon(0f, 0f, -1f, cameraDistance = 1f))
        assertFalse(cap.isBeyondHorizon(0f, 0f, -1f, cameraDistance = 0.5f))
    }

    @Test
    fun `an enclosing cap contains every face center and corner of its range`() {
        val sphere = GeodesicSphere.generate(8)
        val range = 100 until 400
        val cap = SphereCap.enclosing(sphere, range.first, range.last + 1)
        fun distanceToAxis(x: Float, y: Float, z: Float) =
            kotlin.math.sqrt((x - cap.ax) * (x - cap.ax) + (y - cap.ay) * (y - cap.ay) + (z - cap.az) * (z - cap.az))
        for (i in range) {
            assertTrue(distanceToAxis(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i)) <= cap.chordRadius + 1e-5f)
            for (c in 0 until sphere.cornerCount(i)) {
                assertTrue(distanceToAxis(sphere.cornerX(i, c), sphere.cornerY(i, c), sphere.cornerZ(i, c)) <= cap.chordRadius + 1e-5f)
            }
        }
        assertEquals(1f, kotlin.math.sqrt(cap.ax * cap.ax + cap.ay * cap.ay + cap.az * cap.az), 1e-5f)
    }

    @Test
    fun `culling by cap never hides a face that is actually visible`() {
        // Exhaustive check against the exact per-point visibility rule (a point p
        // on the unit sphere is visible iff p . cameraDirection > 1 / distance).
        val sphere = GeodesicSphere.generate(10)
        val meshSize = 150
        val caps = (0 until sphere.faceCount step meshSize).map { from ->
            from to SphereCap.enclosing(sphere, from, minOf(from + meshSize, sphere.faceCount))
        }
        val distance = 2f
        val directions = listOf(
            Triple(0f, 0f, 1f), Triple(0f, 0f, -1f), Triple(1f, 0f, 0f), Triple(0f, 1f, 0f),
            Triple(0.577f, 0.577f, 0.577f), Triple(-0.6f, 0.0f, 0.8f),
        )
        for ((cx, cy, cz) in directions) {
            for ((from, cap) in caps) {
                if (!cap.isBeyondHorizon(cx, cy, cz, distance)) continue
                for (i in from until minOf(from + meshSize, sphere.faceCount)) {
                    for (c in 0 until sphere.cornerCount(i)) {
                        val dot = sphere.cornerX(i, c) * cx + sphere.cornerY(i, c) * cy + sphere.cornerZ(i, c) * cz
                        assertTrue(dot <= 1f / distance, "culled mesh at $from has a visible corner (dot=$dot) for camera ($cx, $cy, $cz)")
                    }
                }
            }
        }
    }
}
