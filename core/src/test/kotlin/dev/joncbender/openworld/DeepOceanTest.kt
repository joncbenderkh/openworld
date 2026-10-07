package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepOceanTest {

    private val sphere = GeodesicSphere.generate(30)

    private fun oceanWith(land: Set<Int>): SphereWorld {
        val world = SphereWorld(sphere, ByteArray(sphere.faceCount) { Biome.OCEAN.ordinal.toByte() })
        for (i in land) world[i] = Biome.PLAINS
        return world
    }

    /** Hops from [from] to every face along the neighbor graph - the ground truth to compare against. */
    private fun distancesFrom(sphere: Sphere, from: Int): IntArray {
        val distance = IntArray(sphere.faceCount) { -1 }
        val queue = ArrayDeque<Int>()
        distance[from] = 0
        queue.add(from)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            sphere.forEachNeighbor(i) { n -> if (distance[n] == -1) { distance[n] = distance[i] + 1; queue.add(n) } }
        }
        return distance
    }

    @Test
    fun `with no land every tile is deep ocean, so nothing needs drawing`() {
        val draw = DeepOcean.tilesToDraw(oceanWith(emptySet()), rings = 3)
        assertTrue(draw.none { it })
    }

    @Test
    fun `zero rings leaves out every ocean tile and keeps every land tile`() {
        val world = oceanWith(setOf(5, 100, 400))
        val draw = DeepOcean.tilesToDraw(world, rings = 0)
        for (i in sphere.indices) assertEquals(world[i] != Biome.OCEAN, draw[i], "face $i")
    }

    @Test
    fun `land is always drawn, and ocean is drawn exactly when land is within the margin`() {
        val land = 300
        val world = oceanWith(setOf(land))
        val fromLand = distancesFrom(sphere, land)
        for (rings in 1..4) {
            val draw = DeepOcean.tilesToDraw(world, rings)
            for (i in sphere.indices) {
                // Drawn individually iff it is within `rings` hops of land (land itself is 0 hops).
                assertEquals(fromLand[i] <= rings, draw[i], "rings=$rings face $i at ${fromLand[i]} hops")
            }
        }
    }

    @Test
    fun `a wider margin never draws fewer tiles`() {
        val world = oceanWith(setOf(10, 700, 1500))
        var previous = 0
        for (rings in 0..4) {
            val drawn = DeepOcean.tilesToDraw(world, rings).count { it }
            assertTrue(drawn >= previous, "rings=$rings drew $drawn after $previous")
            previous = drawn
        }
    }

    @Test
    fun `lakes and rivers count as land for the margin`() {
        val world = SphereWorld(sphere, ByteArray(sphere.faceCount) { Biome.OCEAN.ordinal.toByte() })
        world[50] = Biome.LAKE
        world[60] = Biome.RIVER
        val draw = DeepOcean.tilesToDraw(world, rings = 1)
        assertTrue(draw[50] && draw[60])
        assertFalse(draw.all { it })
    }
}
