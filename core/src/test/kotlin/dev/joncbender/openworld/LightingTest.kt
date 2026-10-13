package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightingTest {
    private val lx = Lighting.LIGHT_X
    private val ly = Lighting.LIGHT_Y
    private val lz = Lighting.LIGHT_Z

    @Test
    fun `the light direction is a unit vector`() {
        assertEquals(1f, lx * lx + ly * ly + lz * lz, 1e-5f)
    }

    @Test
    fun `facing the light is full brightness and facing away keeps the ambient floor`() {
        assertEquals(1f, Lighting.shade(lx, ly, lz, lx, ly, lz), 1e-5f)
        assertEquals(Lighting.AMBIENT, Lighting.shade(-lx, -ly, -lz, lx, ly, lz), 1e-6f)
    }

    @Test
    fun `brightness falls as the surface turns away from the light`() {
        val norm = kotlin.math.sqrt(lx * lx + ly * ly)
        val px = ly / norm
        val py = -lx / norm
        var previous = 2f
        for (step in 0..8) {
            val angle = Math.PI.toFloat() * step / 8
            val c = kotlin.math.cos(angle)
            val s = kotlin.math.sin(angle)
            val shade = Lighting.shade(c * lx + s * px, c * ly + s * py, c * lz, lx, ly, lz)
            assertTrue(shade <= previous + 1e-6f)
            assertTrue(shade in Lighting.AMBIENT - 1e-6f..1f + 1e-6f)
            previous = shade
        }
    }
}
