package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes

/**
 * One resolution of the globe's terrain: a [SphereWorld] split into patches of
 * consecutive faces, each with a bounding [SphereCap] and (once built) its own
 * indexed [Mesh]. The caps are computed up front, so a caller can decide
 * which patches are worth building or drawing before paying for any mesh.
 *
 * Meshes are indexed rather than one big non-indexed mesh: a hexagon's fan
 * needs only 7 distinct points (1 center + 6 corners) but a non-indexed mesh
 * pays 18, which at 5x the tile count was a single 256MB FloatArray (and an
 * OutOfMemoryError). Splitting into patches is needed on top of that
 * regardless, since GL's 16-bit index buffers can only address 65536 distinct
 * vertices per mesh - nowhere near enough for a whole globe.
 *
 * [build] and [buildAll] create GL objects, so they must run on the GL thread.
 */
class TerrainLayer(world: SphereWorld) {
    private class Source(val world: SphereWorld, val layout: PatchLayout)

    // Dropped by releaseSource() once a layer that was built eagerly no longer needs to build
    // anything; the caps below are all that culling needs afterwards.
    private var source: Source?

    val patchCount: Int

    private val caps: Array<SphereCap?>
    private val meshes: Array<Mesh?>

    init {
        val layout = PatchLayout.build(world.sphere, FACES_PER_PATCH)
        source = Source(world, layout)
        patchCount = layout.patchCount
        caps = arrayOfNulls<SphereCap>(patchCount)
        Parallel.forEachIndex(patchCount) { patch ->
            caps[patch] = SphereCap.enclosing(world.sphere, layout.faceIndices, layout.start(patch), layout.end(patch))
        }
        meshes = arrayOfNulls(patchCount)
    }

    // Scratch arrays for filling a patch, shared by every build and allocated on first use.
    private var vertexData: FloatArray? = null
    private var indexData: ShortArray? = null

    fun cap(patch: Int): SphereCap = caps[patch]!!

    /** The built mesh for [patch], or null if it hasn't been built (or was released). */
    fun meshOrNull(patch: Int): Mesh? = meshes[patch]

    /** Builds [patch]'s mesh if it isn't already, and returns it. */
    fun build(patch: Int): Mesh {
        meshes[patch]?.let { return it }
        val (world, layout) = source?.let { it.world to it.layout }
            ?: error("TerrainLayer source was released; patch $patch cannot be built")
        val sphere = world.sphere
        val vertices = vertexData ?: FloatArray(FACES_PER_PATCH * VERTICES_PER_FACE * VERTEX_SIZE).also { vertexData = it }
        val indices = indexData ?: ShortArray(FACES_PER_PATCH * VERTICES_PER_FACE * 3).also { indexData = it }

        var vertexFloatPos = 0
        var vertexCount = 0
        var indexCount = 0
        for (k in layout.start(patch) until layout.end(patch)) {
            val i = layout.faceIndices[k]
            val biome = world[i]
            val centerU = BiomeTextures.centerU(biome)
            val centerV = BiomeTextures.centerV(biome)
            val n = sphere.cornerCount(i)
            val cornerUVs = BiomeTextures.cornerUVs(biome, n)

            val centerIndex = vertexCount
            vertexFloatPos = appendVertex(vertices, vertexFloatPos, sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i), centerU, centerV)
            vertexCount++

            val firstCornerIndex = vertexCount
            for (c in 0 until n) {
                vertexFloatPos = appendVertex(
                    vertices, vertexFloatPos,
                    sphere.cornerX(i, c), sphere.cornerY(i, c), sphere.cornerZ(i, c),
                    cornerUVs[c * 2], cornerUVs[c * 2 + 1],
                )
                vertexCount++
            }
            for (c in 0 until n) {
                val next = (c + 1) % n
                indices[indexCount++] = centerIndex.toShort()
                indices[indexCount++] = (firstCornerIndex + c).toShort()
                indices[indexCount++] = (firstCornerIndex + next).toShort()
            }
        }

        val mesh = Mesh(
            true,
            vertexCount,
            indexCount,
            VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            VertexAttribute.ColorPacked(),
            VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
        )
        mesh.setVertices(vertices, 0, vertexFloatPos)
        mesh.setIndices(indices, 0, indexCount)
        meshes[patch] = mesh
        return mesh
    }

    /** Builds every patch's mesh, logging how long the whole layer took. */
    fun buildAll() {
        val start = System.nanoTime()
        for (patch in 0 until patchCount) build(patch)
        Gdx.app?.log("perf", "TerrainLayer.buildAll: ${(System.nanoTime() - start) / 1_000_000}ms for $patchCount meshes")
    }

    /**
     * Lets go of the world this layer builds from, for a layer whose meshes are all built.
     * Nothing can be built afterwards, but the (large) sphere can be collected.
     */
    fun releaseSource() {
        source = null
    }

    /** Releases [patch]'s mesh; it can be rebuilt later with [build]. */
    fun release(patch: Int) {
        meshes[patch]?.dispose()
        meshes[patch] = null
    }

    fun dispose() {
        for (patch in 0 until patchCount) release(patch)
        // A disposed layer is finished; don't keep the world it was built from alive.
        source = null
    }

    private fun appendVertex(data: FloatArray, offset: Int, x: Float, y: Float, z: Float, u: Float, v: Float): Int {
        var o = offset
        data[o++] = x; data[o++] = y; data[o++] = z
        data[o++] = WHITE_BITS
        data[o++] = u; data[o++] = v
        return o
    }

    companion object {
        // Every face emits one center vertex plus one per corner: 7 for a hexagon, 6 for
        // a pentagon. A patch of this many faces therefore never exceeds 59,997 vertices,
        // inside the 65,536 a 16-bit index can address.
        const val VERTICES_PER_FACE = 7
        const val MAX_VERTICES_PER_PATCH = 60000
        const val FACES_PER_PATCH = MAX_VERTICES_PER_PATCH / VERTICES_PER_FACE

        // position(3) + color packed into one float's 4 bytes + texCoord(2). The color
        // was four full floats (16 of 36 bytes per vertex) though every terrain vertex
        // is plain white - the texture carries all the actual color.
        const val VERTEX_SIZE = 6
        private val WHITE_BITS = Color.WHITE.toFloatBits()
    }
}
