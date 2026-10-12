package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere

/**
 * A world whose open ocean is made of a few large tiles instead of many fine ones.
 *
 * Tile ids `0 until fineCount` are the fine tiles that were kept, in their original order;
 * ids `fineCount until tileCount` are coarse ocean tiles. [world] holds both, so a tile's
 * biome, resources, corners and neighbors are read the usual way. An ocean tile has the
 * corners of the coarse tile it came from but any number of neighbors (see [Sphere.neighborStart]).
 */
class CompactWorld(
    val world: SphereWorld,
    val fineCount: Int,
    /** For each fine tile, its tile in the world it was compacted from. */
    val sourceFace: IntArray,
    /** For each ocean tile (index `tile - fineCount`), its tile on the coarse sphere. */
    val coarseFace: IntArray,
) {
    val tileCount: Int get() = fineCount + coarseFace.size
    val oceanCount: Int get() = coarseFace.size

    fun isOpenOcean(tile: Int): Boolean = tile >= fineCount
}

/**
 * Replaces open ocean with coarse tiles. About 60-70% of a world's tiles are ocean far from
 * any land, where one big tile says what thousands of small ones do.
 *
 * Hexagons don't nest, so the two sizes meet along the borders of the coarse tiles: each fine
 * tile belongs to the coarse tile whose center is nearest to its own, and a coarse tile
 * becomes an ocean tile only if none of its fine tiles is worth keeping (see [DeepOcean]).
 * Every other fine tile stays, even deep water in a coarse tile that also holds coast, since
 * it belongs to no ocean tile. Where a kept tile's neighbor was dropped, its neighbor is
 * the ocean tile that swallowed it, and that ocean tile lists the kept tile in return.
 *
 * Kept tiles keep their biome and resources exactly: this runs on a finished world.
 */
object WorldCompactor {

    /** Coarse tiles are 43,562 over the sphere, leaving ~41% of a 5x world's tiles. */
    const val COARSE_FREQUENCY = 66

    /** Fine tiles kept around land, matching how far the near layer draws. */
    const val COAST_RINGS = 3

    fun compact(full: SphereWorld, coarseFrequency: Int = COARSE_FREQUENCY, rings: Int = COAST_RINGS): CompactWorld =
        compact(full, GeodesicSphere.generate(coarseFrequency), rings)

