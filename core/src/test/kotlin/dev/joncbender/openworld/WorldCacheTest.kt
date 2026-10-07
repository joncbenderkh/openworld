package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldCacheTest {

    private fun tempFile(): FileHandle {
        val file = File.createTempFile("world_cache_test", ".bin")
        file.deleteOnExit()
        return FileHandle(file)
    }

    @Test
    fun `missing cache file returns null`() {
        val file = tempFile()
        file.delete()
        assertNull(WorldCache.load(file, frequency = 12, seed = 1L, resourceDensity = 0.18f))
    }

    @Test
    fun `saved world round-trips exactly`() {
        val file = tempFile()
        val world = WorldGenerator(seed = 5L).generate(frequency = 12, resourceDensity = 0.5f)
        WorldCache.save(file, world, frequency = 12, seed = 5L, resourceDensity = 0.5f)

        val loaded = WorldCache.load(file, frequency = 12, seed = 5L, resourceDensity = 0.5f)
        assertTrue(loaded != null)
        val a = world.sphere
        val b = loaded.sphere
        assertTrue(a.centers.contentEquals(b.centers), "centers differ")
        assertTrue(a.cornerStart.contentEquals(b.cornerStart), "cornerStart differs")
        assertTrue(a.cornerVertex.contentEquals(b.cornerVertex), "cornerVertex differs")
        assertTrue(a.vertexPositions.contentEquals(b.vertexPositions), "vertexPositions differ")
        assertTrue(a.neighbors.contentEquals(b.neighbors), "neighbors differ")
        for (i in a.indices) {
            assertEquals(world[i], loaded[i], "biome mismatch at face $i")
            assertEquals(world.resourcesAt(i), loaded.resourcesAt(i), "resources mismatch at face $i")
        }
    }

    @Test
    fun `mismatched seed misses the cache`() {
        val file = tempFile()
        val world = WorldGenerator(seed = 1L).generate(frequency = 12)
        WorldCache.save(file, world, frequency = 12, seed = 1L, resourceDensity = WorldGenerator.DEFAULT_RESOURCE_DENSITY)

        assertNull(WorldCache.load(file, frequency = 12, seed = 2L, resourceDensity = WorldGenerator.DEFAULT_RESOURCE_DENSITY))
    }

    @Test
    fun `mismatched frequency misses the cache`() {
        val file = tempFile()
        val world = WorldGenerator(seed = 1L).generate(frequency = 12)
        WorldCache.save(file, world, frequency = 12, seed = 1L, resourceDensity = WorldGenerator.DEFAULT_RESOURCE_DENSITY)

        assertNull(WorldCache.load(file, frequency = 4, seed = 1L, resourceDensity = WorldGenerator.DEFAULT_RESOURCE_DENSITY))
    }

    @Test
    fun `mismatched resource density misses the cache`() {
        val file = tempFile()
        val world = WorldGenerator(seed = 1L).generate(frequency = 12, resourceDensity = 0.2f)
        WorldCache.save(file, world, frequency = 12, seed = 1L, resourceDensity = 0.2f)

        assertNull(WorldCache.load(file, frequency = 12, seed = 1L, resourceDensity = 0.3f))
    }

    @Test
    fun `corrupt file is treated as a cache miss, not a crash`() {
        val file = tempFile()
        file.writeBytes(byteArrayOf(1, 2, 3), false)
        assertNull(WorldCache.load(file, frequency = 12, seed = 1L, resourceDensity = WorldGenerator.DEFAULT_RESOURCE_DENSITY))
    }

    @Test
    fun `a world spanning many streaming chunks round-trips exactly`() {
        // Frequency 40's corner array alone is ~385 KB, more than the cache's 256 KB chunk.
        val file = tempFile()
        val world = WorldGenerator(seed = 7L).generate(frequency = 40, resourceDensity = 0.3f)
        WorldCache.save(file, world, frequency = 40, seed = 7L, resourceDensity = 0.3f)

        val loaded = WorldCache.load(file, frequency = 40, seed = 7L, resourceDensity = 0.3f)
        assertTrue(loaded != null)
        assertTrue(world.sphere.cornerVertex.contentEquals(loaded.sphere.cornerVertex))
        assertTrue(world.sphere.neighbors.contentEquals(loaded.sphere.neighbors))
        assertTrue(world.sphere.vertexPositions.contentEquals(loaded.sphere.vertexPositions))
        for (i in world.sphere.indices) {
            assertEquals(world[i], loaded[i], "biome mismatch at face $i")
            assertEquals(world.resourcesAt(i), loaded.resourcesAt(i), "resources mismatch at face $i")
        }
    }

    @Test
    fun `a truncated or extended file is a cache miss`() {
        val file = tempFile()
        val world = WorldGenerator(seed = 3L).generate(frequency = 12)
        val density = WorldGenerator.DEFAULT_RESOURCE_DENSITY
        WorldCache.save(file, world, frequency = 12, seed = 3L, resourceDensity = density)
        val bytes = file.readBytes()

        file.writeBytes(bytes.copyOf(bytes.size - 1), false)
        assertNull(WorldCache.load(file, frequency = 12, seed = 3L, resourceDensity = density), "truncated")

        file.writeBytes(bytes + byteArrayOf(0), false)
        assertNull(WorldCache.load(file, frequency = 12, seed = 3L, resourceDensity = density), "extended")

        file.writeBytes(bytes, false)
        assertTrue(WorldCache.load(file, frequency = 12, seed = 3L, resourceDensity = density) != null, "intact file still loads")
    }
}
