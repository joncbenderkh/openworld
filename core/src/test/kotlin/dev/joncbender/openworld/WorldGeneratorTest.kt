package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorTest {

    @Test
    fun `no lakes or rivers appear in the arctic`() {
        // A handful of seeds, since a single one might not happen to place any
        // river/lake candidates near a pole at all.
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 10)
            for (i in world.faces.indices) {
                val latitude = abs(world.faces[i].center.y)
                if (latitude < 0.88f) continue
                val biome = world[i]
                assertTrue(
                    biome != Biome.LAKE && biome != Biome.RIVER,
                    "seed $seed: face $i at latitude $latitude is $biome, expected no lakes/rivers this close to a pole",
                )
            }
        }
    }
}
