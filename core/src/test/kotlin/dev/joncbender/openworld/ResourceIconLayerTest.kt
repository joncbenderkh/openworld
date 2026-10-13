package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The icon quads a [ResourceIconLayer] hands to GL, checked without a GL context. */
class ResourceIconLayerTest {
    private val sphere = GeodesicSphere.generate(12)
    private val stride = TerrainLayer.VERTEX_SIZE

    private fun world() = SphereWorld(sphere, ByteArray(sphere.faceCount) { Biome.PLAINS.ordinal.toByte() })

    @Test
    fun `a patch with no resources has no icons`() {
        val world = world()
        val layer = TerrainLayer(world)
        for (patch in 0 until layer.patchCount) assertNull(ResourceIconLayer.geometry(world, layer, patch))
    }

    @Test
    fun `each resource becomes one quad of four vertices and six indices`() {
        val world = world()
        world.setResources(5, listOf(Resource.GRAIN))
        world.setResources(9, listOf(Resource.GAME, Resource.GOLD, Resource.STONE))
        val layer = TerrainLayer(world)
        // Patches are spatial cells, so the faces may land in different ones: count across all.
        val geometries = (0 until layer.patchCount).mapNotNull { ResourceIconLayer.geometry(world, layer, it) }
        assertEquals(4 * 4, geometries.sumOf { it.vertexCount })
        assertEquals(4 * 6, geometries.sumOf { it.indexCount })
        for (geometry in geometries) assertTrue(geometry.indices.take(geometry.indexCount).all { it in 0 until geometry.vertexCount })
    }

    @Test
    fun `every icon lies inside its tile's hexagon, on the sphere, with its glyph upright`() {
        val world = world()
        for (face in sphere.indices) world.setResources(face, List(1 + face % SphereWorld.MAX_RESOURCES) { Resource.entries[(face + it) % Resource.entries.size] })
        val layer = TerrainLayer(world)
        var quads = 0
        for (patch in 0 until layer.patchCount) {
            val geometry = assertNotNull(ResourceIconLayer.geometry(world, layer, patch))
            var quad = 0
            for (k in 0 until layer.faceCountOf(patch)) {
                val face = layer.faceOf(patch, k)
                val cx = sphere.centerX(face); val cy = sphere.centerY(face); val cz = sphere.centerZ(face)
                val r = run {
                    val dx = sphere.cornerX(face, 0) - cx; val dy = sphere.cornerY(face, 0) - cy; val dz = sphere.cornerZ(face, 0) - cz
                    sqrt(dx * dx + dy * dy + dz * dz)
                }
                repeat(world.resourceCount(face)) {
                    for (corner in 0 until 4) {
                        val o = (quad * 4 + corner) * stride
                        val dx = geometry.vertices[o] - cx; val dy = geometry.vertices[o + 1] - cy; val dz = geometry.vertices[o + 2] - cz
                        assertTrue(sqrt(dx * dx + dy * dy + dz * dz) < r * 0.9f, "face $face corner $corner leaves the tile")
                        val radius = sqrt(geometry.vertices[o] * geometry.vertices[o] + geometry.vertices[o + 1] * geometry.vertices[o + 1] + geometry.vertices[o + 2] * geometry.vertices[o + 2])
                        assertEquals(1f, radius, 0.01f)
                    }
                    // top-left is above bottom-left in the texture (smaller V), and left of top-right (smaller U)
                    val tl = quad * 4 * stride; val bl = tl + stride; val tr = tl + 3 * stride
                    assertTrue(geometry.vertices[tl + 5] < geometry.vertices[bl + 5])
                    assertTrue(geometry.vertices[tl + 4] < geometry.vertices[tr + 4])
                    quad++
                }
            }
            quads += quad
        }
        assertTrue(quads > 0)
    }

    @Test
    fun `a patch never exceeds what a 16-bit index can address`() {
        assertTrue(ResourceIconLayer.MAX_QUADS * 4 <= 65536)
    }
}
