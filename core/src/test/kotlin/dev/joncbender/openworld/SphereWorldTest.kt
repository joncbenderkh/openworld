package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SphereWorldTest {

    private fun world() = SphereWorld(GeodesicSphere.generate(2), ByteArray(42))

    @Test
    fun `every biome round-trips through the byte storage`() {
        val world = world()
        for ((i, biome) in Biome.entries.withIndex()) {
            world[i] = biome
            assertEquals(biome, world[i])
        }
    }

    @Test
    fun `resources keep their exact order, which is not ordinal order`() {
        // DESERT's options are GOLD (7), OIL (11), STONE (10): a plain bitmask would reorder these.
        val world = world()
        val desert = listOf(Resource.GOLD, Resource.OIL, Resource.STONE)
        world.setResources(3, desert)
        assertEquals(desert, world.resourcesAt(3))
    }

    @Test
    fun `no resources, one, and the maximum all round-trip`() {
        val world = world()
        assertEquals(emptyList(), world.resourcesAt(0))
        world.setResources(1, listOf(Resource.SULFUR))
        assertEquals(listOf(Resource.SULFUR), world.resourcesAt(1))
        val four = listOf(Resource.SULFUR, Resource.FISH, Resource.OBSIDIAN, Resource.ICE_CRYSTALS)
        world.setResources(2, four)
        assertEquals(four, world.resourcesAt(2))
        assertEquals(SphereWorld.MAX_RESOURCES, four.size)
    }

    @Test
    fun `every biome's resource options fit the packed layout`() {
        val world = world()
        for ((biome, options) in BIOME_RESOURCES) {
            world.setResources(0, options)
            assertEquals(options, world.resourcesAt(0), "$biome")
        }
    }

    @Test
    fun `resources are per tile and setting replaces`() {
        val world = world()
        world.setResources(5, listOf(Resource.WOOD, Resource.GAME))
        world.setResources(6, listOf(Resource.GRAIN))
        world.setResources(5, listOf(Resource.FURS))
        assertEquals(listOf(Resource.FURS), world.resourcesAt(5))
        assertEquals(listOf(Resource.GRAIN), world.resourcesAt(6))
    }

    @Test
    fun `more than the maximum resources is rejected`() {
        val world = world()
        assertFailsWith<IllegalArgumentException> { world.setResources(0, List(5) { Resource.FISH }) }
    }
}
