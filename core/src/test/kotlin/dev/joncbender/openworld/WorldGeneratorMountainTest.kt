package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorMountainTest {

    @Test
    fun `mountains cover a modest share of land, across several seeds`() {
        // Regression test: the elevation fBm's practical range fell well short
        // of the old mountain threshold (0.80), so mountains were all but
        // impossible (0% of tiles in 9 of 10 seeds manually checked) despite
        // MOUNTAIN being a real, reachable branch in the biome `when`. The
        // threshold now deliberately undershoots the real-world ~24% figure
        // (a single screenshot isn't a reliable way to compare against that
        // anyway - see WorldGenerator's comment) in favor of a lower, safer
        // target with FOOTHILLS as a transitional band below it - assert
        // every one of several seeds lands in a generous band around that
        // lower target, not just "mountains are nonzero somewhere in some
        // seed."
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 30)
            val land = world.faces.indices.count { world[it] != Biome.OCEAN }
            val mountain = world.faces.indices.count { world[it] == Biome.MOUNTAIN }
            val mountainShareOfLand = mountain.toFloat() / land
            assertTrue(
                mountainShareOfLand in 0.05f..0.25f,
                "seed $seed: mountain is ${mountainShareOfLand * 100}% of land, expected ~5-25%",
            )
        }
    }

    @Test
    fun `foothills form a transitional band below mountains, across several seeds`() {
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 30)
            val land = world.faces.indices.count { world[it] != Biome.OCEAN }
            val foothills = world.faces.indices.count { world[it] == Biome.FOOTHILLS }
            val foothillsShareOfLand = foothills.toFloat() / land
            assertTrue(
                foothillsShareOfLand in 0.05f..0.25f,
                "seed $seed: foothills is ${foothillsShareOfLand * 100}% of land, expected ~5-25%",
            )
        }
    }

    @Test
    fun `land averages 30-40 percent of the globe, across several seeds`() {
        // Individual seeds vary a lot more than the average does (elevation
        // noise clusters spatially), so this checks the average of several
        // seeds against the target, plus a generous per-seed sanity band.
        val landShares = (0L until 8L).map { seed ->
            val world = WorldGenerator(seed).generate(frequency = 30)
            val total = world.faces.size
            val land = world.faces.indices.count { world[it] != Biome.OCEAN }
            land.toFloat() / total
        }
        val average = landShares.average()
        assertTrue(
            average in 0.30..0.40,
            "average land share across seeds is ${average * 100}%, expected 30-40%",
        )
        for ((seed, share) in landShares.withIndex()) {
            assertTrue(
                share in 0.15f..0.55f,
                "seed $seed: land is ${share * 100}% of the globe, expected a generous 15-55% sanity band",
            )
        }
    }
}
