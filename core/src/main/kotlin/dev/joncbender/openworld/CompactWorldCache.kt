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
                if (!layout.isSane || layout.totalBytes != file.length()) return null

                val chunk = ByteArray(CHUNK_BYTES)
                val centers = WorldCache.readFloats(input, layout.tileCount * 3, chunk)
                val cornerStart = WorldCache.readInts(input, layout.tileCount + 1, chunk)
                val cornerVertex = WorldCache.readInts(input, layout.cornerTotal, chunk)
                val vertexPositions = WorldCache.readFloats(input, layout.vertexCount * 3, chunk)
                val neighborStart = WorldCache.readInts(input, layout.tileCount + 1, chunk)
                val neighbors = WorldCache.readInts(input, layout.neighborTotal, chunk)
                val sourceFace = WorldCache.readInts(input, layout.fineCount, chunk)
                val coarseFace = WorldCache.readInts(input, layout.oceanCount, chunk)
                val biomeBytes = ByteArray(layout.tileCount).also { input.readFully(it) }
                val resourceCounts = ByteArray(layout.fineCount).also { input.readFully(it) }
                val resourceBytes = ByteArray(layout.resourceTotal).also { input.readFully(it) }

                if (biomeBytes.any { it < 0 || it >= Biome.entries.size }) return null
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
     * Compacts [full] and writes the result to [file], without ever building it in memory.
     * Best-effort: a failed write shouldn't crash or block gameplay, just means the next launch
     * regenerates.
     */
    fun save(
        file: FileHandle,
        full: SphereWorld,
        frequency: Int,
        seed: Long,
        resourceDensity: Float,
        coarseFrequency: Int = WorldCompactor.COARSE_FREQUENCY,
        rings: Int = WorldCompactor.COAST_RINGS,
    ) {
        try {
            val perf = PerfTimer()
            val plan = CompactPlan(full, GeodesicSphere.generate(coarseFrequency), rings)
            var resourceTotal = 0
            for (t in 0 until plan.fineCount) resourceTotal += plan.resources(t).size
            val layout = Layout(plan.tileCount, plan.fineCount, plan.cornerTotal, plan.neighborTotal, plan.vertexCount, resourceTotal)

            file.write(false).use { out ->
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
        } catch (e: Exception) {
            // Ignored - see doc comment above.
        }
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
