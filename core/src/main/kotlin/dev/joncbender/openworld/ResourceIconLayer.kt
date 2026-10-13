package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import dev.joncbender.openworld.geo.Sphere
import kotlin.math.sqrt

/**
 * The resource glyphs of the near layer, one small mesh per patch, built only for patches in view while
 * the camera is close (see [GlobeScreen]) and released with them. Each icon is a quad lying on its tile,
 * facing north like the inked art, in the vertex layout the terrain shader reads.
 */
internal class ResourceIconLayer(private val near: TerrainLayer, private val world: SphereWorld) {
    private val meshes = arrayOfNulls<Mesh>(near.patchCount)
    var builtCount = 0
        private set

    fun meshOrNull(patch: Int): Mesh? = meshes[patch]

    /** Builds [patch]'s icons if they aren't built yet. A patch with no resources gets no mesh and is tried again, which is cheap. */
    fun build(patch: Int) {
        if (meshes[patch] != null) return
        val geometry = geometry(world, near, patch) ?: return
        val mesh = Mesh(
            true, geometry.vertexCount, geometry.indexCount,
            VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            VertexAttribute.ColorPacked(),
            VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
        )
        mesh.setVertices(geometry.vertices)
        mesh.setIndices(geometry.indices)
        meshes[patch] = mesh
        builtCount++
    }

    fun release(patch: Int) {
        meshes[patch]?.let {
            it.dispose()
            meshes[patch] = null
            builtCount--
        }
    }

    fun releaseAll() {
        if (builtCount == 0) return
        for (patch in meshes.indices) release(patch)
    }

    fun render(patch: Int, shader: ShaderProgram) {
        meshes[patch]?.render(shader, GL20.GL_TRIANGLES)
    }

    class Geometry(val vertices: FloatArray, val vertexCount: Int, val indices: ShortArray, val indexCount: Int)

    companion object {
        // Where icons sit relative to a tile's corner radius: (x, y, half size) of each icon for 1..4 icons,
        // x east and y north. Every icon square stays inside the hexagon.
        private val LAYOUTS = arrayOf(
            floatArrayOf(),
            floatArrayOf(0f, 0f, 0.55f),
            floatArrayOf(-0.40f, 0f, 0.36f, 0.40f, 0f, 0.36f),
            floatArrayOf(0f, 0.36f, 0.32f, -0.36f, -0.24f, 0.32f, 0.36f, -0.24f, 0.32f),
            floatArrayOf(-0.33f, 0.33f, 0.27f, 0.33f, 0.33f, 0.27f, -0.33f, -0.33f, 0.27f, 0.33f, -0.33f, 0.27f),
        )

        // Raised off the tile a hair so the quad never sinks into it; the draw also uses a polygon offset.
        private const val LIFT = 1.00004f

        private val WHITE_BITS = Color.WHITE.toFloatBits()

        /** The vertices and indices of [patch]'s icons, or null when none of its tiles holds a resource. Needs no GL. */
        fun geometry(world: SphereWorld, near: TerrainLayer, patch: Int): Geometry? {
            val sphere = world.sphere
            val faceCount = near.faceCountOf(patch)
            var quads = 0
            for (k in 0 until faceCount) quads += world.resourceCount(near.faceOf(patch, k))
            // A 16-bit index addresses 65,536 vertices: at most 16,384 quads.
            quads = minOf(quads, MAX_QUADS)
            if (quads == 0) return null

            val vertices = FloatArray(quads * 4 * TerrainLayer.VERTEX_SIZE)
            val indices = ShortArray(quads * 6)
            val uv = FloatArray(4)
            var quad = 0
            for (k in 0 until faceCount) {
                val face = near.faceOf(patch, k)
                val count = world.resourceCount(face)
                for (slot in 0 until count) {
                    if (quad == quads) break
                    ResourceIcons.uvRect(world.resourceAt(face, slot), uv, 0)
                    appendQuad(sphere, face, LAYOUTS[count], slot, uv, vertices, quad * 4 * TerrainLayer.VERTEX_SIZE)
                    val v = quad * 4
                    val i = quad * 6
                    indices[i] = v.toShort(); indices[i + 1] = (v + 1).toShort(); indices[i + 2] = (v + 2).toShort()
                    indices[i + 3] = v.toShort(); indices[i + 4] = (v + 2).toShort(); indices[i + 5] = (v + 3).toShort()
                    quad++
                }
            }
            return Geometry(vertices, quads * 4, indices, quads * 6)
        }

        const val MAX_QUADS = 16384

        /**
         * Writes the 4 corners (top-left, bottom-left, bottom-right, top-right) of icon [slot] of [face]. Its offsets
         * are in units of the tile's corner radius along east and north, with east = Y x p and north = p x east.
         */
        internal fun appendQuad(sphere: Sphere, face: Int, layout: FloatArray, slot: Int, uv: FloatArray, out: FloatArray, at: Int) {
            val px = sphere.centerX(face)
            val py = sphere.centerY(face)
            val pz = sphere.centerZ(face)
            var ex = pz
            var ez = -px
            var el = sqrt(ex * ex + ez * ez)
            if (el < 1e-6f) { ex = 1f; ez = 0f; el = 1f }
            ex /= el; ez /= el
            val nx = py * ez
            val ny = pz * ex - px * ez
            val nz = -py * ex

            val dx = sphere.cornerX(face, 0) - px
            val dy = sphere.cornerY(face, 0) - py
            val dz = sphere.cornerZ(face, 0) - pz
            val radius = sqrt(dx * dx + dy * dy + dz * dz)

            val cx = layout[slot * 3] * radius
            val cy = layout[slot * 3 + 1] * radius
            val half = layout[slot * 3 + 2] * radius
            var o = at
            for (corner in 0 until 4) {
                val sx = if (corner == 0 || corner == 1) -1f else 1f // west, west, east, east
                val sy = if (corner == 0 || corner == 3) 1f else -1f // north, south, south, north
                val east = cx + sx * half
                val north = cy + sy * half
                out[o++] = (px + ex * east + nx * north) * LIFT
                out[o++] = (py + ny * north) * LIFT
                out[o++] = (pz + ez * east + nz * north) * LIFT
                out[o++] = WHITE_BITS
                out[o++] = if (sx < 0) uv[0] else uv[2]
                out[o++] = if (sy > 0) uv[1] else uv[3]
            }
        }
    }
}
