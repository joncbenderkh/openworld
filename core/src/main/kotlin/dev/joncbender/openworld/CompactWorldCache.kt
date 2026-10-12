package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Caches a [CompactWorld] to disk, so a later launch with the same settings can load it instead
 * of regenerating it. It plays the role [WorldCache] has for a world of uniform tiles.
 *
 * [save] is handed the *full* world and writes the compact one straight to the file, a value at a
 * time through one small buffer (see [CompactPlan]): compacting in memory first would hold both
 * worlds at once, which a 256 MB heap can't. A caller saves, releases the full world, then [load]s
 * the compact one back.
 *
 * Little-endian, columnar, after the header: centers (3 floats per tile), cornerStart (tiles + 1
 * ints), cornerVertex (1 int per corner), vertex positions (3 floats per vertex), neighborStart
 * (tiles + 1 ints), neighbors (1 int each), sourceFace (1 int per fine tile), coarseFace (1 int
 * per ocean tile), biomes (1 byte per tile), resource counts (1 byte per fine tile), resources
 * (1 byte each).
 *
 * The file is keyed on everything the world depends on - fine frequency, seed, resource density,
 * the coarse frequency and coast width it was compacted with - plus [FORMAT_VERSION], which is
 * bumped for any change to the layout or to how [WorldCompactor] decides what to keep.
 */
object CompactWorldCache {
    private const val FORMAT_VERSION = 4
    private const val HEADER_BYTES = 52

    // A multiple of 4, so a float or int never straddles two chunks.
    private const val CHUNK_BYTES = 256 * 1024

