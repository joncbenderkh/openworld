package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import dev.joncbender.openworld.geo.Sphere
import kotlin.math.sqrt

/**
 * The outline drawn around the selected tile: a ring between a shrunken and a grown copy of the
 * tile's polygon, as its own small mesh in the terrain's vertex layout (see [Graticule]) so the
 * shader's white texel and the vertex color give its colour. It lies on the tile itself, not above
 * it (a lifted ring would slide off the tile at a close zoom, where a hundredth of the radius is
 * many pixels of parallax); the caller draws it last, over a cleared depth buffer.
 */
internal class TileHighlight {
    private val mesh = Mesh(
        false,
        MAX_VERTICES,
        0,
        VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
        VertexAttribute.ColorPacked(),
        VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
    )
    private val vertices = FloatArray(MAX_VERTICES * VERTEX_SIZE)
    private val triangles = FloatArray(MAX_VERTICES * 3)
    private var vertexCount = 0
    private val u: Float
    private val v: Float
    private val colorBits = COLOR.toFloatBits()

    init {
        val white = BiomeTextures.whiteUV()
        u = white.first
        v = white.second
    }

    /** Rebuilds the ring for [tile], [halfWidth] being half its thickness measured on the globe. */
    fun update(sphere: Sphere, tile: Int, halfWidth: Float) {
        vertexCount = ring(sphere, tile, halfWidth, triangles) / 3
        for (i in 0 until vertexCount) {
            vertices[i * VERTEX_SIZE] = triangles[i * 3]
            vertices[i * VERTEX_SIZE + 1] = triangles[i * 3 + 1]
            vertices[i * VERTEX_SIZE + 2] = triangles[i * 3 + 2]
            vertices[i * VERTEX_SIZE + 3] = colorBits
            vertices[i * VERTEX_SIZE + 4] = u
            vertices[i * VERTEX_SIZE + 5] = v
        }
        mesh.setVertices(vertices, 0, vertexCount * VERTEX_SIZE)
    }

    fun render(shader: ShaderProgram) {
        if (vertexCount > 0) mesh.render(shader, GL20.GL_TRIANGLES, 0, vertexCount)
    }

    fun dispose() = mesh.dispose()

    companion object {
        /** The ring lies on the unit sphere. */
        const val RADIUS = 1f

        private const val VERTEX_SIZE = 6 // position(3) + packed color(1) + texCoord(2)
        private const val MAX_CORNERS = 6
        private const val MAX_VERTICES = MAX_CORNERS * 6
        private val COLOR = Color(1f, 0.95f, 0.35f, 1f)

        /**
         * Writes the ring's triangles as x, y, z triples into [out] and returns how many floats it wrote.
         * The ring runs from the polygon shrunk about the tile's center by [halfWidth] (to the center
         * itself if that is more than the tile's radius) to the polygon grown by the same, every point
         * put on the sphere at [RADIUS].
         */
        fun ring(sphere: Sphere, tile: Int, halfWidth: Float, out: FloatArray): Int {
            val corners = sphere.cornerCount(tile)
            val cx = sphere.centerX(tile)
            val cy = sphere.centerY(tile)
            val cz = sphere.centerZ(tile)
            var radius = 0f
            for (k in 0 until corners) {
                val dx = sphere.cornerX(tile, k) - cx
                val dy = sphere.cornerY(tile, k) - cy
                val dz = sphere.cornerZ(tile, k) - cz
                radius += sqrt(dx * dx + dy * dy + dz * dz)
            }
            radius /= corners
            val grow = 1f + halfWidth / radius
            val shrink = maxOf(0f, 1f - halfWidth / radius)

            var written = 0
            fun put(k: Int, scale: Float) {
                val x = cx + (sphere.cornerX(tile, k) - cx) * scale
                val y = cy + (sphere.cornerY(tile, k) - cy) * scale
                val z = cz + (sphere.cornerZ(tile, k) - cz) * scale
                val lift = RADIUS / sqrt(x * x + y * y + z * z)
                out[written++] = x * lift
                out[written++] = y * lift
                out[written++] = z * lift
            }
            for (k in 0 until corners) {
                val next = (k + 1) % corners
                put(k, shrink); put(next, shrink); put(next, grow)
                put(k, shrink); put(next, grow); put(k, grow)
            }
            return written
        }
    }
}
