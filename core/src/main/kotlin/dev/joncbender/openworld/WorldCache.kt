package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import dev.joncbender.openworld.geo.Sphere
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
 * a single contiguous column and moved with one bulk copy through a typed
 * buffer view: no per-tile objects are created or parsed on load.
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
            val bytes = file.readBytes()
            perf.lap("cache.load.readFile")
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (header.getInt(0) != FORMAT_VERSION) return null
            if (header.getInt(4) != frequency) return null
            if (header.getLong(8) != seed) return null
            if (header.getFloat(16) != resourceDensity) return null
            val layout = Layout(header.getInt(20), header.getInt(24), header.getInt(28), header.getInt(32))
            if (layout.totalBytes != bytes.size.toLong()) return null
            readWorld(bytes, layout).also { perf.lap("cache.load.decode") }
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
            val bytes = ByteArray(layout.totalBytes.toInt())

            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0, FORMAT_VERSION)
            header.putInt(4, frequency)
            header.putLong(8, seed)
            header.putFloat(16, resourceDensity)
            header.putInt(20, layout.faceCount)
            header.putInt(24, layout.cornerTotal)
            header.putInt(28, layout.vertexCount)
            header.putInt(32, layout.resourceTotal)

            putFloats(bytes, layout.centers, sphere.centers)
            putInts(bytes, layout.cornerStart, sphere.cornerStart)
            putInts(bytes, layout.cornerVertex, sphere.cornerVertex)
            putFloats(bytes, layout.vertexPositions, sphere.vertexPositions)
            putInts(bytes, layout.neighbors, sphere.neighbors)

            var resourceOffset = layout.resources
            for (i in 0 until faceCount) {
                bytes[layout.biomes + i] = world[i].ordinal.toByte()
                val resources = world.resourcesAt(i)
                bytes[layout.resourceCounts + i] = resources.size.toByte()
                for (resource in resources) bytes[resourceOffset++] = resource.ordinal.toByte()
            }

            perf.lap("cache.save.encode")
            file.writeBytes(bytes, false)
            perf.lap("cache.save.writeFile")
        } catch (e: Exception) {
            // Ignored - see doc comment above.
        }
    }

    private fun readWorld(bytes: ByteArray, layout: Layout): SphereWorld {
        val sphere = Sphere(
            centers = getFloats(bytes, layout.centers, layout.faceCount * 3),
            cornerStart = getInts(bytes, layout.cornerStart, layout.faceCount + 1),
            cornerVertex = getInts(bytes, layout.cornerVertex, layout.cornerTotal),
            vertexPositions = getFloats(bytes, layout.vertexPositions, layout.vertexCount * 3),
            neighbors = getInts(bytes, layout.neighbors, layout.cornerTotal),
        )

        val biomes = Array(layout.faceCount) { Biome.entries[bytes[layout.biomes + it].toInt()] }
        val world = SphereWorld(sphere, biomes)

        var resourceOffset = layout.resources
        for (i in 0 until layout.faceCount) {
            val count = bytes[layout.resourceCounts + i].toInt()
            if (count == 0) continue
            world.setResources(i, List(count) { Resource.entries[bytes[resourceOffset++].toInt()] })
        }
        require(resourceOffset == layout.resources + layout.resourceTotal) { "per-face resource counts disagree with header total" }
        return world
    }

    // ByteBuffer.wrap(array, offset, length) positions the buffer at `offset`,
    // so the typed view starts there without calling ByteBuffer.position(int) -
    // compiled against JDK 9+ that resolves to a covariant override missing
    // from Android's ByteBuffer before API 33.
    private fun getFloats(bytes: ByteArray, offset: Int, count: Int) =
        FloatArray(count).also { ByteBuffer.wrap(bytes, offset, count * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

    private fun getInts(bytes: ByteArray, offset: Int, count: Int) =
        IntArray(count).also { ByteBuffer.wrap(bytes, offset, count * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }

    private fun putFloats(bytes: ByteArray, offset: Int, values: FloatArray) {
        ByteBuffer.wrap(bytes, offset, values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(values)
    }

    private fun putInts(bytes: ByteArray, offset: Int, values: IntArray) {
        ByteBuffer.wrap(bytes, offset, values.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(values)
    }

    /**
     * Byte offset of each column, derived purely from the four header counts.
     * Computed in Long so a corrupt header can't overflow into a plausible-
     * looking size; offsets are only narrowed to Int once [totalBytes] has
     * been checked against the real file length (which is itself an Int).
     */
    private class Layout(val faceCount: Int, val cornerTotal: Int, val vertexCount: Int, val resourceTotal: Int) {
        private val centersAt = HEADER_BYTES.toLong()
        private val cornerStartAt = centersAt + faceCount.toLong() * 12
        private val cornerVertexAt = cornerStartAt + (faceCount.toLong() + 1) * 4
        private val vertexPositionsAt = cornerVertexAt + cornerTotal.toLong() * 4
        private val neighborsAt = vertexPositionsAt + vertexCount.toLong() * 12
        private val biomesAt = neighborsAt + cornerTotal.toLong() * 4
        private val resourceCountsAt = biomesAt + faceCount
        private val resourcesAt = resourceCountsAt + faceCount

        val totalBytes: Long = resourcesAt + resourceTotal

        val centers get() = centersAt.toInt()
        val cornerStart get() = cornerStartAt.toInt()
        val cornerVertex get() = cornerVertexAt.toInt()
        val vertexPositions get() = vertexPositionsAt.toInt()
        val neighbors get() = neighborsAt.toInt()
        val biomes get() = biomesAt.toInt()
        val resourceCounts get() = resourceCountsAt.toInt()
        val resources get() = resourcesAt.toInt()
    }
}
