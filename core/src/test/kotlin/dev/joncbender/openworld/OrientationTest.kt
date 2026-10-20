package dev.joncbender.openworld

import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals

class OrientationTest {
    private fun faced(rotation: Quaternion, point: Vector3): Vector3 {
        val turned = Orientation.facing(rotation, point.x, point.y, point.z)
        return turned.transform(Vector3(point)).nor()
    }

    private fun assertFacing(v: Vector3) {
        assertEquals(0f, v.x, 1e-4f)
        assertEquals(0f, v.y, 1e-4f)
        assertEquals(1f, v.z, 1e-4f)
    }

    @Test
    fun `any point is turned to face the camera from any starting orientation`() {
        val rotations = listOf(Quaternion(), Quaternion(Vector3.Y, 90f), Quaternion(Vector3.X, 137f).mulLeft(Quaternion(Vector3.Z, 33f)))
        val points = listOf(Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f), Vector3(0f, -1f, 0f), Vector3(0.3f, 0.5f, -0.8f), Vector3(0f, 0f, -1f), Vector3(0.014f, 0f, -1f).nor(), Vector3(0f, 0.002f, -1f).nor())
        for (r in rotations) for (p in points) assertFacing(faced(r, p.cpy().nor()))
    }

    @Test
    fun `a point already facing leaves the orientation alone`() {
        val r = Quaternion(Vector3.Z, 40f)
        val turned = Orientation.facing(r, 0f, 0f, 1f)
        assertEquals(1f, kotlin.math.abs(turned.dot(r)), 1e-4f)
    }

    @Test
    fun `the given rotation is not modified`() {
        val r = Quaternion(Vector3.Y, 90f)
        val before = Quaternion(r)
        Orientation.facing(r, 1f, 0f, 0f)
        assertEquals(before, r)
    }
}
