package dev.joncbender.openworld.geo

/**
 * The tiling of a Goldberg sphere (see [GeodesicSphere]) as flat primitive
 * arrays rather than an object per tile. At ~400k tiles an object-per-tile
 * layout means millions of small heap objects (a face, its corner list, a
 * Vector3 per corner, a neighbor array) - slow to build, slow to load from
 * the cache, and heavy on the garbage collector. Here everything is a handful
 * of arrays, so building, caching and reading it are bulk operations.
 *
 * A tile ("face") `i` has corner slots `cornerStart[i] until cornerStart[i + 1]`
 * (5 for the 12 pentagons, 6 otherwise), in winding order around the tile. For
 * each slot `k`, `neighbors[k]` is the tile across the edge that starts at
 * corner `k`, and `cornerVertex[k]` indexes the corner's position in
 * [vertexPositions] - a corner is shared by the three tiles that meet there,
 * so positions are stored once per vertex, not once per tile corner.
 *
 * The arrays are exposed (rather than hidden behind accessors) so the inline
 * neighbor helpers below can read them without a call per element; treat
 * them as read-only.
 */
class Sphere(
    /** x, y, z per face, unit length. */
    val centers: FloatArray,
    /** Prefix offsets into the per-corner arrays; size is `faceCount + 1`. */
    val cornerStart: IntArray,
    /** Per corner slot: index of the vertex it sits on. */
    val cornerVertex: IntArray,
    /** x, y, z per vertex, unit length. */
    val vertexPositions: FloatArray,
    /** Per corner slot: the neighboring face across that corner's edge. */
    val neighbors: IntArray,
) {
    val faceCount: Int get() = centers.size / 3
    val vertexCount: Int get() = vertexPositions.size / 3
    val cornerTotal: Int get() = cornerVertex.size

    val indices: IntRange get() = 0 until faceCount

    init {
        require(centers.size % 3 == 0 && vertexPositions.size % 3 == 0) { "position arrays must hold x, y, z triples" }
        require(cornerStart.size == faceCount + 1) { "cornerStart must have faceCount + 1 entries" }
        require(cornerStart[faceCount] == cornerVertex.size && cornerVertex.size == neighbors.size) {
            "per-corner arrays must match cornerStart's total"
        }
    }

    fun cornerCount(face: Int): Int = cornerStart[face + 1] - cornerStart[face]

    fun centerX(face: Int): Float = centers[face * 3]
    fun centerY(face: Int): Float = centers[face * 3 + 1]
    fun centerZ(face: Int): Float = centers[face * 3 + 2]

    /** [corner] is the index within the face, `0 until cornerCount(face)`. */
    fun cornerX(face: Int, corner: Int): Float = vertexPositions[cornerVertex[cornerStart[face] + corner] * 3]
    fun cornerY(face: Int, corner: Int): Float = vertexPositions[cornerVertex[cornerStart[face] + corner] * 3 + 1]
    fun cornerZ(face: Int, corner: Int): Float = vertexPositions[cornerVertex[cornerStart[face] + corner] * 3 + 2]

    fun neighbor(face: Int, corner: Int): Int = neighbors[cornerStart[face] + corner]

    inline fun forEachNeighbor(face: Int, action: (neighbor: Int) -> Unit) {
        for (k in cornerStart[face] until cornerStart[face + 1]) action(neighbors[k])
    }

    inline fun anyNeighbor(face: Int, predicate: (neighbor: Int) -> Boolean): Boolean {
        for (k in cornerStart[face] until cornerStart[face + 1]) if (predicate(neighbors[k])) return true
        return false
    }

    inline fun countNeighbors(face: Int, predicate: (neighbor: Int) -> Boolean): Int {
        var count = 0
        for (k in cornerStart[face] until cornerStart[face + 1]) if (predicate(neighbors[k])) count++
        return count
    }

    /** The neighbor with the smallest [selector] value (first one on ties), or -1 if the face has none. */
    inline fun minNeighborBy(face: Int, selector: (neighbor: Int) -> Float): Int {
        var best = -1
        var bestValue = Float.POSITIVE_INFINITY
        for (k in cornerStart[face] until cornerStart[face + 1]) {
            val n = neighbors[k]
            val value = selector(n)
            if (best == -1 || value < bestValue) {
                best = n
                bestValue = value
            }
        }
        return best
    }
}