    /**
     * Whether [file] looks like a cache [load] would accept - its header matches the keys and its
     * length matches the header - without reading the world. Lets a caller decide to generate
     * *before* it has spent heap on anything else.
     */
    fun isCurrent(
        file: FileHandle,
        frequency: Int,
        seed: Long,
        resourceDensity: Float,
        coarseFrequency: Int = WorldCompactor.COARSE_FREQUENCY,
        rings: Int = WorldCompactor.COAST_RINGS,
    ): Boolean {
        if (!file.exists()) return false
        return try {
            DataInputStream(file.read()).use { input ->
                val headerBytes = ByteArray(HEADER_BYTES)
                input.readFully(headerBytes)
                val layout = readLayout(ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN), frequency, seed, resourceDensity, coarseFrequency, rings)
                layout != null && layout.totalBytes == file.length()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The counts in [header], or null if it is not for the world described by the other arguments. */
    private fun readLayout(header: ByteBuffer, frequency: Int, seed: Long, resourceDensity: Float, coarseFrequency: Int, rings: Int): Layout? {
        if (header.getInt(0) != FORMAT_VERSION) return null
        if (header.getInt(4) != frequency) return null
        if (header.getLong(8) != seed) return null
        if (header.getFloat(16) != resourceDensity) return null
        if (header.getInt(20) != coarseFrequency) return null
        if (header.getInt(24) != rings) return null
        val layout = Layout(
            tileCount = header.getInt(28), fineCount = header.getInt(32), cornerTotal = header.getInt(36),
            neighborTotal = header.getInt(40), vertexCount = header.getInt(44), resourceTotal = header.getInt(48),
        )
        return if (layout.isSane) layout else null
    }

    fun load(
        file: FileHandle,
        frequency: Int,
        seed: Long,
        resourceDensity: Float,
        coarseFrequency: Int = WorldCompactor.COARSE_FREQUENCY,
        rings: Int = WorldCompactor.COAST_RINGS,
    ): CompactWorld? {
        if (!file.exists()) return null
        return try {
            val perf = PerfTimer()
            DataInputStream(file.read()).use { input ->
                val headerBytes = ByteArray(HEADER_BYTES)
                input.readFully(headerBytes)
                val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val layout = readLayout(header, frequency, seed, resourceDensity, coarseFrequency, rings) ?: return null
                if (layout.totalBytes != file.length()) return null

                val chunk = ByteArray(CHUNK_BYTES)
                val centers = readFloats(input, layout.tileCount * 3, chunk)
                val cornerStart = readInts(input, layout.tileCount + 1, chunk)
                val cornerVertex = readInts(input, layout.cornerTotal, chunk)
                val vertexPositions = readFloats(input, layout.vertexCount * 3, chunk)
                val neighborStart = readInts(input, layout.tileCount + 1, chunk)
                val neighbors = readInts(input, layout.neighborTotal, chunk)
                val sourceFace = readInts(input, layout.fineCount, chunk)
                val coarseFace = readInts(input, layout.oceanCount, chunk)
                val biomeBytes = ByteArray(layout.tileCount).also { input.readFully(it) }
                val resourceCounts = ByteArray(layout.fineCount).also { input.readFully(it) }
                val resourceBytes = ByteArray(layout.resourceTotal).also { input.readFully(it) }

                if (!isConsistent(layout, cornerStart, cornerVertex, neighborStart, neighbors, sourceFace, coarseFace, biomeBytes, resourceCounts, resourceBytes)) return null
                val sphere = Sphere(centers, cornerStart, cornerVertex, vertexPositions, neighbors, neighborStart)
                val world = SphereWorld(sphere, biomeBytes)
                var resourceOffset = 0
                for (t in 0 until layout.fineCount) {
                    val count = resourceCounts[t].toInt()
                    if (count == 0) continue
                    world.setResources(t, List(count) { Resource.entries[resourceBytes[resourceOffset++].toInt()] })
                }
                require(resourceOffset == layout.resourceTotal) { "per-tile resource counts disagree with header total" }
                perf.lap("compactCache.load.stream")
                CompactWorld(world, layout.fineCount, sourceFace, coarseFace)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compacts [full] and writes the result to [file], without ever building it in memory. The file
     * is replaced atomically (see [CacheFiles.writeAtomically]). Returns whether it was written;
     * a failure is not thrown, since the caller decides what a missing cache means.
     */
    fun save(
        file: FileHandle,
        full: SphereWorld,
        frequency: Int,
        seed: Long,
        resourceDensity: Float,
        coarseFrequency: Int = WorldCompactor.COARSE_FREQUENCY,
        rings: Int = WorldCompactor.COAST_RINGS,
    ): Boolean {
        return try {
            val perf = PerfTimer()
            val plan = CompactPlan(full, GeodesicSphere.generate(coarseFrequency), rings)
            var resourceTotal = 0
            for (t in 0 until plan.fineCount) resourceTotal += plan.resources(t).size
            val layout = Layout(plan.tileCount, plan.fineCount, plan.cornerTotal, plan.neighborTotal, plan.vertexCount, resourceTotal)

            val written = CacheFiles.writeAtomically(file) { out ->
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(0, FORMAT_VERSION)
                header.putInt(4, frequency)
                header.putLong(8, seed)
                header.putFloat(16, resourceDensity)
                header.putInt(20, coarseFrequency)
                header.putInt(24, rings)
                header.putInt(28, layout.tileCount)
                header.putInt(32, layout.fineCount)
                header.putInt(36, layout.cornerTotal)
                header.putInt(40, layout.neighborTotal)
                header.putInt(44, layout.vertexCount)
                header.putInt(48, layout.resourceTotal)
                out.write(header.array())

                val writer = ChunkWriter(out)
                plan.emitCenters(writer)
                plan.emitCornerStart(writer)
                plan.emitCornerVertex(writer)
                plan.emitVertexPositions(writer)
                plan.emitNeighborStart(writer)
                plan.emitNeighbors(writer)
                for (id in plan.sourceFace) writer.put(id)
                for (id in plan.coarseFace) writer.put(id)
                for (t in 0 until plan.tileCount) writer.putByte(plan.biome(t).ordinal.toByte())
                for (t in 0 until plan.fineCount) writer.putByte(plan.resources(t).size.toByte())
                for (t in 0 until plan.fineCount) for (resource in plan.resources(t)) writer.putByte(resource.ordinal.toByte())
                writer.flush()
            }
            perf.lap("compactCache.save.stream")
            written
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Whether every index in the arrays points somewhere real: offsets rise from 0 to the totals,
     * vertices and neighbors are in range, source and coarse faces ascend (the compactor writes
     * them in order, and [TilePicker] searches [coarseFace]), and biomes and resources are valid
     * ordinals. A file of the right length can still hold garbage; rejecting it here means a
     * regeneration rather than an index error later.
     */
    private fun isConsistent(
        layout: Layout,
        cornerStart: IntArray,
        cornerVertex: IntArray,
        neighborStart: IntArray,
        neighbors: IntArray,
        sourceFace: IntArray,
        coarseFace: IntArray,
        biomes: ByteArray,
        resourceCounts: ByteArray,
        resourceBytes: ByteArray,
    ): Boolean {
        if (!offsetsRise(cornerStart, layout.cornerTotal) || !offsetsRise(neighborStart, layout.neighborTotal)) return false
        if (cornerVertex.any { it < 0 || it >= layout.vertexCount }) return false
        if (neighbors.any { it < 0 || it >= layout.tileCount }) return false
        if (!ascendingFrom0(sourceFace) || !ascendingFrom0(coarseFace)) return false
        if (biomes.any { it < 0 || it >= Biome.entries.size }) return false
        if (resourceCounts.any { it < 0 || it > SphereWorld.MAX_RESOURCES }) return false
        return resourceBytes.none { it < 0 || it >= Resource.entries.size }
    }

    private fun offsetsRise(offsets: IntArray, total: Int): Boolean {
        if (offsets[0] != 0 || offsets[offsets.size - 1] != total) return false
        for (i in 1 until offsets.size) if (offsets[i] < offsets[i - 1]) return false
        return true
    }

    private fun ascendingFrom0(values: IntArray): Boolean {
        if (values.isEmpty()) return true
        if (values[0] < 0) return false
        for (i in 1 until values.size) if (values[i] <= values[i - 1]) return false
        return true
    }

    private fun readFloats(input: DataInputStream, count: Int, chunk: ByteArray): FloatArray {
        val out = FloatArray(count)
        var done = 0
        while (done < count) {
            val n = minOf(count - done, chunk.size / Float.SIZE_BYTES)
            input.readFully(chunk, 0, n * Float.SIZE_BYTES)
            ByteBuffer.wrap(chunk, 0, n * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, done, n)
            done += n
        }
        return out
    }

    private fun readInts(input: DataInputStream, count: Int, chunk: ByteArray): IntArray {
        val out = IntArray(count)
        var done = 0
        while (done < count) {
            val n = minOf(count - done, chunk.size / Int.SIZE_BYTES)
            input.readFully(chunk, 0, n * Int.SIZE_BYTES)
            ByteBuffer.wrap(chunk, 0, n * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out, done, n)
            done += n
        }
        return out
    }

    /** Batches values into one reusable chunk and writes it out whenever it fills. Call [flush] when done. */
    private class ChunkWriter(private val out: OutputStream) : IntSink, FloatSink {
        private val bytes = ByteArray(CHUNK_BYTES)
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        override fun put(value: Int) {
            if (buffer.remaining() < Int.SIZE_BYTES) flush()
            buffer.putInt(value)
        }

        override fun put(value: Float) {
            if (buffer.remaining() < Float.SIZE_BYTES) flush()
            buffer.putFloat(value)
        }

        fun putByte(value: Byte) {
            if (!buffer.hasRemaining()) flush()
            buffer.put(value)
        }

        fun flush() {
            out.write(bytes, 0, buffer.position())
            buffer.clear()
        }
    }

    /**
     * The file's total size, derived purely from the header counts and computed in Long so a
     * corrupt header can't overflow into a plausible-looking size.
     */
    private class Layout(
        val tileCount: Int,
        val fineCount: Int,
        val cornerTotal: Int,
        val neighborTotal: Int,
        val vertexCount: Int,
        val resourceTotal: Int,
    ) {
        val oceanCount: Int get() = tileCount - fineCount

        val isSane: Boolean
            get() = fineCount >= 0 && oceanCount >= 0 && cornerTotal >= 0 && neighborTotal >= 0 && vertexCount >= 0 && resourceTotal >= 0

        val totalBytes: Long = HEADER_BYTES.toLong() +
            tileCount.toLong() * 12 + // centers
            (tileCount.toLong() + 1) * 4 + // cornerStart
            cornerTotal.toLong() * 4 + // cornerVertex
            vertexCount.toLong() * 12 + // vertex positions
            (tileCount.toLong() + 1) * 4 + // neighborStart
            neighborTotal.toLong() * 4 + // neighbors
            tileCount.toLong() * 4 + // sourceFace + coarseFace: one int per tile
            tileCount.toLong() + // biomes
            fineCount.toLong() + // resource counts
            resourceTotal
    }
}
