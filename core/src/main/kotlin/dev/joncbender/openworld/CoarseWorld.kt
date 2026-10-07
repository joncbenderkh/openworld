package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere

/**
 * The far level of the globe's level of detail: a coarser sphere whose tiles take their
 * biome from the fine tiles they contain. At the default zoom a fine tile at 5x the tile
 * count is ~2 px wide, so drawing every one is overdraw; this coarse world is what is
 * drawn there, and the fine world remains the real one - generation, the cache, resources
 * and tap-picking never see it.
 *
 * Each fine tile belongs to the coarse tile whose center is nearest to its own (found by
 * walking the coarse neighbor lists, not by any dependence on how either sphere was built).
 * A coarse tile's biome is a weighted vote of the fine tiles it contains, with thin
 * features given extra weight so they survive the merge: a one-tile-wide river covers only
 * a couple of the ~5 fine tiles in a coarse tile and would otherwise lose every vote.
 */
object CoarseWorldBuilder {

    /** The coarse frequency the game renders its far level at: 396,012 tiles, today's proven look. */
    const val COARSE_FREQUENCY = 199

    /**
     * A biome's votes per fine tile. Most terrain counts once; features that are only a
     * tile or two wide count more, so a coarse tile with a couple of river tiles in it shows
     * as river rather than as whatever surrounds them. Tunable: the higher the weight, the
     * chunkier those features look when zoomed out.
     */
    private fun weightOf(biome: Biome): Int = when (biome) {
        Biome.RIVER, Biome.LAKE -> 2
        Biome.VOLCANO -> 3
        else -> 1
    }

    private val BIOMES = Biome.entries.toTypedArray()

    /** Builds the coarse world for [fine] at [coarseFrequency]. */
    fun build(fine: SphereWorld, coarseFrequency: Int = COARSE_FREQUENCY): SphereWorld {
        val perf = PerfTimer()
        val coarseSphere = GeodesicSphere.generate(coarseFrequency)
        perf.lap("CoarseWorld.sphere")
        val parent = nearestFaces(fine.sphere, coarseSphere)
        perf.lap("CoarseWorld.map")
        val biomes = vote(parent, fine, coarseSphere)
        perf.lap("CoarseWorld.vote")
        return SphereWorld(coarseSphere, biomes)
    }

    /**
     * For every face of [from], the index of the face of [to] whose center is nearest to its
     * own (the largest dot product between unit vectors). Faces are processed in index order,
     * each seeded from the previous face's answer - consecutive faces are almost always
     * neighbors - so each needs only a short walk; each parallel chunk starts from an exact
     * brute-force lookup.
     */
    internal fun nearestFaces(from: Sphere, to: Sphere): IntArray {
        val result = IntArray(from.faceCount)
        Parallel.forRanges(from.faceCount) { lo, hi ->
            var current = bruteForceNearest(to, from.centerX(lo), from.centerY(lo), from.centerZ(lo))
            for (i in lo until hi) {
                current = descend(to, current, from.centerX(i), from.centerY(i), from.centerZ(i))
                result[i] = current
            }
        }
        return result
    }

    private fun dot(sphere: Sphere, face: Int, x: Float, y: Float, z: Float): Float =
        sphere.centerX(face) * x + sphere.centerY(face) * y + sphere.centerZ(face) * z

    private fun bruteForceNearest(sphere: Sphere, x: Float, y: Float, z: Float): Int {
        var best = 0
        var bestDot = -2f
        for (face in sphere.indices) {
            val d = dot(sphere, face, x, y, z)
            if (d > bestDot) {
                bestDot = d
                best = face
            }
        }
        return best
    }

    /**
     * Walks from [start] to the face nearest the point, moving to the best of the current
     * face's neighbors and *their* neighbors until nothing is closer. Looking two rings out
     * (rather than one) guards against stopping at a local maximum on a tiling that is only
     * approximately Delaunay.
     */
    private fun descend(sphere: Sphere, start: Int, x: Float, y: Float, z: Float): Int {
        var current = start
        var currentDot = dot(sphere, current, x, y, z)
        while (true) {
            var best = current
            var bestDot = currentDot
            sphere.forEachNeighbor(current) { n ->
                val dn = dot(sphere, n, x, y, z)
                if (dn > bestDot) { bestDot = dn; best = n }
                sphere.forEachNeighbor(n) { m ->
                    val dm = dot(sphere, m, x, y, z)
                    if (dm > bestDot) { bestDot = dm; best = m }
                }
            }
            if (best == current) return current
            current = best
            currentDot = bestDot
        }
    }

    /**
     * Each coarse face's biome: the one with the most weighted votes among the fine faces
     * that map to it (ties go to the lowest biome ordinal, so the result is deterministic).
     * Votes are tallied in one byte per (face, biome), saturating - a coarse face holds only
     * a handful of fine faces - to stay small at five times the tile count.
     */
    internal fun vote(parent: IntArray, fine: SphereWorld, coarse: Sphere): ByteArray {
        val biomeCount = BIOMES.size
        val tally = ByteArray(coarse.faceCount * biomeCount)
        for (i in parent.indices) {
            val biome = fine[i]
            val index = parent[i] * biomeCount + biome.ordinal
            tally[index] = minOf(tally[index] + weightOf(biome), Byte.MAX_VALUE.toInt()).toByte()
        }

        val result = ByteArray(coarse.faceCount)
        val undecided = ArrayList<Int>()
        for (face in coarse.indices) {
            var best = -1
            var bestVotes = 0
            for (b in 0 until biomeCount) {
                val votes = tally[face * biomeCount + b].toInt()
                if (votes > bestVotes) {
                    bestVotes = votes
                    best = b
                }
            }
            if (best == -1) undecided.add(face) else result[face] = best.toByte()
        }

        // A coarse face no fine face maps to (not expected when the fine sphere is finer,
        // but cheap to be safe about): take the most common biome among its decided neighbors.
        val decided = BooleanArray(coarse.faceCount) { true }
        for (face in undecided) decided[face] = false
        for (face in undecided) {
            val neighborVotes = IntArray(biomeCount)
            coarse.forEachNeighbor(face) { n -> if (decided[n]) neighborVotes[result[n].toInt()]++ }
            var best = Biome.OCEAN.ordinal
            var bestVotes = 0
            for (b in 0 until biomeCount) {
                if (neighborVotes[b] > bestVotes) {
                    bestVotes = neighborVotes[b]
                    best = b
                }
            }
            result[face] = best.toByte()
        }
        return result
    }
}
