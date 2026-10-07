package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchLayoutTest {

    private val sphere = GeodesicSphere.generate(60)
    private val layout = PatchLayout.build(sphere, TerrainLayer.FACES_PER_PATCH)

    @Test
    fun `every face belongs to exactly one patch`() {
        val seen = IntArray(sphere.faceCount)
        for (patch in 0 until layout.patchCount) {
            for (k in layout.start(patch) until layout.end(patch)) seen[layout.faceIndices[k]]++
        }
        assertTrue(seen.all { it == 1 }, "faces counted other than once: ${seen.count { it != 1 }}")
        assertEquals(sphere.faceCount, layout.end(layout.patchCount - 1))
    }

    @Test
    fun `patches are contiguous, non-empty and list their faces in ascending order`() {
        assertEquals(0, layout.start(0))
        for (patch in 0 until layout.patchCount) {
            if (patch > 0) assertEquals(layout.end(patch - 1), layout.start(patch))
            assertTrue(layout.faceCount(patch) > 0, "patch $patch is empty")
            for (k in layout.start(patch) + 1 until layout.end(patch)) {
                assertTrue(layout.faceIndices[k] > layout.faceIndices[k - 1], "patch $patch not ascending")
            }
        }
    }

    @Test
    fun `no patch exceeds the face limit, and the grid refines itself to meet a tight one`() {
        for (patch in 0 until layout.patchCount) assertTrue(layout.faceCount(patch) <= TerrainLayer.FACES_PER_PATCH)

        val tiny = GeodesicSphere.generate(20) // 4,002 faces
        val tight = PatchLayout.build(tiny, maxFacesPerPatch = 60)
        for (patch in 0 until tight.patchCount) assertTrue(tight.faceCount(patch) <= 60, "patch $patch has ${tight.faceCount(patch)}")
        assertTrue(tight.patchCount > 4002 / 60, "must be at least enough patches to hold every face")
    }

    @Test
    fun `no patch can exceed what a 16-bit index addresses`() {
        for (patch in 0 until layout.patchCount) {
            var vertices = 0
            for (k in layout.start(patch) until layout.end(patch)) vertices += 1 + sphere.cornerCount(layout.faceIndices[k])
            assertTrue(vertices <= TerrainLayer.MAX_VERTICES_PER_PATCH, "patch $patch has $vertices vertices")
        }
        assertTrue(TerrainLayer.MAX_VERTICES_PER_PATCH < 65536)
    }

    @Test
    fun `patches are far more compact than runs of consecutive faces`() {
        // A run of consecutive face indices is a thin strip across a whole icosahedron
        // triangle; compare against such strips of the same average size.
        val avg = sphere.faceCount / layout.patchCount
        var layoutMax = 0f
        for (patch in 0 until layout.patchCount) {
            layoutMax = maxOf(layoutMax, SphereCap.enclosing(sphere, layout.faceIndices, layout.start(patch), layout.end(patch)).chordRadius)
        }
        var stripSum = 0f
        var strips = 0
        var from = 0
        while (from < sphere.faceCount) {
            stripSum += SphereCap.enclosing(sphere, from, minOf(sphere.faceCount, from + avg)).chordRadius
            strips++
            from += avg
        }
        assertTrue(layoutMax < stripSum / strips, "the worst patch ($layoutMax) should still beat the average strip (${stripSum / strips})")
    }

    @Test
    fun `the layout is deterministic`() {
        val again = PatchLayout.build(sphere, TerrainLayer.FACES_PER_PATCH)
        assertEquals(layout.patchCount, again.patchCount)
        assertTrue(layout.faceIndices.contentEquals(again.faceIndices))
    }

    @Test
    fun `a cap over a face list matches the cap over the same consecutive range`() {
        val identity = IntArray(sphere.faceCount) { it }
        val byRange = SphereCap.enclosing(sphere, 100, 700)
        val byList = SphereCap.enclosing(sphere, identity, 100, 700)
        assertEquals(byRange.ax, byList.ax)
        assertEquals(byRange.chordRadius, byList.chordRadius)
    }
}
