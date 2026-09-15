package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceTest {

    @Test
    fun `every biome has at least one possible resource`() {
        for (biome in Biome.entries) {
            val options = BIOME_RESOURCES[biome]
            assertTrue(!options.isNullOrEmpty(), "$biome has no entry in BIOME_RESOURCES")
        }
    }

    @Test
    fun `assigned resources always match their tile's biome`() {
        for (seed in 0L until 5L) {
            val world = WorldGenerator(seed).generate(frequency = 12, resourceDensity = 0.5f)
            for (i in world.faces.indices) {
                val resource = world.resourceAt(i) ?: continue
                val allowed = BIOME_RESOURCES.getValue(world[i])
                assertTrue(resource in allowed, "seed $seed: face $i is ${world[i]} but has resource $resource")
            }
        }
    }

    @Test
    fun `resource density roughly matches the requested fraction`() {
        val world = WorldGenerator(seed = 42L).generate(frequency = 30, resourceDensity = 0.20f)
        val withResource = world.faces.indices.count { world.resourceAt(it) != null }
        val actualDensity = withResource.toFloat() / world.faces.size
        // Generous tolerance: density is a per-tile coin flip, and it's also capped
        // by whether a tile's biome even has a resource list (all of them do, but
        // this keeps the test from being flaky over the RNG alone).
        assertTrue(actualDensity in 0.10f..0.30f, "requested 20% density, got ${actualDensity * 100}%")
    }

    @Test
    fun `zero density means no resources at all`() {
        val world = WorldGenerator(seed = 7L).generate(frequency = 12, resourceDensity = 0f)
        val withResource = world.faces.indices.count { world.resourceAt(it) != null }
        assertEquals(0, withResource)
    }
}
