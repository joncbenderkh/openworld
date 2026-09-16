package dev.joncbender.openworld

import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals

class CompassTest {

    private fun assertAngle(expectedDeg: Float, actualDeg: Float, toleranceDeg: Float = 0.01f) {
        var diff = (actualDeg - expectedDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        assertEquals(0f, diff, toleranceDeg, "expected $expectedDeg but was $actualDeg")
    }

    @Test
    fun `no rotation points the compass straight up`() {
        val azimuth = Compass.nextAzimuthDeg(Quaternion(), previousAzimuthDeg = 999f)
        assertAngle(0f, azimuth)
    }

    @Test
    fun `twisting the globe 90 degrees around the view axis rotates the compass 90 degrees`() {
        val rotation = Quaternion(Vector3.Z, 90f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 0f)
        assertAngle(270f, azimuth)
    }

    @Test
    fun `twisting the other way rotates the compass the other way`() {
        val rotation = Quaternion(Vector3.Z, -90f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 0f)
        assertAngle(90f, azimuth)
    }

    @Test
    fun `a full 180 degree twist points the compass straight down`() {
        val rotation = Quaternion(Vector3.Z, 180f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 0f)
        assertAngle(180f, azimuth)
    }

    @Test
    fun `rolling the globe so the pole faces the camera holds the last stable angle`() {
        // Rotating the local north pole (0,1,0) onto the view axis (0,0,1) via
        // a 90-degree rotation around X leaves nothing for the compass to
        // read - the angle should freeze rather than swing arbitrarily.
        val rotation = Quaternion(Vector3.X, 90f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 123f)
        assertEquals(123f, azimuth)
    }

    @Test
    fun `pole facing directly away from the camera also holds the last stable angle`() {
        val rotation = Quaternion(Vector3.X, -90f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 45f)
        assertEquals(45f, azimuth)
    }

    @Test
    fun `panning left-right around the pole's own axis leaves the compass unchanged`() {
        // The north pole sits exactly on the world Y axis at identity
        // rotation, so rotating around that same axis is a no-op for it -
        // matches how the graticule's pole marker doesn't move for a purely
        // horizontal drag either.
        val rotation = Quaternion(Vector3.Y, 40f)
        val azimuth = Compass.nextAzimuthDeg(rotation, previousAzimuthDeg = 0f)
        assertAngle(0f, azimuth)
    }
}
