package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import dev.joncbender.openworld.geo.Sphere

/**
 * The rings drawn on settled tiles, in the terrain's vertex layout like [TileHighlight] (the shader's white texel and
 * the vertex color give the colour). A ring is thicker for a higher tier. The caller passes only the settlements on
 * the near side of the globe, and draws it over a cleared depth buffer.
 */
internal class SettlementMarkers {
    private val mesh = Mesh(
        false,
        MAX_VERTICES,
        0,
        VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
        VertexAttribute.ColorPacked(),
        VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
    )
    private val vertices = FloatArray(MAX_VERTICES * VERTEX_SIZE)
    private val triangles = FloatArray(RING_VERTICES * 3)
    private var vertexCount = 0
    private val u: Float
    private val v: Float
    private val colorBits = COLOR.toFloatBits()

    init {
        val white = BiomeTextures.whiteUV()
        u = white.first
        v = white.second
    }

    fun clear() {
        vertexCount = 0
    }

    /**
     * Adds a ring on [tile], [halfWidth] being half its thickness on the globe, in the settlement colour or - for a tile
     * the settlement only claims - the lighter [claimed] one. Returns false, adding nothing, when the mesh is full:
     * render and [clear] it, then add again.
     */
    fun add(sphere: Sphere, tile: Int, halfWidth: Float, claimed: Boolean): Boolean {
        if (vertexCount + RING_VERTICES > MAX_VERTICES) return false
        val color = if (claimed) CLAIM_COLOR.toFloatBits() else colorBits
        val written = TileHighlight.ring(sphere, tile, halfWidth, triangles) / 3
        for (i in 0 until written) {
            val at = (vertexCount + i) * VERTEX_SIZE
            vertices[at] = triangles[i * 3]
            vertices[at + 1] = triangles[i * 3 + 1]
            vertices[at + 2] = triangles[i * 3 + 2]
            vertices[at + 3] = color
            vertices[at + 4] = u
            vertices[at + 5] = v
        }
        vertexCount += written
        return true
    }

    fun render(shader: ShaderProgram) {
        if (vertexCount == 0) return
        mesh.setVertices(vertices, 0, vertexCount * VERTEX_SIZE)
        mesh.render(shader, GL20.GL_TRIANGLES, 0, vertexCount)
    }

    fun dispose() = mesh.dispose()

    private companion object {
        const val VERTEX_SIZE = 6 // position(3) + packed color(1) + texCoord(2)
        const val RING_VERTICES = 6 * 6
        const val MAX_VERTICES = RING_VERTICES * 128
        val COLOR = Color(0.45f, 0.10f, 0.08f, 1f)
        val CLAIM_COLOR = Color(0.90f, 0.42f, 0.10f, 1f)
    }
}
