package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TilePickerTest {

    private val coarse = GeodesicSphere.generate(5)
    private val compact = WorldCompactor.compact(WorldGenerator(7L).generate(30), coarse, rings = 1)
    private val sphere = compact.world.sphere
    private val picker = TilePicker(compact, coarse)

    private fun pick(i: Int) = picker.pick(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i))

    @Test
    fun `the test world has both kinds of tile`() {
        assertTrue(compact.fineCount > 0 && compact.oceanCount > 0)
    }

    @Test
    fun `a tile's own center picks that tile`() {
        for (t in 0 until compact.tileCount) assertEquals(t, pick(t), "tile $t")
    }

    @Test
    fun `any point resolves like the brute-force rule`() {
        val random = Random(3)
        repeat(2000) {
            val x = random.nextGaussian().toFloat()
            val y = random.nextGaussian().toFloat()
            val z = random.nextGaussian().toFloat()
            val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val (px, py, pz) = Triple(x / length, y / length, z / length)

            val cell = (0 until coarse.faceCount).maxByOrNull { coarse.centerX(it) * px + coarse.centerY(it) * py + coarse.centerZ(it) * pz }!!
            val oceanIndex = compact.coarseFace.indexOf(cell)
            val expected = if (oceanIndex >= 0) {
                compact.fineCount + oceanIndex
            } else {
                (0 until compact.fineCount).maxByOrNull { sphere.centerX(it) * px + sphere.centerY(it) * py + sphere.centerZ(it) * pz }!!
            }
            assertEquals(expected, picker.pick(px, py, pz))
        }
    }

    @Test
    fun `a point deep inside an ocean tile picks it even when a fine tile's center is nearer`() {
        val o = compact.fineCount
        val c = compact.coarseFace[0]
        // Slide from the ocean tile's center toward one of its corners, but stay inside the cell.
        val k = 0
        val x = coarse.centerX(c) * 0.3f + coarse.cornerX(c, k) * 0.7f
        val y = coarse.centerY(c) * 0.3f + coarse.cornerY(c, k) * 0.7f
        val z = coarse.centerZ(c) * 0.3f + coarse.cornerZ(c, k) * 0.7f
        val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        assertEquals(o, picker.pick(x / length, y / length, z / length))
    }
}
