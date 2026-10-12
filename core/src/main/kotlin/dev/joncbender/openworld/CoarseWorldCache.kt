package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Persists just the coarse world's biomes (one byte per coarse tile, ~400 KB) next to the full
 * world cache. Building them needs the fine world and a coarse sphere in memory together; with
 * them cached, a warm start can build the far layer first, while the heap is still empty, and
 * only then load the ~175 MB fine world - no overlap, and no mapping or voting to redo.
 *
 * Keyed on everything the biomes depend on: the fine and coarse frequencies, the seed (resource
 * density only affects resources, never biomes), and [CoarseWorldBuilder.ALGORITHM_VERSION].
 * Anything else, or a damaged file, is a miss, and the caller just recomputes.
 */
object CoarseWorldCache {
    private const val FORMAT_VERSION = 1
    private const val HEADER_BYTES = 4 + 4 + 4 + 4 + 8 + 4

    fun load(file: FileHandle, fineFrequency: Int, coarseFrequency: Int, seed: Long): ByteArray? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.read()).use { input ->
                if (input.readInt() != FORMAT_VERSION) return null
                if (input.readInt() != CoarseWorldBuilder.ALGORITHM_VERSION) return null
                if (input.readInt() != fineFrequency) return null
                if (input.readInt() != coarseFrequency) return null
                if (input.readLong() != seed) return null
                val count = input.readInt()
                if (count != 10 * coarseFrequency * coarseFrequency + 2) return null
                if (file.length() != HEADER_BYTES.toLong() + count) return null
                val biomes = ByteArray(count).also { input.readFully(it) }
                if (biomes.any { it < 0 || it >= Biome.entries.size }) return null
                biomes
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Replaces [file] atomically (see [CacheFiles.writeAtomically]); returns whether it was written. */
    fun save(file: FileHandle, biomes: ByteArray, fineFrequency: Int, coarseFrequency: Int, seed: Long): Boolean =
        CacheFiles.writeAtomically(file) { out ->
            DataOutputStream(out).apply {
                writeInt(FORMAT_VERSION)
                writeInt(CoarseWorldBuilder.ALGORITHM_VERSION)
                writeInt(fineFrequency)
                writeInt(coarseFrequency)
                writeLong(seed)
                writeInt(biomes.size)
                write(biomes)
                flush()
            }
        }
}
