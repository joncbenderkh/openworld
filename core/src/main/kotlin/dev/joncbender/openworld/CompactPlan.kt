package dev.joncbender.openworld

import com.badlogic.gdx.math.Vector3
import dev.joncbender.openworld.geo.Sphere

internal fun interface IntSink { fun put(value: Int) }
internal fun interface FloatSink { fun put(value: Float) }

/** A set of indices that can also say how many members come before an index - ~1.1 bits per index. */
internal class BitRank(size: Int, isMember: (Int) -> Boolean) {
    private val bits = LongArray((size + 63) ushr 6)
    private val before = IntArray(bits.size + 1)

    init {
        for (i in 0 until size) if (isMember(i)) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
        for (w in bits.indices) before[w + 1] = before[w] + java.lang.Long.bitCount(bits[w])
    }

    val count: Int get() = before[bits.size]

    operator fun contains(index: Int): Boolean = (bits[index ushr 6] ushr (index and 63)) and 1L != 0L

    /** How many members are below [index]. */
    fun rank(index: Int): Int = before[index ushr 6] + java.lang.Long.bitCount(bits[index ushr 6] and ((1L shl (index and 63)) - 1))
}

/**
 * How [WorldCompactor] turns a full world into a [CompactWorld], without building the result.
 * The plan holds only small tables (a few bytes per source tile); each column of the compact
 * world - centers, corners, neighbors and so on - is then *emitted* a value at a time into a
 * sink, so the same code fills arrays in memory or streams straight to the cache while the
 * full world is still resident, never holding both worlds at once.
 *
 * Tile ids: kept fine tiles first, in source order, then ocean tiles in coarse-tile order.
 * Vertex ids: used fine vertices in order of [cornerSlot], then used coarse vertices in coarse order.
 *
 * The source world may have no corners at all (see [Sphere.hasCorners]): a fine tile's corners are
 * rebuilt from centers and neighbors. The corner between two consecutive neighbors is where those
 * two tiles and this one meet, so it is named by the lowest of the three tiles and its place in
 * that tile's neighbor list, which all three tiles agree on, and sits at the average of their centers.
 */
internal class CompactPlan(private val full: SphereWorld, private val coarse: Sphere, rings: Int) {
    private val fine = full.sphere
    private val parent: IntArray
    private val kept: BitRank
    private val oceanCells: BitRank
    private val fineVertices: BitRank
    private val coarseVertices: BitRank

    val fineCount: Int
    val oceanCount: Int
    val sourceFace: IntArray
    val coarseFace: IntArray

    /** Kept fine tiles that border each ocean tile, ascending: `borderList[borderStart[o] until borderStart[o + 1]]`. */
    private val borderStart: IntArray
    private val borderList: IntArray

    val tileCount: Int get() = fineCount + oceanCount
    val cornerTotal: Int
    val neighborTotal: Int
    val vertexCount: Int get() = fineVertices.count + coarseVertices.count

    init {
        val perf = PerfTimer()
        val keep = DeepOcean.tilesToDraw(full, rings)
        parent = CoarseWorldBuilder.nearestFaces(fine, coarse)
        perf.lap("compact.map")

        val coarseHasKept = BooleanArray(coarse.faceCount)
        for (i in keep.indices) if (keep[i]) coarseHasKept[parent[i]] = true
        kept = BitRank(fine.faceCount) { coarseHasKept[parent[it]] }
        oceanCells = BitRank(coarse.faceCount) { !coarseHasKept[it] }
        fineCount = kept.count
        oceanCount = oceanCells.count
        sourceFace = IntArray(fineCount)
        for (i in 0 until fine.faceCount) if (i in kept) sourceFace[kept.rank(i)] = i
        coarseFace = IntArray(oceanCount)
        for (c in 0 until coarse.faceCount) if (c in oceanCells) coarseFace[oceanCells.rank(c)] = c

        val borders = bordersOfOceanTiles()
        borderStart = borders.first
        borderList = borders.second

        fineVertices = usedVertices(fine.neighbors.size) { used ->
            for (t in 0 until fineCount) {
                val source = sourceFace[t]
                for (g in 0 until fine.neighborCount(source)) used[cornerSlot(source, g)] = true
            }
        }
        coarseVertices = usedVertices(coarse.vertexCount) { used ->
            for (o in 0 until oceanCount) {
                val c = coarseFace[o]
                for (k in coarse.cornerStart[c] until coarse.cornerStart[c + 1]) used[coarse.cornerVertex[k]] = true
            }
        }

        var corners = 0
        var neighbors = 0
        for (t in 0 until tileCount) {
            corners += cornerCount(t)
            neighbors += neighborCount(t)
        }
        cornerTotal = corners
        neighborTotal = neighbors
        perf.lap("compact.plan")
    }

    /**
     * The neighbor-list slot that names corner [g] of [tile], the one between neighbors `g - 1` and `g`:
     * a slot of the lowest of the three tiles meeting there, whichever of them asks.
     */
    private fun cornerSlot(tile: Int, g: Int): Int {
        val start = fine.neighborStart[tile]
        val degree = fine.neighborCount(tile)
        val before = fine.neighbors[start + (g + degree - 1) % degree]
        val after = fine.neighbors[start + g]
        if (tile < before && tile < after) return start + g
        val owner = minOf(before, after)
        val other = if (owner == before) after else before
        val ownerStart = fine.neighborStart[owner]
        val ownerDegree = fine.neighborCount(owner)
        for (p in 0 until ownerDegree) {
            val a = fine.neighbors[ownerStart + (p + ownerDegree - 1) % ownerDegree]
            val b = fine.neighbors[ownerStart + p]
            if ((a == tile && b == other) || (a == other && b == tile)) return ownerStart + p
        }
        error("tiles $owner, $tile and $other meet at no corner of $owner")
    }

