package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `assigned resources always match their tile's biome and have no duplicates`() {
        for (seed in 0L until 5L) {
            val world = WorldGenerator(seed).generate(frequency = 12, resourceDensity = 0.5f)
            for (i in world.faces.indices) {
                val resources = world.resourcesAt(i)
                val allowed = BIOME_RESOURCES.getValue(world[i])
                for (resource in resources) {
                    assertTrue(resource in allowed, "seed $seed: face $i is ${world[i]} but has resource $resource")
                }
                assertEquals(resources.distinct(), resources, "seed $seed: face $i has duplicate resources: $resources")
            }
        }
    }

    @Test
    fun `a single-resource biome's density roughly matches the requested fraction`() {
        // Arctic only ever offers one resource (ice crystals), so - unlike a
        // multi-resource biome - the "any resource" fraction should track the
        // requested density directly, same as the old single-roll model did.
        val world = WorldGenerator(seed = 42L).generate(frequency = 30, resourceDensity = 0.20f)
        val arcticTiles = world.faces.indices.filter { world[it] == Biome.ARCTIC }
        assertTrue(arcticTiles.size > 20, "need enough arctic tiles to measure density meaningfully")
        val withResource = arcticTiles.count { world.resourcesAt(it).isNotEmpty() }
        val actualDensity = withResource.toFloat() / arcticTiles.size
        assertTrue(actualDensity in 0.10f..0.30f, "requested 20% density, got ${actualDensity * 100}%")
    }

    @Test
    fun `forest tiles can end up with both wood and game`() {
        // High density specifically so this is reliably observable from one
        // generated world rather than needing to search many seeds.
        val world = WorldGenerator(seed = 1L).generate(frequency = 30, resourceDensity = 0.6f)
        val hasBoth = world.faces.indices.any { i ->
            world[i] == Biome.FOREST &&
                Resource.WOOD in world.resourcesAt(i) &&
                Resource.GAME in world.resourcesAt(i)
        }
        assertTrue(hasBoth, "expected at least one forest tile with both wood and game at high density")
    }

    @Test
    fun `zero density means no resources at all`() {
        val world = WorldGenerator(seed = 7L).generate(frequency = 12, resourceDensity = 0f)
        val anyResource = world.faces.indices.any { world.resourcesAt(it).isNotEmpty() }
        assertFalse(anyResource)
    }
}
