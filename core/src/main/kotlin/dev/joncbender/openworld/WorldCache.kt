package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.Vector3
import dev.joncbender.openworld.geo.Face
import java.nio.ByteBuffer

/**
 * Caches a generated [SphereWorld] to disk so a subsequent launch with the
 * same frequency/seed/resource density can load it instead of regenerating
 * it - at the game's current tile count (~396k), generation takes upwards of
 * ten seconds even after several rounds of optimization, almost all of it
 * building sphere topology and hydrology that's wasted work if nothing about
 * the world actually changed since last time.
 *
 * A plain binary format over a single [ByteBuffer], with no generic
 * (de)serialization library - there isn't one in this project's dependency
 * set already. Fields are stored column by column (all centers, then all
 * corner counts, ...) rather than record by record, so the whole file is one
 * bulk read or write, and every face's bytes sit at an offset computable from
 * prefix sums - which lets [Parallel] fill and decode them across all cores
 * instead of pushing millions of individual values through a stream.
 *
 * Layout (big-endian), after the header: centers (3 floats per face), corner
 * counts (1 byte per face), corners (3 floats each), neighbors (1 int per
 * corner - a face always has as many neighbors as corners), biomes (1 byte
 * per face), resource counts (1 byte per face), resources (1 byte each).
 */
object WorldCache {
    private const val FORMAT_VERSION = 2
    private const val HEADER_BYTES = 32

    private const val VECTOR_BYTES = 3 * Float.SIZE_BYTES

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
            val buffer = ByteBuffer.wrap(file.readBytes())
            perf.lap("cache.load.readFile")
            if (buffer.getInt(0) != FORMAT_VERSION) return null
            if (buffer.getInt(4) != frequency) return null
            if (buffer.getLong(8) != seed) return null
            if (buffer.getFloat(16) != resourceDensity) return null
            val layout = Layout(buffer.getInt(20), buffer.getInt(24), buffer.getInt(28))
            if (layout.totalBytes != buffer.capacity().toLong()) return null
            readWorld(buffer, layout).also { perf.lap("cache.load.decode") }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: a failed write shouldn't crash or block gameplay, just means the next launch regenerates. */
    fun save(file: FileHandle, world: SphereWorld, frequency: Int, seed: Long, resourceDensity: Float) {
        try {
            val perf = PerfTimer()
            val faces = world.faces
            val cornerStart = IntArray(faces.size + 1)
            val resourceStart = IntArray(faces.size + 1)
            for (i in faces.indices) {
                cornerStart[i + 1] = cornerStart[i] + faces[i].corners.size
                resourceStart[i + 1] = resourceStart[i] + world.resourcesAt(i).size
            }
            val layout = Layout(faces.size, cornerStart[faces.size], resourceStart[faces.size])
            val buffer = ByteBuffer.allocate(layout.totalBytes.toInt())

            buffer.putInt(0, FORMAT_VERSION)
            buffer.putInt(4, frequency)
            buffer.putLong(8, seed)
            buffer.putFloat(16, resourceDensity)
            buffer.putInt(20, layout.faceCount)
            buffer.putInt(24, layout.cornerTotal)
            buffer.putInt(28, layout.resourceTotal)

            Parallel.forEachIndex(faces.size) { i ->
                val face = faces[i]
                check(face.neighbors.size == face.corners.size) { "face $i: neighbor/corner count mismatch" }
                buffer.putVector(layout.centers + i * VECTOR_BYTES, face.center)
                buffer.put(layout.cornerCounts + i, face.corners.size.toByte())
                for (c in face.corners.indices) {
                    val corner = cornerStart[i] + c
                    buffer.putVector(layout.corners + corner * VECTOR_BYTES, face.corners[c])
                    buffer.putInt(layout.neighbors + corner * Int.SIZE_BYTES, face.neighbors[c])
                }
                buffer.put(layout.biomes + i, world[i].ordinal.toByte())
                val resources = world.resourcesAt(i)
                buffer.put(layout.resourceCounts + i, resources.size.toByte())
                for (r in resources.indices) buffer.put(layout.resources + resourceStart[i] + r, resources[r].ordinal.toByte())
            }

            perf.lap("cache.save.encode")
            file.writeBytes(buffer.array(), false)
            perf.lap("cache.save.writeFile")
        } catch (e: Exception) {
            // Ignored - see doc comment above.
        }
    }

    private fun readWorld(buffer: ByteBuffer, layout: Layout): SphereWorld {
        val faceCount = layout.faceCount
        val cornerStart = IntArray(faceCount + 1)
        val resourceStart = IntArray(faceCount + 1)
        for (i in 0 until faceCount) {
            cornerStart[i + 1] = cornerStart[i] + buffer.get(layout.cornerCounts + i).toInt()
            resourceStart[i + 1] = resourceStart[i] + buffer.get(layout.resourceCounts + i).toInt()
        }
        require(cornerStart[faceCount] == layout.cornerTotal && resourceStart[faceCount] == layout.resourceTotal) {
            "per-face counts disagree with header totals"
        }

        val faces = arrayOfNulls<Face>(faceCount)
        val biomes = Array(faceCount) { Biome.OCEAN }
        Parallel.forEachIndex(faceCount) { i ->
            val cornerCount = cornerStart[i + 1] - cornerStart[i]
            val corners = ArrayList<Vector3>(cornerCount)
            val neighbors = IntArray(cornerCount)
            for (c in 0 until cornerCount) {
                val corner = cornerStart[i] + c
                corners.add(buffer.getVector(layout.corners + corner * VECTOR_BYTES))
                neighbors[c] = buffer.getInt(layout.neighbors + corner * Int.SIZE_BYTES)
            }
            faces[i] = Face(buffer.getVector(layout.centers + i * VECTOR_BYTES), corners, neighbors)
            biomes[i] = Biome.entries[buffer.get(layout.biomes + i).toInt()]
        }

        @Suppress("UNCHECKED_CAST")
        val world = SphereWorld((faces as Array<Face>).asList(), biomes)
        Parallel.forEachIndex(faceCount) { i ->
            val resourceCount = resourceStart[i + 1] - resourceStart[i]
            if (resourceCount > 0) {
                world.setResources(i, List(resourceCount) { r ->
                    Resource.entries[buffer.get(layout.resources + resourceStart[i] + r).toInt()]
                })
            }
        }
        return world
    }

    private fun ByteBuffer.putVector(offset: Int, v: Vector3) {
        putFloat(offset, v.x)
        putFloat(offset + Float.SIZE_BYTES, v.y)
        putFloat(offset + 2 * Float.SIZE_BYTES, v.z)
    }

    private fun ByteBuffer.getVector(offset: Int) =
        Vector3(getFloat(offset), getFloat(offset + Float.SIZE_BYTES), getFloat(offset + 2 * Float.SIZE_BYTES))

    /** Byte offset of each column, derived purely from the three header counts. */
    private class Layout(val faceCount: Int, val cornerTotal: Int, val resourceTotal: Int) {
        val centers = HEADER_BYTES
        val cornerCounts = centers + faceCount * VECTOR_BYTES
        val corners = cornerCounts + faceCount
        val neighbors = corners + cornerTotal * VECTOR_BYTES
        val biomes = neighbors + cornerTotal * Int.SIZE_BYTES
        val resourceCounts = biomes + faceCount
        val resources = resourceCounts + faceCount

        // A Long so a corrupt header can't overflow into a plausible-looking size.
        val totalBytes: Long = resources.toLong() + resourceTotal
    }
}
