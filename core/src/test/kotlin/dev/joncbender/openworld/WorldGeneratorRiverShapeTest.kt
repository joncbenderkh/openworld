package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertTrue

class WorldGeneratorRiverShapeTest {

    @Test
    fun `rivers stay thin paths instead of pooling into wide blobs, across several seeds`() {
        // Regression test: many separate river walks can dead-end in the same
        // inland depression that's too high up to qualify as a near-sea-level
        // lake, painting it as a wide RIVER-textured blob instead of the pool
        // it visually is. A real single-tile-wide path mostly has at most two
        // RIVER neighbors (three at a rare confluence); a pool's interior
        // tiles touch many more - assert high-degree ("pooled-looking") river
        // tiles stay rare.
        for (seed in 0L until 8L) {
            val world = WorldGenerator(seed).generate(frequency = 30)
            val riverTiles = world.faces.indices.filter { world[it] == Biome.RIVER }
            if (riverTiles.isEmpty()) continue
            val pooledCount = riverTiles.count { i ->
                world.faces[i].neighbors.count { world[it] == Biome.RIVER } >= 4
            }
            val pooledFraction = pooledCount.toFloat() / riverTiles.size
            assertTrue(
                pooledFraction <= 0.10f,
                "seed $seed: ${pooledFraction * 100}% of river tiles look pooled (>=4 river neighbors), expected <=10%",
            )
        }
    }
}