    private inline fun usedVertices(count: Int, mark: (BooleanArray) -> Unit): BitRank {
        val used = BooleanArray(count)
        mark(used)
        return BitRank(count) { used[it] }
    }

    /**
     * The kept fine tiles bordering each ocean tile, in compressed rows: counted, then filled, with
     * a tile that touches the same ocean tile along several edges listed once.
     */
    private fun bordersOfOceanTiles(): Pair<IntArray, IntArray> {
        val start = IntArray(oceanCount + 1)
        val last = IntArray(oceanCount) { -1 }
        forEachBorder(last) { o, _ -> start[o + 1]++ }
        for (o in 0 until oceanCount) start[o + 1] += start[o]
        val list = IntArray(start[oceanCount])
        val cursor = start.copyOf(oceanCount)
        last.fill(-1)
        forEachBorder(last) { o, t -> list[cursor[o]++] = t }
        return start to list
    }

    private inline fun forEachBorder(last: IntArray, action: (ocean: Int, fineTile: Int) -> Unit) {
        for (t in 0 until fineCount) {
            fine.forEachNeighbor(sourceFace[t]) { n ->
                val id = resolve(n)
                if (id >= fineCount && last[id - fineCount] != t) {
                    last[id - fineCount] = t
                    action(id - fineCount, t)
                }
            }
        }
    }

    /** The tile a source-world tile becomes part of: itself if kept, otherwise the ocean tile covering it. */
    private fun resolve(sourceTile: Int): Int =
        if (sourceTile in kept) kept.rank(sourceTile) else fineCount + oceanCells.rank(parent[sourceTile])

    private fun oceanNeighborCount(c: Int): Int = coarse.countNeighbors(c) { it in oceanCells }

    fun cornerCount(tile: Int): Int =
        if (tile < fineCount) fine.cornerCount(sourceFace[tile]) else coarse.cornerCount(coarseFace[tile - fineCount])

    fun neighborCount(tile: Int): Int = if (tile < fineCount) {
        fine.neighborCount(sourceFace[tile])
    } else {
        val o = tile - fineCount
        oceanNeighborCount(coarseFace[o]) + borderStart[o + 1] - borderStart[o]
    }

    fun biome(tile: Int): Biome = if (tile < fineCount) full[sourceFace[tile]] else Biome.OCEAN

    fun resources(tile: Int): List<Resource> = if (tile < fineCount) full.resourcesAt(sourceFace[tile]) else emptyList()

    fun emitCenters(sink: FloatSink) {
        for (t in 0 until tileCount) {
            val source = if (t < fineCount) fine else coarse
            val face = if (t < fineCount) sourceFace[t] else coarseFace[t - fineCount]
            sink.put(source.centerX(face)); sink.put(source.centerY(face)); sink.put(source.centerZ(face))
        }
    }

    fun emitCornerStart(sink: IntSink) = emitPrefixSums(sink, ::cornerCount)

    fun emitNeighborStart(sink: IntSink) = emitPrefixSums(sink, ::neighborCount)

    private inline fun emitPrefixSums(sink: IntSink, size: (Int) -> Int) {
        var total = 0
        sink.put(0)
        for (t in 0 until tileCount) {
            total += size(t)
            sink.put(total)
        }
    }

    fun emitCornerVertex(sink: IntSink) {
        for (t in 0 until fineCount) {
            val source = sourceFace[t]
            for (g in 0 until fine.neighborCount(source)) sink.put(fineVertices.rank(cornerSlot(source, g)))
        }
        for (o in 0 until oceanCount) {
            val c = coarseFace[o]
            for (k in coarse.cornerStart[c] until coarse.cornerStart[c + 1]) {
                sink.put(fineVertices.count + coarseVertices.rank(coarse.cornerVertex[k]))
            }
        }
    }

    fun emitVertexPositions(sink: FloatSink) {
        val sum = Vector3()
        for (tile in 0 until fine.faceCount) {
            val start = fine.neighborStart[tile]
            val degree = fine.neighborCount(tile)
            for (g in 0 until degree) {
                if (start + g !in fineVertices) continue
                val a = fine.neighbors[start + (g + degree - 1) % degree]
                val b = fine.neighbors[start + g]
                // Added lowest tile first, so the sum does not depend on which tile the corner is reached from.
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                sum.set(
                    (fine.centerX(tile) + fine.centerX(lo) + fine.centerX(hi)) / 3f,
                    (fine.centerY(tile) + fine.centerY(lo) + fine.centerY(hi)) / 3f,
                    (fine.centerZ(tile) + fine.centerZ(lo) + fine.centerZ(hi)) / 3f,
                ).nor()
                sink.put(sum.x); sink.put(sum.y); sink.put(sum.z)
            }
        }
        for (v in 0 until coarse.vertexCount) {
            if (v in coarseVertices) { sink.put(coarse.vertexPositions[v * 3]); sink.put(coarse.vertexPositions[v * 3 + 1]); sink.put(coarse.vertexPositions[v * 3 + 2]) }
        }
    }

    /** Fine tiles' neighbors in source order; an ocean tile's coarse ocean neighbors, then the fine tiles bordering it. */
    fun emitNeighbors(sink: IntSink) {
        for (t in 0 until fineCount) fine.forEachNeighbor(sourceFace[t]) { sink.put(resolve(it)) }
        for (o in 0 until oceanCount) {
            coarse.forEachNeighbor(coarseFace[o]) { n -> if (n in oceanCells) sink.put(fineCount + oceanCells.rank(n)) }
            for (k in borderStart[o] until borderStart[o + 1]) sink.put(borderList[k])
        }
    }
}
