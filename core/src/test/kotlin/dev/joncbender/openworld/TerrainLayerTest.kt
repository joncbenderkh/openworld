package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainLayerTest {

    private val sphere = GeodesicSphere.generate(40)
    private fun layer() = TerrainLayer(SphereWorld(sphere, ByteArray(sphere.faceCount)))

    @Test
    fun `a layer has one patch per layout cell and every patch has a cap`() {
        val layer = layer()
        assertEquals(PatchLayout.build(sphere, TerrainLayer.FACES_PER_PATCH).patchCount, layer.patchCount)
        for (patch in 0 until layer.patchCount) assertTrue(layer.cap(patch).chordRadius > 0f, "patch $patch")
    }

    @Test
    fun `a layer starts with no meshes built`() {
        val layer = layer()
        for (patch in 0 until layer.patchCount) assertNull(layer.meshOrNull(patch))
    }

    @Test
    fun `a released source can no longer build, but its caps remain for culling`() {
        val layer = layer()
        val cap = layer.cap(0)
        layer.releaseSource()
        assertFailsWith<IllegalStateException> { layer.build(0) }
        assertEquals(cap.chordRadius, layer.cap(0).chordRadius)
    }

    @Test
    fun `a tiny world is a handful of patches`() {
        val small = GeodesicSphere.generate(2)
        val smallLayer = TerrainLayer(SphereWorld(small, ByteArray(small.faceCount)))
        assertTrue(smallLayer.patchCount in 1..20, "got ${smallLayer.patchCount}")
    }
}
