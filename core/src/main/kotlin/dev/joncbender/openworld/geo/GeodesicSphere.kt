package dev.joncbender.openworld.geo

import com.badlogic.gdx.math.Vector3
import dev.joncbender.openworld.Parallel
import dev.joncbender.openworld.PerfTimer
import kotlin.math.sqrt

/**
 * A Goldberg polyhedron: the dual of a geodesically subdivided icosahedron.
 * Every face is a hexagon except for exactly 12 pentagons at the original
 * icosahedron's vertices - a sphere's surface can't be tiled by hexagons
 * alone (Euler's formula forces exactly 12 five-sided defects), so this is
 * the standard construction real hex-globes use.
 */
object GeodesicSphere {

    /** [frequency] controls tile count: total faces = 10*frequency^2 + 2. */
    fun generate(frequency: Int): Sphere {
        require(frequency >= 1) { "frequency must be >= 1" }
        val perf = PerfTimer()
        val (vertices, triangles) = subdivideIcosahedron(frequency)
        perf.lap("subdivideIcosahedron")
        val sphere = buildDual(vertices, triangles)
        perf.lap("buildDual")
        return sphere
    }

    private val PHI = ((1.0 + sqrt(5.0)) / 2.0).toFloat()

    internal val BASE_VERTICES = arrayOf(
        Vector3(-1f, PHI, 0f), Vector3(1f, PHI, 0f),
        Vector3(-1f, -PHI, 0f), Vector3(1f, -PHI, 0f),
        Vector3(0f, -1f, PHI), Vector3(0f, 1f, PHI),
        Vector3(0f, -1f, -PHI), Vector3(0f, 1f, -PHI),
        Vector3(PHI, 0f, -1f), Vector3(PHI, 0f, 1f),
        Vector3(-PHI, 0f, -1f), Vector3(-PHI, 0f, 1f),
    ).map { it.cpy().nor() }

    internal val BASE_FACES = arrayOf(
        intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
    )

    private const val EDGE_COUNT = 30

    /** Index of the icosahedron edge between base vertices `lo < hi`, in `0 until EDGE_COUNT`. */
    private val EDGE_IDS: Array<IntArray> = run {
        val ids = Array(BASE_VERTICES.size) { IntArray(BASE_VERTICES.size) { -1 } }
        var next = 0
        for (face in BASE_FACES) {
            for (k in 0..2) {
                val a = face[k]
                val b = face[(k + 1) % 3]
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                if (ids[lo][hi] == -1) ids[lo][hi] = next++
            }
        }
        check(next == EDGE_COUNT) { "expected $EDGE_COUNT icosahedron edges, found $next" }
        ids
    }