    internal fun compact(full: SphereWorld, coarse: Sphere, rings: Int): CompactWorld {
        val perf = PerfTimer()
        val fine = full.sphere
        val fineTotal = fine.faceCount

        val keep = DeepOcean.tilesToDraw(full, rings)
        val parent = CoarseWorldBuilder.nearestFaces(fine, coarse)
        perf.lap("compact.map")

        val coarseHasKept = BooleanArray(coarse.faceCount)
        for (i in 0 until fineTotal) if (keep[i]) coarseHasKept[parent[i]] = true

        // Tile ids: fine tiles that stay, then ocean tiles.
        val fineId = IntArray(fineTotal) { -1 }
        var fineCount = 0
        for (i in 0 until fineTotal) if (coarseHasKept[parent[i]]) fineId[i] = fineCount++
        val sourceFace = IntArray(fineCount)
        for (i in 0 until fineTotal) if (fineId[i] >= 0) sourceFace[fineId[i]] = i

        val oceanId = IntArray(coarse.faceCount) { -1 }
        var oceanCount = 0
        for (c in 0 until coarse.faceCount) if (!coarseHasKept[c]) oceanId[c] = fineCount + oceanCount++
        val coarseFace = IntArray(oceanCount)
        for (c in 0 until coarse.faceCount) if (oceanId[c] >= 0) coarseFace[oceanId[c] - fineCount] = c

        val tileCount = fineCount + oceanCount
        fun resolve(sourceTile: Int): Int = if (fineId[sourceTile] >= 0) fineId[sourceTile] else oceanId[parent[sourceTile]]

        // Kept fine tiles that border each ocean tile, in ascending order.
        val bordering = Array(oceanCount) { ArrayList<Int>() }
        for (t in 0 until fineCount) {
            fine.forEachNeighbor(sourceFace[t]) { n ->
                val id = resolve(n)
                if (id >= fineCount) {
                    val list = bordering[id - fineCount]
                    if (list.isEmpty() || list.last() != t) list.add(t)
                }
            }
        }
        perf.lap("compact.borders")

        // Geometry. Fine and coarse vertices are separate, so each source has its own remap.
        val centers = FloatArray(tileCount * 3)
        val cornerStart = IntArray(tileCount + 1)
        val neighborStart = IntArray(tileCount + 1)
        for (t in 0 until fineCount) {
            val source = sourceFace[t]
            cornerStart[t + 1] = cornerStart[t] + fine.cornerCount(source)
            neighborStart[t + 1] = neighborStart[t] + fine.neighborCount(source)
        }
        for (o in 0 until oceanCount) {
            val t = fineCount + o
            val c = coarseFace[o]
            var neighborCount = bordering[o].size
            coarse.forEachNeighbor(c) { n -> if (oceanId[n] >= 0) neighborCount++ }
            cornerStart[t + 1] = cornerStart[t] + coarse.cornerCount(c)
            neighborStart[t + 1] = neighborStart[t] + neighborCount
        }

        val cornerVertex = IntArray(cornerStart[tileCount])
        val neighbors = IntArray(neighborStart[tileCount])
        val fineVertexId = IntArray(fine.vertexCount) { -1 }
        val coarseVertexId = IntArray(coarse.vertexCount) { -1 }
        var vertexCount = 0
        for (t in 0 until fineCount) {
            val source = sourceFace[t]
            fine.centers.copyInto(centers, t * 3, source * 3, source * 3 + 3)
            val from = fine.cornerStart[source]
            for (k in 0 until fine.cornerCount(source)) {
                val v = fine.cornerVertex[from + k]
                if (fineVertexId[v] < 0) fineVertexId[v] = vertexCount++
                cornerVertex[cornerStart[t] + k] = fineVertexId[v]
            }
            for (k in 0 until fine.neighborCount(source)) {
                neighbors[neighborStart[t] + k] = resolve(fine.neighbor(source, k))
            }
        }
        for (o in 0 until oceanCount) {
            val t = fineCount + o
            val c = coarseFace[o]
            coarse.centers.copyInto(centers, t * 3, c * 3, c * 3 + 3)
            val from = coarse.cornerStart[c]
            for (k in 0 until coarse.cornerCount(c)) {
                val v = coarse.cornerVertex[from + k]
                if (coarseVertexId[v] < 0) coarseVertexId[v] = vertexCount++
                cornerVertex[cornerStart[t] + k] = coarseVertexId[v]
            }
            var slot = neighborStart[t]
            coarse.forEachNeighbor(c) { n -> if (oceanId[n] >= 0) neighbors[slot++] = oceanId[n] }
            for (f in bordering[o]) neighbors[slot++] = f
        }

        val vertexPositions = FloatArray(vertexCount * 3)
        for (v in 0 until fine.vertexCount) {
            val id = fineVertexId[v]
            if (id >= 0) fine.vertexPositions.copyInto(vertexPositions, id * 3, v * 3, v * 3 + 3)
        }
        for (v in 0 until coarse.vertexCount) {
            val id = coarseVertexId[v]
            if (id >= 0) coarse.vertexPositions.copyInto(vertexPositions, id * 3, v * 3, v * 3 + 3)
        }
        perf.lap("compact.geometry")

        val biomes = ByteArray(tileCount) { Biome.OCEAN.ordinal.toByte() }
        val compact = SphereWorld(Sphere(centers, cornerStart, cornerVertex, vertexPositions, neighbors, neighborStart), biomes)
        for (t in 0 until fineCount) {
            val source = sourceFace[t]
            compact[t] = full[source]
            val resources = full.resourcesAt(source)
            if (resources.isNotEmpty()) compact.setResources(t, resources)
        }
        perf.lap("compact.world")
        return CompactWorld(compact, fineCount, sourceFace, coarseFace)
    }
}
