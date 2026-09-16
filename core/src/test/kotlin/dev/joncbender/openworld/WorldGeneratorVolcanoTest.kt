package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorVolcanoTest {

    @Test
    fun `volcanoes are rare but present, across several seeds`() {
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 89)
            val land = world.faces.indices.count { world[it] != Biome.OCEAN }
            val volcano = world.faces.indices.count { world[it] == Biome.VOLCANO }
            assertTrue(volcano > 0, "seed $seed: expected at least one volcano")
            val volcanoShareOfLand = volcano.toFloat() / land
            assertTrue(
                volcanoShareOfLand < 0.005f,
                "seed $seed: volcano is ${volcanoShareOfLand * 100}% of land, expected well under 0.5%",
            )
        }
    }

    @Test
    fun `every volcano is within a few hex-hops of the ocean, across several seeds`() {
        // Regression test for the placement rule ("along coasts, ocean
        // islands"): a volcano tile should never be found deep inland,
        // however rare it is.
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 89)

            val distanceToOcean = IntArray(world.faces.size) { -1 }
            val queue = ArrayDeque<Int>()
            for (i in world.faces.indices) {
                if (world[i] == Biome.OCEAN) {
                    distanceToOcean[i] = 0
                    queue.add(i)
                }
            }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                for (n in world.faces[i].neighbors) {
                    if (distanceToOcean[n] == -1) {
                        distanceToOcean[n] = distanceToOcean[i] + 1
                        queue.add(n)
                    }
                }
            }

            for (i in world.faces.indices) {
                if (world[i] != Biome.VOLCANO) continue
                assertTrue(
                    distanceToOcean[i] in 1..6,
                    "seed $seed: face $i is a volcano ${distanceToOcean[i]} hops from the ocean, expected within 6",
                )
            }
        }
    }
}
