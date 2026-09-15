package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3

/**
 * Latitude/longitude reference lines drawn as thin bands sitting just above
 * the sphere's surface (own mesh, same position+color vertex layout and
 * shader as the terrain), so they read as crisp lines independent of tile
 * resolution rather than following jagged tile edges.
 */
object Graticule {

    private const val VERTEX_SIZE = 7 // position(3) + color(4)
    private const val LINE_RADIUS = 1.004f // just proud of the terrain sphere (radius 1)
    private const val PARALLEL_SEGMENTS = 128
    private const val MERIDIAN_SEGMENTS = 64

    private const val THIN_HALF_WIDTH = 0.0025f
    private const val TROPIC_HALF_WIDTH = 0.0045f
    private const val EMPHASIZED_HALF_WIDTH = 0.007f

    private val GRID_COLOR = Color(0.85f, 0.85f, 0.85f, 1f)
    private val EQUATOR_COLOR = Color(0.95f, 0.15f, 0.15f, 1f)
    private val PRIME_MERIDIAN_COLOR = Color(1f, 0.85f, 0.1f, 1f)
    private val DATE_LINE_COLOR = Color(0.2f, 0.75f, 1f, 1f)
    private val TROPIC_COLOR = Color(1f, 0.55f, 0.1f, 1f)

    private const val TROPIC_LATITUDE_DEG = 23.4368f

    fun build(): Mesh {
        val data = ArrayList<Float>()

        for (latDeg in -60..60 step 30) {
            val emphasized = latDeg == 0
            addParallel(
                data,
                latitudeToPhi(latDeg.toFloat()),
                if (emphasized) EMPHASIZED_HALF_WIDTH else THIN_HALF_WIDTH,
                if (emphasized) EQUATOR_COLOR else GRID_COLOR,
            )
        }
        addParallel(data, latitudeToPhi(TROPIC_LATITUDE_DEG), TROPIC_HALF_WIDTH, TROPIC_COLOR)
        addParallel(data, latitudeToPhi(-TROPIC_LATITUDE_DEG), TROPIC_HALF_WIDTH, TROPIC_COLOR)

        for (lonDeg in 0 until 360 step 30) {
            val (color, halfWidth) = when (lonDeg) {
                0 -> PRIME_MERIDIAN_COLOR to EMPHASIZED_HALF_WIDTH
                180 -> DATE_LINE_COLOR to EMPHASIZED_HALF_WIDTH
                else -> GRID_COLOR to THIN_HALF_WIDTH
            }
            addMeridian(data, lonDeg * MathUtils.degreesToRadians, halfWidth, color)
        }

        val floatArray = data.toFloatArray()
        val mesh = Mesh(
            true,
            floatArray.size / VERTEX_SIZE,
            0,
            VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
        )
        mesh.setVertices(floatArray)
        return mesh
    }

    /** Colatitude (from the +Y pole) for a latitude in degrees, matching GlobeScreen's convention. */
    private fun latitudeToPhi(latDeg: Float): Float = (90f - latDeg) * MathUtils.degreesToRadians

    private fun spherePoint(phi: Float, theta: Float): Vector3 {
        val s = MathUtils.sin(phi) * LINE_RADIUS
        return Vector3(s * MathUtils.cos(theta), MathUtils.cos(phi) * LINE_RADIUS, s * MathUtils.sin(theta))
    }

    private fun addParallel(out: MutableList<Float>, phi: Float, halfWidth: Float, color: Color) {
        val inner = phi - halfWidth
        val outer = phi + halfWidth
        for (i in 0 until PARALLEL_SEGMENTS) {
            val t0 = i.toFloat() / PARALLEL_SEGMENTS * MathUtils.PI2
            val t1 = (i + 1).toFloat() / PARALLEL_SEGMENTS * MathUtils.PI2
            appendQuad(out, spherePoint(inner, t0), spherePoint(inner, t1), spherePoint(outer, t1), spherePoint(outer, t0), color)
        }
    }

    private fun addMeridian(out: MutableList<Float>, theta: Float, halfWidth: Float, color: Color) {
        val inner = theta - halfWidth
        val outer = theta + halfWidth
        for (i in 0 until MERIDIAN_SEGMENTS) {
            val p0 = i.toFloat() / MERIDIAN_SEGMENTS * MathUtils.PI
            val p1 = (i + 1).toFloat() / MERIDIAN_SEGMENTS * MathUtils.PI
            appendQuad(out, spherePoint(p0, inner), spherePoint(p1, inner), spherePoint(p1, outer), spherePoint(p0, outer), color)
        }
    }

    private fun appendQuad(out: MutableList<Float>, a: Vector3, b: Vector3, c: Vector3, d: Vector3, color: Color) {
        appendVertex(out, a, color); appendVertex(out, b, color); appendVertex(out, c, color)
        appendVertex(out, a, color); appendVertex(out, c, color); appendVertex(out, d, color)
    }

    private fun appendVertex(out: MutableList<Float>, pos: Vector3, color: Color) {
        out.add(pos.x); out.add(pos.y); out.add(pos.z)
        out.add(color.r); out.add(color.g); out.add(color.b); out.add(color.a)
    }
}
