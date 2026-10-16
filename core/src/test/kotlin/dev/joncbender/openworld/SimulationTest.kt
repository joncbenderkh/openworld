package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationTest {
    private val sphere = GeodesicSphere.generate(8)
    private val near = SettlementRules.claim(world(emptyMap()), 20, 1).first { it != 20 }

    private fun world(resources: Map<Int, List<Resource>>): CompactWorld {
        val w = SphereWorld(sphere, Array(sphere.faceCount) { Biome.PLAINS })
        resources.forEach { (tile, list) -> w.setResources(tile, list) }
        return CompactWorld(w, sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
    }

    private class Rig(world: CompactWorld) {
        val prefs = FakePreferences()
        val inventory = Inventory(prefs)
        val settlements = SettlementBook(prefs).also { it.found(world, 20) }
        private val deposits = Deposits(prefs)
        // Plenty of everything the people need, so the tick is about gathering and growth, not hunger.
        private val siteOf = { s: Settlement ->
            SettlementSite.of(world, s).let { SettlementSite(it.tiles, IntArray(Need.entries.size) { 1000 }, it.cold, it.deposits) }
        }
        val simulation = Simulation(settlements, inventory, Gatherer(inventory, deposits), Crafter(inventory), Upkeeper(inventory, prefs), siteOf)
    }

    @Test
    fun `a tick gathers from the land`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        repeat(40) { rig.simulation.tick() }
        assertTrue(rig.inventory.count(Resource.GRAIN) > 0)
    }

    @Test
    fun `replays are deterministic`() {
        val resources = mapOf(near to listOf(Resource.GRAIN, Resource.WOOD))
        val a = Rig(world(resources)).also { rig -> repeat(300) { rig.simulation.tick() } }
        val b = Rig(world(resources)).also { rig -> repeat(300) { rig.simulation.tick() } }
        assertEquals(a.inventory.held(), b.inventory.held())
        assertEquals(a.settlements.all.toList(), b.settlements.all.toList())
    }

    @Test
    fun `a full away time of replay fills the store and no further`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        repeat(OfflineProgress.Tuning.CAP_TICKS) { rig.simulation.tick() }
        assertEquals(Gathering.capacity(rig.settlements.all), rig.inventory.count(Resource.GRAIN))
    }
}