    /**
     * Builds each grid point via raw float barycentric interpolation instead
     * of chained Vector3.cpy()/scl()/add() calls, and normalizes in place
     * rather than copying first.
     *
     * A grid point is identified by its integer barycentric counts
     * (c0, c1, c2) toward the base face's three corners, summing to
     * [freq]. Only points on the base face's boundary can also be reached
     * from a neighboring base face - the 12 icosahedron corners (one count
     * is [freq]) and the points along its 30 edges (one count is zero) - so
     * those get an exact slot in a small table: a corner by its base index,
     * an edge point by which edge and how far along it. Interior points are
     * never shared and need no lookup at all. This replaces an earlier
     * scheme that quantized each point's float coordinates into a hash key:
     * a shared point is computed independently by each of its two faces and
     * can differ by an ULP, so any fixed quantum eventually splits one
     * vertex in two (observed at frequency 400) or merges two distinct
     * vertices once the spacing nears the quantum (observed at 700). Integer
     * identities have no such failure mode, at any frequency.
     */
    internal fun subdivideIcosahedron(freq: Int): Pair<FloatArray, IntArray> {
        // Both counts are known exactly up front (see the class doc /
        // generate()'s contract). Positions and triangles are flat primitive
        // arrays - a Vector3 per vertex and an IntArray per triangle was ~5M
        // small objects at 5x the production tile count, enough to exhaust
        // the Java heap before the sphere was even assembled.
        val expectedVertices = 10 * freq * freq + 2
        val expectedTriangles = 20 * freq * freq
        val vertices = FloatArray(expectedVertices * 3)
        var vertexCount = 0

        val edgeSlotsStart = BASE_VERTICES.size
        val sharedIndex = IntArray(edgeSlotsStart + EDGE_COUNT * (freq - 1)) { -1 }

        // The table slot for a point that can be reached from more than one
        // base face, or -1 for an interior point. ids/counts are the base
        // face's corner indices and the point's barycentric counts toward them.
        fun sharedSlot(ids: IntArray, counts: IntArray): Int {
            val zeros = (if (counts[0] == 0) 1 else 0) + (if (counts[1] == 0) 1 else 0) + (if (counts[2] == 0) 1 else 0)
            return when (zeros) {
                0 -> -1
                2 -> ids[counts.indexOfFirst { it != 0 }]
                else -> {
                    val a = counts.indexOfFirst { it != 0 }
                    val b = counts.indexOfLast { it != 0 }
                    val (lo, hi, countTowardHi) =
                        if (ids[a] < ids[b]) Triple(ids[a], ids[b], counts[b]) else Triple(ids[b], ids[a], counts[a])
                    edgeSlotsStart + EDGE_IDS[lo][hi] * (freq - 1) + (countTowardHi - 1)
                }
            }
        }

        // Normalizes through one reused Vector3 (the same nor() the object-per-
        // vertex version called), then stores the result in the flat array.
        val scratch = Vector3()
        fun addVertex(x: Float, y: Float, z: Float, slot: Int): Int {
            if (slot >= 0 && sharedIndex[slot] != -1) return sharedIndex[slot]
            scratch.set(x, y, z).nor()
            val index = vertexCount++
            vertices[index * 3] = scratch.x
            vertices[index * 3 + 1] = scratch.y
            vertices[index * 3 + 2] = scratch.z
            if (slot >= 0) sharedIndex[slot] = index
            return index
        }

        val triangles = IntArray(expectedTriangles * 3)
        var triangleCount = 0
        fun addTriangle(a: Int, b: Int, c: Int) {
            triangles[triangleCount * 3] = a
            triangles[triangleCount * 3 + 1] = b
            triangles[triangleCount * 3 + 2] = c
            triangleCount++
        }

        val counts = IntArray(3)
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
                    counts[0] = freq - i - j
                    counts[1] = i
                    counts[2] = j
                    grid[i][j] = addVertex(
                        v0.x * w0 + v1.x * a + v2.x * b,
                        v0.y * w0 + v1.y * a + v2.y * b,
                        v0.z * w0 + v1.z * a + v2.z * b,
                        sharedSlot(face, counts),
                    )
                }
            }

            for (i in 0 until freq) {
                for (j in 0 until freq - i) {
                    val a = grid[i][j]
                    val b = grid[i + 1][j]
                    val c = grid[i][j + 1]
                    addTriangle(a, b, c)
                    if (j < freq - i - 1) {
                        val d = grid[i + 1][j + 1]
                        addTriangle(b, d, c)
                    }
                }
            }
        }
        check(vertexCount == expectedVertices) { "expected $expectedVertices vertices, built $vertexCount" }
        check(triangleCount == expectedTriangles) { "expected $expectedTriangles triangles, built $triangleCount" }
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
    private fun buildDual(vertices: FloatArray, triangles: IntArray): Sphere {
        val perf = PerfTimer()
        val vertexCount = vertices.size / 3
        val triangleCount = triangles.size / 3

        // One dual-face corner per triangle, as a flat x/y/z array. The
        // scratch vector per chunk keeps this allocation-free while using
        // Vector3.nor() itself, so the result matches the old per-triangle
        // Vector3 version bit for bit.
        val vertexPositions = FloatArray(triangleCount * 3)
        Parallel.forRanges(triangleCount) { from, to ->
            val scratch = Vector3()
            for (ti in from until to) {
                val a = triangles[ti * 3] * 3
                val b = triangles[ti * 3 + 1] * 3
                val c = triangles[ti * 3 + 2] * 3
                scratch.set(
                    (vertices[a] + vertices[b] + vertices[c]) / 3f,
                    (vertices[a + 1] + vertices[b + 1] + vertices[c + 1]) / 3f,
                    (vertices[a + 2] + vertices[b + 2] + vertices[c + 2]) / 3f,
                ).nor()
                vertexPositions[ti * 3] = scratch.x
                vertexPositions[ti * 3 + 1] = scratch.y
                vertexPositions[ti * 3 + 2] = scratch.z
            }
        }
        perf.lap("buildDual.triCentroid")

        // Flat (CSR-style) vertex -> incident-triangle adjacency in plain
        // IntArrays. A vertex's degree is its dual face's corner count, so
        // these offsets are also the Sphere's cornerStart, and the adjacency
        // array itself becomes cornerVertex: the walk below only reorders each
        // vertex's own slice of it into winding order, in place.
        val cornerStart = IntArray(vertexCount + 1)
        for (v in triangles) cornerStart[v + 1]++
        for (v in 0 until vertexCount) cornerStart[v + 1] += cornerStart[v]
        val cornerVertex = IntArray(cornerStart[vertexCount])
        val cursor = cornerStart.copyOf(vertexCount)
        for (ti in 0 until triangleCount) {
            for (k in 0..2) {
                val v = triangles[ti * 3 + k]
                cornerVertex[cursor[v]++] = ti
            }
        }
        perf.lap("buildDual.csr")

        fun thirdVertexAfter(tri: Int, v: Int): Int {
            val base = tri * 3
            val i = if (triangles[base] == v) 0 else if (triangles[base + 1] == v) 1 else 2
            return triangles[base + (i + 2) % 3]
        }

        val neighbors = IntArray(cornerVertex.size)

        // Each vertex's walk reads only the shared, immutable triangles and its
        // own slice of cornerVertex, and writes only that slice and its slice
        // of neighbors, so the walks are independent and can run in parallel.
        // The ordered result goes through a small per-chunk scratch because the
        // walk is still reading the unordered slice while it builds the order.
        Parallel.forRanges(vertexCount) { chunkFrom, chunkTo ->
            val ordered = IntArray(MAX_DEGREE)
            for (v in chunkFrom until chunkTo) {
                val from = cornerStart[v]
                val to = cornerStart[v + 1]
                val degree = to - from
                check(degree <= MAX_DEGREE) { "vertex $v: degree $degree exceeds $MAX_DEGREE - malformed mesh" }

                val startTri = cornerVertex[from]
                var current = startTri
                var guard = 0
                do {
                    // Checked before writing so a malformed mesh can't spill into
                    // the next vertex's slice (which another thread may be filling).
                    check(guard < degree) { "vertex $v: dual walk did not close within $degree steps - malformed mesh" }
                    ordered[guard] = current
                    val targetVertex = thirdVertexAfter(current, v)
                    neighbors[from + guard] = targetVertex

                    var next = -1
                    for (k in from until to) {
                        val cand = cornerVertex[k]
                        if (cand == current) continue
                        val base = cand * 3
                        if (triangles[base] == targetVertex || triangles[base + 1] == targetVertex || triangles[base + 2] == targetVertex) {
                            next = cand
                            break
                        }
                    }
                    check(next != -1) { "vertex $v: no other triangle shares edge with vertex $targetVertex - malformed mesh" }
                    current = next
                    guard++
                } while (current != startTri)
                check(guard == degree) { "vertex $v: dual walk closed after $guard steps, expected exactly $degree - malformed mesh" }
                System.arraycopy(ordered, 0, cornerVertex, from, degree)
            }
        }
        perf.lap("buildDual.perVertexWalk")
        // The original vertex positions are exactly the face centers, so the
        // array is handed over rather than copied.
        return Sphere(vertices, cornerStart, cornerVertex, vertexPositions, neighbors)
    }

    // Pentagons have 5 corners and hexagons 6; nothing on this sphere has more.
    private const val MAX_DEGREE = 6
}
