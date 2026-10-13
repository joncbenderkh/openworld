package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraRigTest {

    @Test
    fun `zooming scales the height above the surface, not the distance`() {
        assertEquals(1f + 2f * 0.5f, CameraRig.zoomed(3f, 0.5f), 1e-6f)
        assertEquals(1f + 0.1f * 1.1f, CameraRig.zoomed(1.1f, 1.1f), 1e-6f)
    }

    @Test
    fun `zooming stops at the limits from either side`() {
        assertEquals(CameraRig.MIN_DISTANCE, CameraRig.zoomed(1.5f, 1e-6f))
        assertEquals(CameraRig.MAX_DISTANCE, CameraRig.zoomed(5f, 100f))
        var distance = 3f
        repeat(500) { distance = CameraRig.zoomed(distance, 0.9f) }
        assertEquals(CameraRig.MIN_DISTANCE, distance)
    }

    @Test
    fun `the near plane never reaches the surface or the lines just above it`() {
        var distance = CameraRig.MIN_DISTANCE
        while (distance <= CameraRig.MAX_DISTANCE) {
            assertTrue(CameraRig.nearPlane(distance) < distance - 1.004f, "distance $distance")
            assertTrue(CameraRig.farPlane(distance) > distance + 1f, "distance $distance")
            distance += 0.01f
        }
    }

    @Test
    fun `dragging slows down near the surface and is full speed from far out`() {
        assertEquals(1f, CameraRig.dragScale(3f))
        assertEquals(1f, CameraRig.dragScale(CameraRig.MAX_DISTANCE))
        assertTrue(CameraRig.dragScale(CameraRig.MIN_DISTANCE) < 0.05f)
        assertTrue(CameraRig.dragScale(1.5f) < CameraRig.dragScale(2f))
    }
}
