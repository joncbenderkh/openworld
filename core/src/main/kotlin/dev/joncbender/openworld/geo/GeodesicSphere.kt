package dev.joncbender.openworld.geo

import com.badlogic.gdx.math.Vector3
import kotlin.math.sqrt

/** One tile on the sphere: a pentagon (12 of these total) or a hexagon. */
data class Face(
    val center: Vector3,
    val corners: List<Vector3>,
    val neighbors: IntArray,
)

/**
 * A Goldberg polyhedron: the dual of a geodesically subdivided icosahedron.
 * Every face is a hexagon except for exactly 12 pentagons at the original
 * icosahedron's vertices - a sphere's surface can't be tiled by hexagons
 * alone (Euler's formula forces exactly 12 five-sided defects), so this is
 * the standard construction real hex-globes use.
 */
object GeodesicSphere {

    /** [frequency] controls tile count: total faces = 10*frequency^2 + 2. */
    fun generate(frequency: Int): List<Face> {
        require(frequency >= 1) { "frequency must be >= 1" }
        val (vertices, triangles) = subdivideIcosahedron(frequency)
        return buildDual(vertices, triangles)
    }

    private val PHI = ((1.0 + sqrt(5.0)) / 2.0).toFloat()

    private val BASE_VERTICES = arrayOf(
        Vector3(-1f, PHI, 0f), Vector3(1f, PHI, 0f),
        Vector3(-1f, -PHI, 0f), Vector3(1f, -PHI, 0f),
        Vector3(0f, -1f, PHI), Vector3(0f, 1f, PHI),
        Vector3(0f, -1f, -PHI), Vector3(0f, 1f, -PHI),
        Vector3(PHI, 0f, -1f), Vector3(PHI, 0f, 1f),
        Vector3(-PHI, 0f, -1f), Vector3(-PHI, 0f, 1f),
    ).map { it.cpy().nor() }

    private val BASE_FACES = arrayOf(
        intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
    )

    private fun subdivideIcosahedron(freq: Int): Pair<List<Vector3>, List<IntArray>> {
        val vertices = ArrayList<Vector3>()
        val keyToIndex = HashMap<Long, Int>()

        fun keyOf(v: Vector3): Long {
            // Quantize so points shared by adjacent icosahedron faces merge into one vertex.
            val qx = Math.round(v.x * 100000f).toLong() and 0x1FFFFF
            val qy = Math.round(v.y * 100000f).toLong() and 0x1FFFFF
            val qz = Math.round(v.z * 100000f).toLong() and 0x1FFFFF
            return qx or (qy shl 21) or (qz shl 42)
        }

        fun addVertex(v: Vector3): Int {
            val nv = v.cpy().nor()
            return keyToIndex.getOrPut(keyOf(nv)) {
                vertices.add(nv)
                vertices.size - 1
            }
        }

        val triangles = ArrayList<IntArray>()
        for (face in BASE_FACES) {
            val v0 = BASE_VERTICES[face[0]]
            val v1 = BASE_VERTICES[face[1]]
            val v2 = BASE_VERTICES[face[2]]

            val grid = Array(freq + 1) { IntArray(freq + 1) { -1 } }
            for (i in 0..freq) {
                for (j in 0..freq - i) {
                    val a = i.toFloat() / freq
                    val b = j.toFloat() / freq
                    val p = v0.cpy().scl(1f - a - b).add(v1.cpy().scl(a)).add(v2.cpy().scl(b))
                    grid[i][j] = addVertex(p)
                }
            }

            for (i in 0 until freq) {
                for (j in 0 until freq - i) {
                    val a = grid[i][j]
                    val b = grid[i + 1][j]
                    val c = grid[i][j + 1]
                    triangles.add(intArrayOf(a, b, c))
                    if (j < freq - i - 1) {
                        val d = grid[i + 1][j + 1]
                        triangles.add(intArrayOf(b, d, c))
                    }
                }
            }
        }
        return vertices to triangles
    }

    private fun buildDual(vertices: List<Vector3>, triangles: List<IntArray>): List<Face> {
        val triCentroid = triangles.map { t ->
            vertices[t[0]].cpy().add(vertices[t[1]]).add(vertices[t[2]]).scl(1f / 3f).nor()
        }

        val vertexTriangles = Array(vertices.size) { ArrayList<Int>() }
        for ((ti, t) in triangles.withIndex()) {
            for (v in t) vertexTriangles[v].add(ti)
        }

        fun edgeKey(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong()
            val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }
        val edgeTriangles = HashMap<Long, MutableList<Int>>()
        for ((ti, t) in triangles.withIndex()) {
            edgeTriangles.getOrPut(edgeKey(t[0], t[1])) { ArrayList() }.add(ti)
            edgeTriangles.getOrPut(edgeKey(t[1], t[2])) { ArrayList() }.add(ti)
            edgeTriangles.getOrPut(edgeKey(t[2], t[0])) { ArrayList() }.add(ti)
        }

        fun rotateToStart(t: IntArray, v: Int): IntArray {
            val i = t.indexOf(v)
            return intArrayOf(t[i], t[(i + 1) % 3], t[(i + 2) % 3])
        }

        val neighborSets = Array(vertices.size) { LinkedHashSet<Int>() }
        for (t in triangles) {
            neighborSets[t[0]].add(t[1]); neighborSets[t[0]].add(t[2])
            neighborSets[t[1]].add(t[0]); neighborSets[t[1]].add(t[2])
            neighborSets[t[2]].add(t[0]); neighborSets[t[2]].add(t[1])
        }

        val faces = ArrayList<Face>(vertices.size)
        for (v in vertices.indices) {
            val incident = vertexTriangles[v]
            val startTri = incident[0]
            val ordered = ArrayList<Vector3>(incident.size)
            var current = startTri
            var guard = 0
            do {
                ordered.add(triCentroid[current])
                val rotated = rotateToStart(triangles[current], v)
                val candidates = edgeTriangles.getValue(edgeKey(v, rotated[2]))
                current = candidates.first { it != current }
                guard++
            } while (current != startTri && guard <= incident.size)

            faces.add(Face(center = vertices[v], corners = ordered, neighbors = neighborSets[v].toIntArray()))
        }
        return faces
    }
}
