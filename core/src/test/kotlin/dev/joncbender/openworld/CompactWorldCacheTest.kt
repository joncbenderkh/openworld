package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import dev.joncbender.openworld.geo.GeodesicSphere
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactWorldCacheTest {

    // Large enough that every column spans several 256 KB chunks, small enough to be quick.
    private val frequency = 60
    private val seed = 11L
    private val density = 0.3f
    private val coarseFrequency = 8
    private val rings = 1
    private val full = WorldGenerator(seed).generate(frequency, density)

    private fun tempFile(): FileHandle {
        val file = File.createTempFile("compact_cache_test", ".bin")
        file.deleteOnExit()
        return FileHandle(file)
    }

    private fun saved(): FileHandle = tempFile().also {
        CompactWorldCache.save(it, full, frequency, seed, density, coarseFrequency, rings)
    }

    private fun load(file: FileHandle, frequency: Int = this.frequency, seed: Long = this.seed, density: Float = this.density, coarse: Int = coarseFrequency, rings: Int = this.rings) =
        CompactWorldCache.load(file, frequency, seed, density, coarse, rings)

    @Test
    fun `missing cache file returns null`() {
        val file = tempFile()
        file.delete()
        assertNull(load(file))
    }

    @Test
    fun `a streamed save loads back exactly what compacting in memory builds`() {
        val expected = WorldCompactor.compact(full, GeodesicSphere.generate(coarseFrequency), rings)
        val loaded = assertNotNull(load(saved()))

        assertTrue(expected.oceanCount > 0 && expected.fineCount > 0)
        assertEquals(expected.fineCount, loaded.fineCount)
        assertTrue(expected.sourceFace.contentEquals(loaded.sourceFace), "sourceFace differs")
        assertTrue(expected.coarseFace.contentEquals(loaded.coarseFace), "coarseFace differs")
        val a = expected.world.sphere
        val b = loaded.world.sphere
        assertTrue(a.centers.contentEquals(b.centers), "centers differ")
        assertTrue(a.cornerStart.contentEquals(b.cornerStart), "cornerStart differs")
        assertTrue(a.cornerVertex.contentEquals(b.cornerVertex), "cornerVertex differs")
        assertTrue(a.vertexPositions.contentEquals(b.vertexPositions), "vertexPositions differ")
        assertTrue(a.neighborStart.contentEquals(b.neighborStart), "neighborStart differs")
        assertTrue(a.neighbors.contentEquals(b.neighbors), "neighbors differ")
        for (t in a.indices) {
            assertEquals(expected.world[t], loaded.world[t], "biome mismatch at tile $t")
            assertEquals(expected.world.resourcesAt(t), loaded.world.resourcesAt(t), "resources mismatch at tile $t")
        }
    }

    @Test
    fun `a mismatch in any key misses the cache`() {
        val file = saved()
        assertNotNull(load(file))
        assertNull(load(file, seed = seed + 1))
        assertNull(load(file, frequency = frequency + 1))
        assertNull(load(file, density = density + 0.1f))
        assertNull(load(file, coarse = coarseFrequency + 1))
        assertNull(load(file, rings = rings + 1))
    }

    @Test
    fun `a truncated or padded file misses the cache`() {
        val file = saved()
        val bytes = file.readBytes()
        val shorter = tempFile().also { it.writeBytes(bytes.copyOf(bytes.size - 1), false) }
        val longer = tempFile().also { it.writeBytes(bytes + byteArrayOf(0), false) }
        assertNull(load(shorter))
        assertNull(load(longer))
    }

    @Test
    fun `a stale format version misses the cache`() {
        val bytes = saved().readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        assertNull(load(tempFile().also { it.writeBytes(bytes, false) }))
    }

    @Test
    fun `a biome byte out of range misses the cache`() {
        val file = saved()
        val bytes = file.readBytes()
        // The biome column is followed by one count byte per fine tile and the resource bytes.
        val world = assertNotNull(load(file))
        var resourceBytes = 0
        for (t in 0 until world.fineCount) resourceBytes += world.world.resourcesAt(t).size
        val firstBiome = bytes.size - resourceBytes - world.fineCount - world.tileCount
        bytes[firstBiome] = Biome.entries.size.toByte()
        assertNull(load(tempFile().also { it.writeBytes(bytes, false) }))
    }

    @Test
    fun `isCurrent agrees with load on keys and length, without reading the world`() {
        val file = saved()
        assertTrue(CompactWorldCache.isCurrent(file, frequency, seed, density, coarseFrequency, rings))
        assertTrue(!CompactWorldCache.isCurrent(file, frequency, seed + 1, density, coarseFrequency, rings))
        assertTrue(!CompactWorldCache.isCurrent(file, frequency, seed, density + 0.1f, coarseFrequency, rings))
        val missing = tempFile().also { it.delete() }
        assertTrue(!CompactWorldCache.isCurrent(missing, frequency, seed, density, coarseFrequency, rings))
        val bytes = file.readBytes()
        val shorter = tempFile().also { it.writeBytes(bytes.copyOf(bytes.size - 1), false) }
        assertTrue(!CompactWorldCache.isCurrent(shorter, frequency, seed, density, coarseFrequency, rings))
    }

    @Test
    fun `an unwritable location is reported, not thrown`() {
        val written = CompactWorldCache.save(FileHandle(File("/nonexistent-dir/\u0000/cache.bin")), full, frequency, seed, density, coarseFrequency, rings)
        assertFalse(written)
    }

    @Test
    fun `a save reports success and leaves no temporary file`() {
        val file = tempFile()
        assertTrue(CompactWorldCache.save(file, full, frequency, seed, density, coarseFrequency, rings))
        assertFalse(File(file.path() + ".tmp").exists())
    }

    /** Where each column starts in a saved file, so a test can damage one. */
    private class Columns(world: CompactWorld) {
        val tiles = world.tileCount
        val fine = world.fineCount
        val ocean = world.oceanCount
        val corners = world.world.sphere.cornerTotal
        val vertices = world.world.sphere.vertexCount
        val neighbors = world.world.sphere.neighbors.size
        val resources = (0 until fine).sumOf { world.world.resourcesAt(it).size }

        val cornerStart = 52 + 12 * tiles
        val cornerVertex = cornerStart + 4 * (tiles + 1)
        val neighborStart = cornerVertex + 4 * corners + 12 * vertices
        val neighborList = neighborStart + 4 * (tiles + 1)
        val sourceFace = neighborList + 4 * neighbors
        val coarseFace = sourceFace + 4 * fine
        val biomes = coarseFace + 4 * ocean
        val resourceCounts = biomes + tiles
        val resourceList = resourceCounts + fine
    }

    private fun damaged(change: (bytes: ByteArray, at: Columns) -> Unit): FileHandle {
        val file = saved()
        val bytes = file.readBytes()
        change(bytes, Columns(assertNotNull(load(file))))
        return tempFile().also { it.writeBytes(bytes, false) }
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun ByteArray.getInt(offset: Int): Int =
        (0 until 4).sumOf { (this[offset + it].toInt() and 0xFF) shl (8 * it) }

    @Test
    fun `damage to any index column misses the cache`() {
        val cases = mapOf<String, (ByteArray, Columns) -> Unit>(
            "cornerStart does not begin at 0" to { b, c -> b.putInt(c.cornerStart, 1) },
            "cornerStart falls" to { b, c -> b.putInt(c.cornerStart + 4, -5) },
            "neighborStart falls" to { b, c -> b.putInt(c.neighborStart + 4, -5) },
            "cornerVertex too big" to { b, c -> b.putInt(c.cornerVertex, c.vertices) },
            "cornerVertex negative" to { b, c -> b.putInt(c.cornerVertex, -1) },
            "neighbor too big" to { b, c -> b.putInt(c.neighborList, c.tiles) },
            "neighbor negative" to { b, c -> b.putInt(c.neighborList, -1) },
            "sourceFace not ascending" to { b, c -> b.putInt(c.sourceFace, b.getInt(c.sourceFace + 4) + 5) },
            "coarseFace not ascending" to { b, c -> b.putInt(c.coarseFace, b.getInt(c.coarseFace + 4) + 5) },
            "resource count too big" to { b, c -> b[c.resourceCounts] = 9 },
            "resource ordinal out of range" to { b, c -> b[c.resourceList] = 99 },
        )
        assertTrue(Columns(assertNotNull(load(saved()))).let { it.fine > 1 && it.ocean > 1 && it.resources > 0 })
        for ((name, change) in cases) assertNull(load(damaged(change)), name)
    }

    @Test
    fun `an undamaged file still loads, so the damage tests are not vacuous`() {
        assertNotNull(load(damaged { _, _ -> }))
    }
}
