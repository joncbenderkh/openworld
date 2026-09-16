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
        assertEquals(world.faces.size, loaded.faces.size)
        for (i in world.faces.indices) {
            assertEquals(world[i], loaded[i], "biome mismatch at face $i")
            assertEquals(world.resourcesAt(i), loaded.resourcesAt(i), "resources mismatch at face $i")
            assertEquals(world.faces[i].neighbors.toList(), loaded.faces[i].neighbors.toList(), "neighbors mismatch at face $i")

            val corners1 = world.faces[i].corners
            val corners2 = loaded.faces[i].corners
            assertEquals(corners1.size, corners2.size, "corner count mismatch at face $i")
            for (c in corners1.indices) {
                assertEquals(corners1[c].x, corners2[c].x, 0.0001f)
                assertEquals(corners1[c].y, corners2[c].y, 0.0001f)
                assertEquals(corners1[c].z, corners2[c].z, 0.0001f)
            }

            val center1 = world.faces[i].center
            val center2 = loaded.faces[i].center
            assertEquals(center1.x, center2.x, 0.0001f)
            assertEquals(center1.y, center2.y, 0.0001f)
            assertEquals(center1.z, center2.z, 0.0001f)
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
}
