package dev.joncbender.openworld.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeodesicSphereTest {

    @Test
    fun `frequency 1 is a dodecahedron - twelve pentagons, nothing else`() {
        val faces = GeodesicSphere.generate(1)
        assertEquals(12, faces.size)
        assertTrue(faces.all { it.corners.size == 5 })
    }

    @Test
    fun `higher frequency yields exactly twelve pentagons and the rest hexagons`() {
        val freq = 4
        val faces = GeodesicSphere.generate(freq)

        assertEquals(10 * freq * freq + 2, faces.size)
        assertEquals(12, faces.count { it.corners.size == 5 })
        assertTrue(faces.filter { it.corners.size != 5 }.all { it.corners.size == 6 })
    }

    @Test
    fun `every face's neighbor count matches its corner count`() {
        val faces = GeodesicSphere.generate(3)
        for (face in faces) {
            assertEquals(face.corners.size, face.neighbors.size)
        }
    }

    @Test
    fun `neighbor relationships are symmetric`() {
        val faces = GeodesicSphere.generate(3)
        for ((i, face) in faces.withIndex()) {
            for (n in face.neighbors) {
                assertTrue(i in faces[n].neighbors, "face $n does not list $i back as a neighbor")
            }
        }
    }

    @Test
    fun `all points lie on the unit sphere`() {
        val faces = GeodesicSphere.generate(2)
        for (face in faces) {
            assertTrue(abs(face.center.len() - 1f) < 1e-4f)
            for (corner in face.corners) {
                assertTrue(abs(corner.len() - 1f) < 1e-4f)
            }
        }
    }

    @Test
    fun `holds up at the frequency the game actually uses`() {
        // Regression test: at freq=40, boundary points shared by adjacent icosahedron
        // faces were occasionally computed with a ~1 ULP float difference between the
        // two faces, landing on opposite sides of the vertex-merge quantization
        // boundary. That left a stray unmerged vertex, which made an edge belong to
        // only one triangle instead of two and crashed buildDual() with
        // NoSuchElementException when walking the dual polygon around it.
        val freq = 40
        val faces = GeodesicSphere.generate(freq)

        assertEquals(10 * freq * freq + 2, faces.size)
        assertEquals(12, faces.count { it.corners.size == 5 })
        for ((i, face) in faces.withIndex()) {
            assertEquals(face.corners.size, face.neighbors.size)
            for (n in face.neighbors) {
                assertTrue(i in faces[n].neighbors, "face $n does not list $i back as a neighbor")
            }
        }
    }
}
