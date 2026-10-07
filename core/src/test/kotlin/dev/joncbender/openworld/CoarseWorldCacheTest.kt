package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class CoarseWorldCacheTest {

    private val coarseFrequency = 16
    private val count = 10 * coarseFrequency * coarseFrequency + 2

    private fun tempFile(): FileHandle {
        val file = File.createTempFile("coarse_cache_test", ".bin")
        file.deleteOnExit()
        return FileHandle(file)
    }

    private fun biomes() = ByteArray(count) { (it % Biome.entries.size).toByte() }

    private fun saved(): FileHandle {
        val file = tempFile()
        CoarseWorldCache.save(file, biomes(), fineFrequency = 40, coarseFrequency = coarseFrequency, seed = 7L)
        return file
    }

    @Test
    fun `saved biomes round-trip exactly`() {
        val loaded = CoarseWorldCache.load(saved(), fineFrequency = 40, coarseFrequency = coarseFrequency, seed = 7L)
        assertContentEquals(biomes(), loaded)
    }

    @Test
    fun `a missing file is a miss`() {
        val file = tempFile()
        file.delete()
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L))
    }

    @Test
    fun `a different seed, fine frequency or coarse frequency is a miss`() {
        val file = saved()
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, seed = 8L), "seed")
        assertNull(CoarseWorldCache.load(file, fineFrequency = 41, coarseFrequency = coarseFrequency, seed = 7L), "fine frequency")
        assertNull(CoarseWorldCache.load(file, fineFrequency = 40, coarseFrequency = 17, seed = 7L), "coarse frequency")
    }

    @Test
    fun `a truncated, extended or garbage file is a miss`() {
        val file = saved()
        val bytes = file.readBytes()

        file.writeBytes(bytes.copyOf(bytes.size - 1), false)
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L), "truncated")

        file.writeBytes(bytes + byteArrayOf(0), false)
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L), "extended")

        file.writeBytes(byteArrayOf(1, 2, 3), false)
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L), "garbage")
    }

    @Test
    fun `a file whose tile count does not match its frequency is a miss`() {
        val file = tempFile()
        CoarseWorldCache.save(file, ByteArray(count - 1), 40, coarseFrequency, 7L)
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L))
    }

    @Test
    fun `a cache written by a different algorithm version is a miss`() {
        val file = tempFile()
        DataOutputStream(file.write(false)).use { out ->
            out.writeInt(1) // format version, current
            out.writeInt(CoarseWorldBuilder.ALGORITHM_VERSION + 1)
            out.writeInt(40)
            out.writeInt(coarseFrequency)
            out.writeLong(7L)
            out.writeInt(count)
            out.write(biomes())
        }
        assertNull(CoarseWorldCache.load(file, 40, coarseFrequency, 7L))
    }
}
