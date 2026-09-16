package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorMountainTest {

    @Test
    fun `mountains cover roughly the real-world share of land, across several seeds`() {
        // Regression test: the elevation fBm's practical range fell well short
        // of the old mountain threshold (0.80), so mountains were all but
        // impossible (0% of tiles in 9 of 10 seeds manually checked) despite
        // MOUNTAIN being a real, reachable branch in the biome `when`. The
        // threshold was retuned to target real-world mountainous land
        // coverage (~24%) - assert every one of several seeds lands in a
        // generous band around that, not just "mountains are nonzero
        // somewhere in some seed."
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 30)
            val land = world.faces.indices.count { world[it] != Biome.OCEAN }
            val mountain = world.faces.indices.count { world[it] == Biome.MOUNTAIN }
            val mountainShareOfLand = mountain.toFloat() / land
            assertTrue(
                mountainShareOfLand in 0.15f..0.35f,
                "seed $seed: mountain is ${mountainShareOfLand * 100}% of land, expected ~15-35% (targeting real-world's ~24%)",
            )
        }
    }
}
