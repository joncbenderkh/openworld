package dev.joncbender.openworld.geo

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeodesicSphereTest {

    private fun length(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    @Test
    fun `frequency 1 is a dodecahedron - twelve pentagons, nothing else`() {
        val sphere = GeodesicSphere.generate(1)
        assertEquals(12, sphere.faceCount)
        assertTrue(sphere.indices.all { sphere.cornerCount(it) == 5 })
    }

    @Test
    fun `higher frequency yields exactly twelve pentagons and the rest hexagons`() {
        val freq = 4
        val sphere = GeodesicSphere.generate(freq)

        assertEquals(10 * freq * freq + 2, sphere.faceCount)
        assertEquals(12, sphere.indices.count { sphere.cornerCount(it) == 5 })
        assertTrue(sphere.indices.filter { sphere.cornerCount(it) != 5 }.all { sphere.cornerCount(it) == 6 })
    }

    @Test
    fun `corner slots are laid out contiguously per face`() {
        val sphere = GeodesicSphere.generate(3)
        assertEquals(0, sphere.cornerStart[0])
        assertEquals(sphere.cornerTotal, sphere.cornerStart[sphere.faceCount])
        assertEquals(sphere.cornerTotal, sphere.neighbors.size)
        // Each vertex is shared by exactly three faces (it's a triangle centroid).
        assertEquals(sphere.cornerTotal, sphere.vertexCount * 3)
    }

    @Test
    fun `neighbor relationships are symmetric`() {
        val sphere = GeodesicSphere.generate(3)
        for (i in sphere.indices) {
            sphere.forEachNeighbor(i) { n ->
                assertTrue(sphere.anyNeighbor(n) { it == i }, "face $n does not list $i back as a neighbor")
            }
        }
    }

    @Test
    fun `all points lie on the unit sphere`() {
        val sphere = GeodesicSphere.generate(2)
        for (i in sphere.indices) {
            assertTrue(abs(length(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i)) - 1f) < 1e-4f)
            for (c in 0 until sphere.cornerCount(i)) {
                assertTrue(abs(length(sphere.cornerX(i, c), sphere.cornerY(i, c), sphere.cornerZ(i, c)) - 1f) < 1e-4f)
            }
        }
    }

    @Test
    fun `min neighbor matches minByOrNull, first smallest on ties`() {
        val sphere = GeodesicSphere.generate(2)
        val values = FloatArray(sphere.faceCount) { (it % 7).toFloat() }
        for (i in sphere.indices) {
            val expected = (0 until sphere.cornerCount(i)).map { sphere.neighbor(i, it) }.minByOrNull { values[it] }
            assertEquals(expected, sphere.minNeighborBy(i) { values[it] })
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
        val sphere = GeodesicSphere.generate(freq)

        assertEquals(10 * freq * freq + 2, sphere.faceCount)
        assertEquals(12, sphere.indices.count { sphere.cornerCount(it) == 5 })
        for (i in sphere.indices) {
            sphere.forEachNeighbor(i) { n ->
                assertTrue(sphere.anyNeighbor(n) { it == i }, "face $n does not list $i back as a neighbor")
            }
        }
    }

    @Test
    fun `subdivision yields exact vertex and triangle counts beyond the old quantization limit`() {
        // Regression test: vertices used to be deduplicated by quantizing
        // coordinates to 1e-3, which produced 7 duplicate vertices at
        // frequency 400 (and wrongly merged distinct ones by 700). Shared
        // vertices are now identified by integer edge position, so the
        // counts must be exact at any frequency.
        val freq = 400
        val (vertices, triangles) = GeodesicSphere.subdivideIcosahedron(freq)
        assertEquals(10 * freq * freq + 2, vertices.size)
        assertEquals(20 * freq * freq, triangles.size)
    }
}
