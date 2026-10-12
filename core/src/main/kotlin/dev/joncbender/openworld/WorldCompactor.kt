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
        val plan = CompactPlan(full, coarse, rings)

        val tileCount = plan.tileCount
        val centers = FloatArray(tileCount * 3).also { fill(it, plan::emitCenters) }
        val cornerStart = IntArray(tileCount + 1).also { fill(it, plan::emitCornerStart) }
        val cornerVertex = IntArray(plan.cornerTotal).also { fill(it, plan::emitCornerVertex) }
        val vertexPositions = FloatArray(plan.vertexCount * 3).also { fill(it, plan::emitVertexPositions) }
        val neighborStart = IntArray(tileCount + 1).also { fill(it, plan::emitNeighborStart) }
        val neighbors = IntArray(plan.neighborTotal).also { fill(it, plan::emitNeighbors) }
        perf.lap("compact.geometry")

        val world = SphereWorld(
            Sphere(centers, cornerStart, cornerVertex, vertexPositions, neighbors, neighborStart),
            ByteArray(tileCount) { plan.biome(it).ordinal.toByte() },
        )
        for (t in 0 until plan.fineCount) {
            val resources = plan.resources(t)
            if (resources.isNotEmpty()) world.setResources(t, resources)
        }
        perf.lap("compact.world")
        return CompactWorld(world, plan.fineCount, plan.sourceFace, plan.coarseFace)
    }

    private fun fill(target: FloatArray, emit: (FloatSink) -> Unit) {
        var next = 0
        emit(FloatSink { target[next++] = it })
        check(next == target.size) { "emitted $next values for ${target.size} slots" }
    }

    private fun fill(target: IntArray, emit: (IntSink) -> Unit) {
        var next = 0
        emit(IntSink { target[next++] = it })
        check(next == target.size) { "emitted $next values for ${target.size} slots" }
    }
}
