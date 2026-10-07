package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainLayerTest {

    // Frequency 40 has 16,002 faces: two patches (8,571 + 7,431).
    private val sphere = GeodesicSphere.generate(40)
    private val layer = TerrainLayer(SphereWorld(sphere, ByteArray(sphere.faceCount)))

    @Test
    fun `patches tile every face exactly once, in order`() {
        assertEquals(2, layer.patchCount)
        assertEquals(0, layer.firstFace(0))
        for (patch in 0 until layer.patchCount) {
            if (patch > 0) assertEquals(layer.endFace(patch - 1), layer.firstFace(patch))
            assertTrue(layer.endFace(patch) > layer.firstFace(patch), "patch $patch is empty")
            assertTrue(layer.endFace(patch) - layer.firstFace(patch) <= TerrainLayer.FACES_PER_PATCH)
        }
        assertEquals(sphere.faceCount, layer.endFace(layer.patchCount - 1))
    }

    @Test
    fun `no patch can exceed what a 16-bit index addresses`() {
        for (patch in 0 until layer.patchCount) {
            var vertices = 0
            for (i in layer.firstFace(patch) until layer.endFace(patch)) vertices += 1 + sphere.cornerCount(i)
            assertTrue(vertices <= TerrainLayer.MAX_VERTICES_PER_PATCH, "patch $patch has $vertices vertices")
        }
        assertTrue(TerrainLayer.MAX_VERTICES_PER_PATCH < 65536)
    }

    @Test
    fun `each patch's cap contains all of its face centers and corners`() {
        for (patch in 0 until layer.patchCount) {
            val cap = layer.cap(patch)
            fun distance(x: Float, y: Float, z: Float) =
                sqrt((x - cap.ax) * (x - cap.ax) + (y - cap.ay) * (y - cap.ay) + (z - cap.az) * (z - cap.az))
            for (i in layer.firstFace(patch) until layer.endFace(patch)) {
                assertTrue(distance(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i)) <= cap.chordRadius + 1e-5f)
                for (c in 0 until sphere.cornerCount(i)) {
                    assertTrue(distance(sphere.cornerX(i, c), sphere.cornerY(i, c), sphere.cornerZ(i, c)) <= cap.chordRadius + 1e-5f)
                }
            }
        }
    }

    @Test
    fun `a layer starts with no meshes built`() {
        for (patch in 0 until layer.patchCount) assertNull(layer.meshOrNull(patch))
    }

    @Test
    fun `a tiny world is a single patch`() {
        val small = GeodesicSphere.generate(2)
        val smallLayer = TerrainLayer(SphereWorld(small, ByteArray(small.faceCount)))
        assertEquals(1, smallLayer.patchCount)
        assertEquals(small.faceCount, smallLayer.endFace(0))
    }
}
