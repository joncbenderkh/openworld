package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorDesertTest {

    @Test
    fun `desert never appears outside the equatorial band`() {
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 10)
            for (i in world.faces.indices) {
                if (world[i] != Biome.DESERT) continue
                val latitude = abs(world.faces[i].center.y)
                assertTrue(latitude < 0.40f, "seed $seed: desert at latitude $latitude, expected it confined to the equatorial band")
            }
        }
    }

    @Test
    fun `desert is a substantial share of the equatorial band, not a rarity`() {
        // Regression guard for the actual ask ("deserts should be more prevalent
        // around the equator") - across enough seeds, desert should make up a
        // meaningful chunk of non-ocean equatorial land, not just be technically
        // possible there.
        var equatorialLand = 0
        var equatorialDesert = 0
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 16)
            for (i in world.faces.indices) {
                val latitude = abs(world.faces[i].center.y)
                if (latitude >= 0.40f) continue
                val biome = world[i]
                if (biome == Biome.OCEAN) continue
                equatorialLand++
                if (biome == Biome.DESERT) equatorialDesert++
            }
        }
        val desertShare = equatorialDesert.toFloat() / equatorialLand
        assertTrue(desertShare > 0.15f, "desert made up only ${desertShare * 100}% of equatorial land, expected a substantial share")
    }
}
