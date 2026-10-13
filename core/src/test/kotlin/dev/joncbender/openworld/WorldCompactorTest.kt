package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldCompactorTest {

    private val full = WorldGenerator(7L).generate(30)
    private val coarse = GeodesicSphere.generate(5)
    private val compact = WorldCompactor.compact(full, coarse, rings = 1)
    private val world = compact.world
    private val sphere = world.sphere

    @Test
    fun `the test world has both kinds of tile, so the other tests mean something`() {
        assertTrue(compact.fineCount > 0 && compact.oceanCount > 0)
        assertTrue(compact.tileCount < full.sphere.faceCount)
        assertEquals(compact.tileCount, sphere.faceCount)
    }

    @Test
    fun `kept tiles keep their biome, resources, position and corner count`() {
        for (t in 0 until compact.fineCount) {
            val source = compact.sourceFace[t]
            assertEquals(full[source], world[t])
            assertEquals(full.resourcesAt(source), world.resourcesAt(t))
            assertEquals(full.sphere.cornerCount(source), sphere.cornerCount(t))
            assertEquals(full.sphere.centerX(source), sphere.centerX(t))
            assertEquals(full.sphere.centerZ(source), sphere.centerZ(t))
            for (k in 0 until sphere.cornerCount(t)) {
                assertEquals(full.sphere.cornerX(source, k), sphere.cornerX(t, k), 1e-6f)
                assertEquals(full.sphere.cornerY(source, k), sphere.cornerY(t, k), 1e-6f)
                assertEquals(full.sphere.cornerZ(source, k), sphere.cornerZ(t, k), 1e-6f)
            }
        }
    }

    @Test
    fun `source faces ascend and are distinct`() {
        for (t in 1 until compact.fineCount) assertTrue(compact.sourceFace[t] > compact.sourceFace[t - 1])
    }

    @Test
    fun `ocean tiles are plain ocean with no resources and the corners of their coarse tile`() {
        for (o in 0 until compact.oceanCount) {
            val t = compact.fineCount + o
            val c = compact.coarseFace[o]
            assertTrue(compact.isOpenOcean(t))
            assertEquals(Biome.OCEAN, world[t])
            assertTrue(world.resourcesAt(t).isEmpty())
            assertEquals(coarse.cornerCount(c), sphere.cornerCount(t))
            for (k in 0 until sphere.cornerCount(t)) {
                assertEquals(coarse.cornerX(c, k), sphere.cornerX(t, k))
                assertEquals(coarse.cornerZ(c, k), sphere.cornerZ(t, k))
            }
        }
    }

    @Test
    fun `every neighbor is a valid tile other than itself`() {
        for (t in 0 until sphere.faceCount) {
            sphere.forEachNeighbor(t) { n ->
                assertTrue(n in 0 until sphere.faceCount && n != t, "tile $t has neighbor $n")
            }
        }
    }

    @Test
    fun `neighbors are mutual`() {
        for (t in 0 until sphere.faceCount) {
            sphere.forEachNeighbor(t) { n ->
                assertTrue(sphere.anyNeighbor(n) { it == t }, "$t lists $n but $n does not list $t")
            }
        }
    }

    @Test
    fun `land never touches an ocean tile`() {
        for (t in 0 until compact.fineCount) {
            if (world[t] != Biome.OCEAN) {
                sphere.forEachNeighbor(t) { n -> assertTrue(!compact.isOpenOcean(n), "land tile $t touches an ocean tile") }
            }
        }
    }

    @Test
    fun `corner vertices are in range and every vertex is used`() {
        val used = BooleanArray(sphere.vertexCount)
        for (k in 0 until sphere.cornerTotal) {
            assertTrue(sphere.cornerVertex[k] in 0 until sphere.vertexCount)
            used[sphere.cornerVertex[k]] = true
        }
        assertTrue(used.all { it })
    }

    @Test
    fun `the pentagon count of kept tiles matches the source`() {
        val keptPentagons = (0 until compact.fineCount).count { sphere.cornerCount(it) == 5 }
        val sourcePentagons = (0 until compact.fineCount).count { full.sphere.cornerCount(compact.sourceFace[it]) == 5 }
        assertEquals(sourcePentagons, keptPentagons)
    }

    @Test
    fun `the tiles worth drawing in detail are the same in the compact world as in the full one`() {
        // rings = 1 is the coast width the world was compacted with.
        val before = DeepOcean.tilesToDraw(full, rings = 1)
        val after = DeepOcean.tilesToDraw(world, rings = 1)
        for (t in 0 until compact.fineCount) assertEquals(before[compact.sourceFace[t]], after[t], "fine tile $t")
        // Every tile worth drawing was kept, so nothing the full world would have drawn is lost.
        assertEquals(before.count { it }, (0 until compact.fineCount).count { after[it] })
    }

    @Test
    fun `an all-ocean world compacts to coarse tiles alone`() {
        val bare = SphereWorld(full.sphere, ByteArray(full.sphere.faceCount) { Biome.OCEAN.ordinal.toByte() })
        val result = WorldCompactor.compact(bare, coarse, rings = 1)
        assertEquals(0, result.fineCount)
        assertEquals(coarse.faceCount, result.oceanCount)
        for (o in 0 until coarse.faceCount) assertEquals(coarse.neighborCount(o), result.world.sphere.neighborCount(o))
    }

    @Test
    fun `compaction is deterministic`() {
        val again = WorldCompactor.compact(full, coarse, rings = 1)
        assertTrue(again.world.sphere.neighbors.contentEquals(sphere.neighbors))
        assertTrue(again.sourceFace.contentEquals(compact.sourceFace))
    }

    @Test
    fun `a world generated without corners compacts to the same world as one with them`() {
        val lean = WorldGenerator(7L).generate(30, corners = false)
        assertTrue(!lean.sphere.hasCorners)
        for (i in 0 until full.sphere.faceCount) assertEquals(full[i], lean[i], "tile $i")
        val again = WorldCompactor.compact(lean, coarse, rings = 1)
        val a = again.world.sphere
        assertTrue(a.neighbors.contentEquals(sphere.neighbors))
        assertTrue(a.cornerVertex.contentEquals(sphere.cornerVertex))
        assertTrue(a.vertexPositions.contentEquals(sphere.vertexPositions))
        assertTrue(a.centers.contentEquals(sphere.centers))
    }

    @Test
    fun `corners are shared by exactly the three tiles that meet there`() {
        val uses = IntArray(sphere.vertexCount)
        for (k in 0 until sphere.cornerTotal) uses[sphere.cornerVertex[k]]++
        // A corner on the seam with an ocean tile is also a corner of that ocean tile, so fine corners are used 1-3 times.
        for (v in 0 until sphere.vertexCount) assertTrue(uses[v] in 1..3, "vertex $v used ${uses[v]} times")
    }

    @Test
    fun `rebuilt corners sit where the source corners are, including on pentagons, at every size`() {
        var checked = 0
        for (frequency in intArrayOf(1, 2, 3, 12)) {
            val source = WorldGenerator(3L).generate(frequency)
            val result = WorldCompactor.compact(source, GeodesicSphere.generate(1), rings = 1)
            val rebuilt = result.world.sphere
            checked += result.fineCount
            for (t in 0 until result.fineCount) {
                val face = result.sourceFace[t]
                for (k in 0 until rebuilt.cornerCount(t)) {
                    assertEquals(source.sphere.cornerX(face, k), rebuilt.cornerX(t, k), 1e-6f, "freq $frequency tile $t corner $k")
                    assertEquals(source.sphere.cornerY(face, k), rebuilt.cornerY(t, k), 1e-6f, "freq $frequency tile $t corner $k")
                    assertEquals(source.sphere.cornerZ(face, k), rebuilt.cornerZ(t, k), 1e-6f, "freq $frequency tile $t corner $k")
                }
            }
        }
        assertTrue(checked > 0, "no tile was kept, so nothing was compared")
    }
}
