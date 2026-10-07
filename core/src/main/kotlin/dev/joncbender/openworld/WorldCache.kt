package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import dev.joncbender.openworld.geo.Sphere
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Caches a generated [SphereWorld] to disk so a subsequent launch with the
 * same frequency/seed/resource density can load it instead of regenerating
 * it - at the game's current tile count (~396k), generation takes seconds
 * even after several rounds of optimization, almost all of it building
 * sphere topology and hydrology that's wasted work if nothing about the
 * world actually changed since last time.
 *
 * A plain binary format with no generic (de)serialization library - there
 * isn't one in this project's dependency set already. The world is already a
 * handful of flat primitive arrays (see [Sphere]), so each one is stored as
 * a single contiguous column. Columns are moved through one small reusable
 * buffer straight to or from the arrays themselves, so a save or load never
 * holds a second, file-sized copy of the world in memory (~190 MB at five
 * times the current tile count, enough on its own to exhaust the Java heap).
 *
 * Layout (little-endian, the native order on every Android ABI), after the
 * header: face centers (3 floats per face), cornerStart (faceCount + 1
 * ints), cornerVertex (1 int per corner slot), vertex positions (3 floats
 * per vertex), neighbors (1 int per corner slot), biomes (1 byte per face),
 * resource counts (1 byte per face), resources (1 byte each).
 */
object WorldCache {
    private const val FORMAT_VERSION = 3
    private const val HEADER_BYTES = 36

    // A multiple of 4, so a float or int never straddles two chunks.
    private const val CHUNK_BYTES = 256 * 1024

    /**
     * Returns the cached world if [file] holds one matching [frequency],
     * [seed], and [resourceDensity] exactly - or null on any mismatch,
     * missing file, or read failure (a stale format from an older app
     * version included), in which case the caller should regenerate.
     */
    fun load(file: FileHandle, frequency: Int, seed: Long, resourceDensity: Float): SphereWorld? {
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
                val layout = Layout(header.getInt(20), header.getInt(24), header.getInt(28), header.getInt(32))
                if (layout.hasNegativeCount || layout.totalBytes != file.length()) return null

                val chunk = ByteArray(CHUNK_BYTES)
                val sphere = Sphere(
                    centers = readFloats(input, layout.faceCount * 3, chunk),
                    cornerStart = readInts(input, layout.faceCount + 1, chunk),
                    cornerVertex = readInts(input, layout.cornerTotal, chunk),
                    vertexPositions = readFloats(input, layout.vertexCount * 3, chunk),
                    neighbors = readInts(input, layout.cornerTotal, chunk),
                )
                val biomeBytes = ByteArray(layout.faceCount).also { input.readFully(it) }
                val resourceCounts = ByteArray(layout.faceCount).also { input.readFully(it) }
                val resourceBytes = ByteArray(layout.resourceTotal).also { input.readFully(it) }

                val world = SphereWorld(sphere, Array(layout.faceCount) { Biome.entries[biomeBytes[it].toInt()] })
                var resourceOffset = 0
                for (i in 0 until layout.faceCount) {
                    val count = resourceCounts[i].toInt()
                    if (count == 0) continue
                    world.setResources(i, List(count) { Resource.entries[resourceBytes[resourceOffset++].toInt()] })
                }
                require(resourceOffset == layout.resourceTotal) { "per-face resource counts disagree with header total" }
                perf.lap("cache.load.stream")
                world
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: a failed write shouldn't crash or block gameplay, just means the next launch regenerates. */
    fun save(file: FileHandle, world: SphereWorld, frequency: Int, seed: Long, resourceDensity: Float) {
        try {
            val perf = PerfTimer()
            val sphere = world.sphere
            val faceCount = sphere.faceCount
            var resourceTotal = 0
            for (i in 0 until faceCount) resourceTotal += world.resourcesAt(i).size
            val layout = Layout(faceCount, sphere.cornerTotal, sphere.vertexCount, resourceTotal)

            file.write(false).use { out ->
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(0, FORMAT_VERSION)
                header.putInt(4, frequency)
                header.putLong(8, seed)
                header.putFloat(16, resourceDensity)
                header.putInt(20, layout.faceCount)
                header.putInt(24, layout.cornerTotal)
                header.putInt(28, layout.vertexCount)
                header.putInt(32, layout.resourceTotal)
                out.write(header.array())

                val chunk = ByteArray(CHUNK_BYTES)
                writeFloats(out, sphere.centers, chunk)
                writeInts(out, sphere.cornerStart, chunk)
                writeInts(out, sphere.cornerVertex, chunk)
                writeFloats(out, sphere.vertexPositions, chunk)
                writeInts(out, sphere.neighbors, chunk)

                val sink = ByteSink(out, chunk)
                for (i in 0 until faceCount) sink.put(world[i].ordinal.toByte())
                for (i in 0 until faceCount) sink.put(world.resourcesAt(i).size.toByte())
                for (i in 0 until faceCount) for (resource in world.resourcesAt(i)) sink.put(resource.ordinal.toByte())
                sink.flush()
            }
            perf.lap("cache.save.stream")
        } catch (e: Exception) {
            // Ignored - see doc comment above.
        }
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

    private fun writeFloats(out: OutputStream, values: FloatArray, chunk: ByteArray) {
        var done = 0
        while (done < values.size) {
            val n = minOf(values.size - done, chunk.size / Float.SIZE_BYTES)
            ByteBuffer.wrap(chunk, 0, n * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(values, done, n)
            out.write(chunk, 0, n * Float.SIZE_BYTES)
            done += n
        }
    }

    private fun writeInts(out: OutputStream, values: IntArray, chunk: ByteArray) {
        var done = 0
        while (done < values.size) {
            val n = minOf(values.size - done, chunk.size / Int.SIZE_BYTES)
            ByteBuffer.wrap(chunk, 0, n * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(values, done, n)
            out.write(chunk, 0, n * Int.SIZE_BYTES)
            done += n
        }
    }

    /** Batches single bytes into [buffer]-sized writes. Call [flush] when done. */
    private class ByteSink(private val out: OutputStream, private val buffer: ByteArray) {
        private var used = 0

        fun put(b: Byte) {
            if (used == buffer.size) flush()
            buffer[used++] = b
        }

        fun flush() {
            out.write(buffer, 0, used)
            used = 0
        }
    }

    /**
     * The file's total size, derived purely from the four header counts and
     * computed in Long so a corrupt header can't overflow into a plausible-
     * looking size.
     */
    private class Layout(val faceCount: Int, val cornerTotal: Int, val vertexCount: Int, val resourceTotal: Int) {
        val hasNegativeCount: Boolean get() = faceCount < 0 || cornerTotal < 0 || vertexCount < 0 || resourceTotal < 0

        val totalBytes: Long = HEADER_BYTES.toLong() +
            faceCount.toLong() * 12 + // centers
            (faceCount.toLong() + 1) * 4 + // cornerStart
            cornerTotal.toLong() * 4 + // cornerVertex
            vertexCount.toLong() * 12 + // vertex positions
            cornerTotal.toLong() * 4 + // neighbors
            faceCount.toLong() * 2 + // biomes + resource counts
            resourceTotal
    }
}
