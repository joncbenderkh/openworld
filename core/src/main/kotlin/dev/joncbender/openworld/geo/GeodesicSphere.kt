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

    /**
     * Builds each grid point via raw float barycentric interpolation instead
     * of chained Vector3.cpy()/scl()/add() calls, and normalizes in place in
     * addVertex rather than copying first - each grid point (up to ~80k of
     * them at the game's production frequency) previously cost 3 Vector3
     * allocations, now costs 1.
     */
    private fun subdivideIcosahedron(freq: Int): Pair<List<Vector3>, List<IntArray>> {
        val vertices = ArrayList<Vector3>()
        val keyToIndex = HashMap<Long, Int>()

        fun keyOf(v: Vector3): Long {
            // Quantize so points shared by adjacent icosahedron faces merge into one
            // vertex. A shared boundary point is computed independently by each of its
            // two faces via different operand orderings (e.g. v0*(1-t)+v1*t vs the
            // mirrored v1*(1-t')+v0*t'), which can differ by ~1 float32 ULP - too coarse
            // a scale here lets that noise flip which side of a rounding boundary the
            // point lands on, silently producing two vertices instead of one. 1e3 stays
            // far below real inter-vertex spacing even at high subdivision frequencies.
            val qx = Math.round(v.x * 1000f).toLong() and 0x1FFFFF
            val qy = Math.round(v.y * 1000f).toLong() and 0x1FFFFF
            val qz = Math.round(v.z * 1000f).toLong() and 0x1FFFFF
            return qx or (qy shl 21) or (qz shl 42)
        }

        // Takes ownership of v (mutates it in place) - always call with a
        // freshly constructed Vector3, never a shared/reused instance.
        fun addVertex(v: Vector3): Int {
            v.nor()
            return keyToIndex.getOrPut(keyOf(v)) {
                vertices.add(v)
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
                    val w0 = 1f - a - b
                    val p = Vector3(
                        v0.x * w0 + v1.x * a + v2.x * b,
                        v0.y * w0 + v1.y * a + v2.y * b,
                        v0.z * w0 + v1.z * a + v2.z * b,
                    )
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

    /**
     * Building each dual face means, for every original vertex v: (1) the
     * cyclic order of triangles around it (their centroids become the dual
     * face's corners), and (2) its neighboring vertices (= neighboring dual
     * faces). The original approach found "the other triangle sharing edge
     * (v, x)" via one global HashMap<Long, MutableList<Int>> covering every
     * edge in the whole mesh (~475k entries at the game's production
     * frequency, each boxing a Long key and allocating an ArrayList) plus a
     * global Array<LinkedHashSet<Int>> for neighbors - both were the
     * dominant cost of world generation (measured ~17.5s of a ~19.5s total).
     *
     * Since a triangle sharing edge (v, x) must itself be incident to v, that
     * search only ever needs the small set of triangles already incident to
     * v (5 or 6 of them) - no global structure is needed, just a per-vertex
     * linear scan. The neighbor list falls out of the same walk for free
     * (each step's "other" vertex is exactly one dual-face neighbor), so the
     * separate neighbor-set pass is gone too.
     */
    private fun buildDual(vertices: List<Vector3>, triangles: List<IntArray>): List<Face> {
        val triCentroid = Array(triangles.size) { ti ->
            val t = triangles[ti]
            val v0 = vertices[t[0]]
            val v1 = vertices[t[1]]
            val v2 = vertices[t[2]]
            Vector3((v0.x + v1.x + v2.x) / 3f, (v0.y + v1.y + v2.y) / 3f, (v0.z + v1.z + v2.z) / 3f).nor()
        }

        // Flat (CSR-style) vertex -> incident-triangle adjacency, built with
        // plain IntArrays instead of Array<ArrayList<Int>> to avoid boxing.
        val incidentCount = IntArray(vertices.size)
        for (t in triangles) {
            incidentCount[t[0]]++; incidentCount[t[1]]++; incidentCount[t[2]]++
        }
        val incidentStart = IntArray(vertices.size + 1)
        for (v in vertices.indices) incidentStart[v + 1] = incidentStart[v] + incidentCount[v]
        val incidentTriangles = IntArray(incidentStart[vertices.size])
        val cursor = incidentStart.copyOf()
        for ((ti, t) in triangles.withIndex()) {
            for (v in t) {
                incidentTriangles[cursor[v]] = ti
                cursor[v]++
            }
        }

        fun thirdVertexAfter(t: IntArray, v: Int): Int {
            val i = t.indexOf(v)
            return t[(i + 2) % 3]
        }

        val faces = ArrayList<Face>(vertices.size)
        for (v in vertices.indices) {
            val from = incidentStart[v]
            val to = incidentStart[v + 1]
            val degree = to - from

            val corners = ArrayList<Vector3>(degree)
            val neighbors = ArrayList<Int>(degree)
            val startTri = incidentTriangles[from]
            var current = startTri
            var guard = 0
            do {
                corners.add(triCentroid[current])
                val targetVertex = thirdVertexAfter(triangles[current], v)
                neighbors.add(targetVertex)

                var next = -1
                for (k in from until to) {
                    val cand = incidentTriangles[k]
                    if (cand == current) continue
                    val t = triangles[cand]
                    if (t[0] == targetVertex || t[1] == targetVertex || t[2] == targetVertex) {
                        next = cand
                        break
                    }
                }
                check(next != -1) { "vertex $v: no other triangle shares edge with vertex $targetVertex - malformed mesh" }
                current = next
                guard++
            } while (current != startTri && guard <= degree)

            faces.add(Face(center = vertices[v], corners = corners, neighbors = neighbors.toIntArray()))
        }
        return faces
    }
}
