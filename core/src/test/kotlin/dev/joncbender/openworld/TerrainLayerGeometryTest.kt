package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The vertex data a [TerrainLayer] hands to GL, checked without a GL context. */
class TerrainLayerGeometryTest {

    private val sphere = GeodesicSphere.generate(12) // 1,442 faces: 12 pentagons, the rest hexagons
    private val stride = TerrainLayer.VERTEX_SIZE // x, y, z, packed color, u, v

    private fun world(biome: Biome) = SphereWorld(sphere, ByteArray(sphere.faceCount) { biome.ordinal.toByte() })

    private val oceanBlue = Color(0.20f, 0.37f, 0.60f, 1f)
    private val whiteBits = Color.WHITE.toFloatBits()

    /** Every vertex of every patch, as (colorBits, u, v) - copied out, since the scratch arrays are reused. */
    private fun allVertices(layer: TerrainLayer): List<Triple<Float, Float, Float>> {
        val out = ArrayList<Triple<Float, Float, Float>>()
        for (patch in 0 until layer.patchCount) {
            val g = layer.geometry(patch)
            for (v in 0 until g.vertexCount) {
                out.add(Triple(g.vertices[v * stride + 3], g.vertices[v * stride + 4], g.vertices[v * stride + 5]))
            }
        }
        return out
    }

    @Test
    fun `a flat layer draws every vertex as the one color through the white texel`() {
        val layer = TerrainLayer(world(Biome.OCEAN), flatColor = oceanBlue)
        val (whiteU, whiteV) = BiomeTextures.whiteUV()
        val vertices = allVertices(layer)
        assertTrue(vertices.isNotEmpty())
        for ((color, u, v) in vertices) {
            assertEquals(oceanBlue.toFloatBits(), color)
            assertEquals(whiteU, u)
            assertEquals(whiteV, v)
        }
    }

    @Test
    fun `a textured layer is white-tinted and samples its biome's cells`() {
        val layer = TerrainLayer(world(Biome.DESERT))
        val allowed = HashSet<Pair<Float, Float>>().apply {
            for (variant in 0 until BiomeArt.VARIANTS) {
                add(BiomeTextures.centerU(Biome.DESERT, variant) to BiomeTextures.centerV(Biome.DESERT, variant))
                for (corners in 5..6) {
                    for (rotation in 0 until BiomeTextures.ROTATIONS) {
                        val uvs = BiomeTextures.cornerUVs(Biome.DESERT, corners, variant, rotation)
                        for (c in 0 until corners) add(uvs[c * 2] to uvs[c * 2 + 1])
                    }
                }
            }
        }
        for ((color, u, v) in allVertices(layer)) {
            assertEquals(whiteBits, color)
            assertTrue((u to v) in allowed, "uv ($u, $v) is not in one of the desert cells' center or corner sets")
        }
    }

    @Test
    fun `a flat layer and a textured layer have identical positions and triangles`() {
        val textured = TerrainLayer(world(Biome.OCEAN))
        val flat = TerrainLayer(world(Biome.OCEAN), flatColor = oceanBlue)
        assertEquals(textured.patchCount, flat.patchCount)
        for (patch in 0 until textured.patchCount) {
            val a = textured.geometry(patch)
            val aPositions = a.vertices.copyOf(a.vertexFloats)
            val aIndices = a.indices.copyOf(a.indexCount)
            val b = flat.geometry(patch)
            for (v in 0 until a.vertexCount) {
                for (axis in 0..2) assertEquals(aPositions[v * stride + axis], b.vertices[v * stride + axis], "patch $patch vertex $v")
            }
            assertTrue(aIndices.contentEquals(b.indices.copyOf(b.indexCount)), "patch $patch indices")
        }
    }

    @Test
    fun `a masked layer emits exactly the kept faces' centers and corners, and nothing of the dropped ones`() {
        val keep = BooleanArray(sphere.faceCount) { it % 3 != 0 }
        val layer = TerrainLayer(world(Biome.PLAINS), keep)

        // Each face emits its own center then its own corners (nothing is shared between faces), so
        // the multiset of vertex positions must equal that of the kept faces - which also proves no
        // dropped face's vertices are present, even one with the same corner count as a kept face.
        val expected = ArrayList<String>()
        for (i in sphere.indices) {
            if (!keep[i]) continue
            expected.add("${sphere.centerX(i)},${sphere.centerY(i)},${sphere.centerZ(i)}")
            for (c in 0 until sphere.cornerCount(i)) {
                expected.add("${sphere.cornerX(i, c)},${sphere.cornerY(i, c)},${sphere.cornerZ(i, c)}")
            }
        }
        val actual = ArrayList<String>()
        var indexCount = 0
        for (patch in 0 until layer.patchCount) {
            val g = layer.geometry(patch)
            for (v in 0 until g.vertexCount) {
                actual.add("${g.vertices[v * stride]},${g.vertices[v * stride + 1]},${g.vertices[v * stride + 2]}")
            }
            indexCount += g.indexCount
        }
        assertEquals(expected.sorted(), actual.sorted())
        assertEquals(3 * keptCorners(keep), indexCount)
    }

    private fun keptCorners(keep: BooleanArray): Int = sphere.indices.filter { keep[it] }.sumOf { sphere.cornerCount(it) }

    @Test
    fun `every triangle is a fan of its face - the center, then corner k and corner k plus one, wrapping at the end`() {
        val layer = TerrainLayer(world(Biome.FOREST), flatColor = oceanBlue)
        var faces = 0
        for (patch in 0 until layer.patchCount) {
            val g = layer.geometry(patch)
            assertEquals(0, g.indexCount % 3)
            fun index(slot: Int) = g.indices[slot].toInt() and 0xFFFF

            var t = 0
            var expectedCenter = 0 // vertices are laid out face by face: center, then its corners
            while (t < g.indexCount / 3) {
                val center = index(t * 3)
                assertEquals(expectedCenter, center, "patch $patch: a face's center must follow the previous face's last corner")
                // Count this face's triangles: they are the consecutive ones sharing this center.
                var corners = 0
                while (t + corners < g.indexCount / 3 && index((t + corners) * 3) == center) corners++
                assertTrue(corners == 5 || corners == 6, "patch $patch: a face has 5 or 6 corners, got $corners")
                for (k in 0 until corners) {
                    assertEquals(center + 1 + k, index((t + k) * 3 + 1), "patch $patch: corner k")
                    assertEquals(center + 1 + (k + 1) % corners, index((t + k) * 3 + 2), "patch $patch: corner k + 1 (wrapping)")
                }
                t += corners
                expectedCenter = center + 1 + corners
                faces++
            }
            assertEquals(g.vertexCount, expectedCenter, "patch $patch: every vertex belongs to a fan")
        }
        assertEquals(sphere.faceCount, faces)
    }

    @Test
    fun `positions lie on the unit sphere`() {
        val layer = TerrainLayer(world(Biome.FOREST), flatColor = oceanBlue)
        for (patch in 0 until layer.patchCount) {
            val g = layer.geometry(patch)
            for (v in 0 until g.vertexCount) {
                val x = g.vertices[v * stride]
                val y = g.vertices[v * stride + 1]
                val z = g.vertices[v * stride + 2]
                assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f, "patch $patch vertex $v")
            }
        }
    }

    @Test
    fun `geometry cannot be produced once the source is released`() {
        val layer = TerrainLayer(world(Biome.OCEAN))
        layer.releaseSource()
        assertFailsWith<IllegalStateException> { layer.geometry(0) }
    }
}
