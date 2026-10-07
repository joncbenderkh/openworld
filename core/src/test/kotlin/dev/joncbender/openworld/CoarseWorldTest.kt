package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoarseWorldTest {

    private fun dot(a: Sphere, i: Int, b: Sphere, j: Int) =
        a.centerX(i) * b.centerX(j) + a.centerY(i) * b.centerY(j) + a.centerZ(i) * b.centerZ(j)

    @Test
    fun `the neighbor walk finds the same nearest face as an exhaustive search`() {
        val fine = GeodesicSphere.generate(40)
        val coarse = GeodesicSphere.generate(16)
        val parent = CoarseWorldBuilder.nearestFaces(fine, coarse)

        for (i in fine.indices) {
            var bestDot = -2f
            for (c in coarse.indices) bestDot = maxOf(bestDot, dot(fine, i, coarse, c))
            // An exact tie may go to either face; anything measurably worse is a wrong answer.
            assertTrue(dot(fine, i, coarse, parent[i]) >= bestDot - 1e-6f, "fine face $i mapped to a face that is not nearest")
        }
    }

    @Test
    fun `mapping a sphere onto itself is the identity`() {
        val sphere = GeodesicSphere.generate(12)
        val parent = CoarseWorldBuilder.nearestFaces(sphere, sphere)
        for (i in sphere.indices) assertEquals(i, parent[i])
    }

    @Test
    fun `every coarse face gets fine faces when the fine sphere is finer`() {
        val fine = GeodesicSphere.generate(40)
        val coarse = GeodesicSphere.generate(16)
        val children = IntArray(coarse.faceCount)
        for (p in CoarseWorldBuilder.nearestFaces(fine, coarse)) children[p]++
        assertTrue(children.all { it > 0 }, "a coarse face has no fine faces: ${children.count { it == 0 }} of them")
    }

    // Fine freq 8 (642 faces) onto coarse freq 4 (162 faces) with parent = index % 162, so
    // coarse face c owns fine faces c, c+162, c+324, c+486 (the last only if c < 156).
    private val fineSphere = GeodesicSphere.generate(8)
    private val coarseSphere = GeodesicSphere.generate(4)
    private val parent = IntArray(fineSphere.faceCount) { it % coarseSphere.faceCount }

    private fun fineWorld(vararg assignments: Pair<Int, Biome>): SphereWorld {
        val world = SphereWorld(fineSphere, ByteArray(fineSphere.faceCount) { Biome.PLAINS.ordinal.toByte() })
        for ((face, biome) in assignments) world[face] = biome
        return world
    }

    private fun coarseBiomeOf(face: Int, world: SphereWorld): Biome {
        val result = CoarseWorldBuilder.vote(parent, world, coarseSphere)
        return Biome.entries[result[face].toInt()]
    }

    @Test
    fun `plain majority wins`() {
        val world = fineWorld(7 to Biome.DESERT, 169 to Biome.DESERT)
        assertEquals(Biome.PLAINS, coarseBiomeOf(0, world), "untouched")
        // Coarse face 7 owns fine 7, 169, 331, 493: two desert, two plains - desert breaks the tie by ordinal if lower.
        val expectedTie = if (Biome.DESERT.ordinal < Biome.PLAINS.ordinal) Biome.DESERT else Biome.PLAINS
        assertEquals(expectedTie, coarseBiomeOf(7, world), "a tie goes to the lowest ordinal")
        val clearMajority = fineWorld(7 to Biome.DESERT, 169 to Biome.DESERT, 331 to Biome.DESERT)
        assertEquals(Biome.DESERT, coarseBiomeOf(7, clearMajority))
    }

    @Test
    fun `two river tiles beat the plains around them but one does not`() {
        assertEquals(Biome.RIVER, coarseBiomeOf(7, fineWorld(7 to Biome.RIVER, 169 to Biome.RIVER)), "2 river (weight 4) vs 2 plains")
        assertEquals(Biome.PLAINS, coarseBiomeOf(7, fineWorld(7 to Biome.RIVER)), "1 river (weight 2) vs 3 plains")
    }

    @Test
    fun `a single river tile among ocean tiles is outvoted but two survive`() {
        val world = fineWorld(7 to Biome.RIVER, 169 to Biome.OCEAN, 331 to Biome.OCEAN, 493 to Biome.OCEAN)
        // 1 river (2) vs 3 ocean (3): the ocean wins - weights only protect features that are at least a couple of tiles.
        assertEquals(Biome.OCEAN, coarseBiomeOf(7, world))
        val two = fineWorld(7 to Biome.RIVER, 169 to Biome.RIVER, 331 to Biome.OCEAN, 493 to Biome.OCEAN)
        assertEquals(Biome.RIVER, coarseBiomeOf(7, two))
    }

    @Test
    fun `a volcano outweighs two mountain tiles`() {
        val world = fineWorld(7 to Biome.VOLCANO, 169 to Biome.MOUNTAIN, 331 to Biome.MOUNTAIN)
        assertEquals(Biome.VOLCANO, coarseBiomeOf(7, world), "volcano (3) vs 2 mountain (2)")
    }

    @Test
    fun `a coarse face with no fine faces takes its neighbors' common biome`() {
        // Send every fine face to coarse face 0 except face 0's neighbors' share, so coarse face 5 is empty.
        val allToZero = IntArray(fineSphere.faceCount)
        val world = fineWorld()
        val result = CoarseWorldBuilder.vote(allToZero, world, coarseSphere)
        assertEquals(Biome.PLAINS.ordinal, result[0].toInt())
        // Coarse faces next to the decided one inherit its biome; everything else falls back to the
        // ocean default since none of its neighbors was decided.
        coarseSphere.forEachNeighbor(0) { n -> assertEquals(Biome.PLAINS.ordinal, result[n].toInt(), "neighbor $n") }
        assertTrue(result.all { it.toInt() in Biome.entries.indices })
    }

    @Test
    fun `building a coarse world keeps the shape of the fine world and is deterministic`() {
        val fine = WorldGenerator(seed = 5L).generate(frequency = 40)
        val coarse = CoarseWorldBuilder.build(fine, coarseFrequency = 16)
        assertEquals(10 * 16 * 16 + 2, coarse.sphere.faceCount)

        fun oceanFraction(w: SphereWorld) = w.sphere.indices.count { w[it] == Biome.OCEAN }.toFloat() / w.sphere.faceCount
        assertEquals(oceanFraction(fine), oceanFraction(coarse), 0.06f)

        val again = CoarseWorldBuilder.build(fine, coarseFrequency = 16)
        for (i in coarse.sphere.indices) assertEquals(coarse[i], again[i], "face $i")
    }

    @Test
    fun `rivers in the fine world survive into the coarse one`() {
        val fine = WorldGenerator(seed = 5L).generate(frequency = 40)
        val fineRivers = fine.sphere.indices.count { fine[it] == Biome.RIVER }
        assertTrue(fineRivers > 0, "this seed should have rivers for the test to mean anything")
        val coarse = CoarseWorldBuilder.build(fine, coarseFrequency = 16)
        assertTrue(coarse.sphere.indices.any { coarse[it] == Biome.RIVER }, "all $fineRivers fine river tiles vanished")
    }
}
