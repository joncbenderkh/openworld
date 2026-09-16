package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorOceanShapeTest {

    @Test
    fun `landlocked ocean depressions become lakes or ice instead, across several seeds`() {
        // Regression test: OCEAN used to mean "any tile below sea level,"
        // which includes below-sea-level depressions fully enclosed by land
        // (like the real Caspian Sea) - a tiny landlocked puddle far from
        // the coast got the same OCEAN classification as the actual, single
        // connected global ocean purely because of its elevation. Assert no
        // OCEAN connected component is tiny relative to the largest one (a
        // landlocked pocket this close to a pole becomes ARCTIC ice rather
        // than a lake - see the "no lakes in arctic regions" rule - so it
        // shouldn't show up as a small OCEAN component here either).
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 30)
            val visited = BooleanArray(world.faces.size)
            val componentSizes = mutableListOf<Int>()
            for (start in world.faces.indices) {
                if (visited[start] || world[start] != Biome.OCEAN) continue
                var size = 0
                val queue = ArrayDeque<Int>()
                queue.add(start)
                visited[start] = true
                while (queue.isNotEmpty()) {
                    val i = queue.removeFirst()
                    size++
                    for (n in world.faces[i].neighbors) {
                        if (!visited[n] && world[n] == Biome.OCEAN) {
                            visited[n] = true
                            queue.add(n)
                        }
                    }
                }
                componentSizes.add(size)
            }
            if (componentSizes.isEmpty()) continue
            val largest = componentSizes.max()
            for (size in componentSizes) {
                assertTrue(
                    size >= largest * 0.04,
                    "seed $seed: found an OCEAN component of $size tiles vs. largest $largest - " +
                        "too small to be part of the same ocean, should be a LAKE or ARCTIC",
                )
            }
        }
    }
}
