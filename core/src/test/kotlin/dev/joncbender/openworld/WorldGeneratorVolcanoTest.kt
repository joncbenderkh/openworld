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

    @Test
    fun `standalone ocean island volcanoes occur, across several seeds`() {
        // Regression test: mountain-elevation-based placement alone can only
        // ever produce coastal-range volcanoes, never a true standalone
        // island one - a small (1-15 tile) island never accumulates enough
        // elevation gradient to reach full MOUNTAIN status, so its highest
        // point sits barely above sea level no matter what. Counts how many
        // volcanoes sit on a small landmass surrounded by the ocean, summed
        // across several seeds since any single seed's small islands are a
        // random, sometimes-empty draw.
        var islandVolcanoesAcrossSeeds = 0
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 89)
            val visited = BooleanArray(world.faces.size)
            fun isLand(i: Int) = world[i] != Biome.OCEAN && world[i] != Biome.LAKE

            for (start in world.faces.indices) {
                if (world[start] != Biome.VOLCANO || visited[start]) continue

                val island = mutableListOf<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(start)
                visited[start] = true
                while (queue.isNotEmpty()) {
                    val i = queue.removeFirst()
                    island.add(i)
                    for (n in world.faces[i].neighbors) {
                        if (!visited[n] && isLand(n)) {
                            visited[n] = true
                            queue.add(n)
                        }
                    }
                }
                if (island.size <= 15) islandVolcanoesAcrossSeeds++
            }
        }
        assertTrue(
            islandVolcanoesAcrossSeeds > 0,
            "expected at least one standalone ocean island volcano across 8 seeds, found none",
        )
    }
}
