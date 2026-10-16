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
    fun `a full away time fills the store and no further`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        rig.simulation.away(OfflineProgress.Tuning.CAP_TICKS)
        assertEquals(Gathering.capacity(rig.settlements.all), rig.inventory.count(Resource.GRAIN))
    }

    @Test
    fun `time away is close to ticking it one by one`() {
        val resources = mapOf(near to listOf(Resource.GRAIN, Resource.WOOD))
        val one = Rig(world(resources)).also { rig -> repeat(120) { rig.simulation.tick() } }
        val many = Rig(world(resources)).also { rig -> rig.simulation.away(120) }
        for (item in listOf(Resource.GRAIN, Resource.WOOD)) {
            val a = one.inventory.count(item).toFloat()
            val b = many.inventory.count(item).toFloat()
            assertTrue(a > 0 && kotlin.math.abs(a - b) <= maxOf(2f, a * 0.1f), "$item: $a ticks vs $b away")
        }
        val p1 = one.settlements.all.first().population
        val p2 = many.settlements.all.first().population
        assertTrue(kotlin.math.abs(p1 - p2) <= maxOf(0.5f, p1 * 0.15f), "people: $p1 vs $p2")
    }

    @Test
    fun `no time away changes nothing`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        val report = rig.simulation.away(0)
        assertEquals(0, report.ticks)
        assertTrue(report.stock.isEmpty() && report.idle.isEmpty())
        assertEquals(0, rig.inventory.count(Resource.GRAIN))
    }

    @Test
    fun `the report says what the time away changed`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        val report = rig.simulation.away(200)
        assertEquals(200, report.ticks)
        assertEquals(rig.inventory.count(Resource.GRAIN), report.stock.getValue(Resource.GRAIN))
        assertEquals(1, report.settlements.size)
        assertEquals(rig.settlements.all.first().population, report.settlements.first().after)
    }

    @Test
    fun `a building that cannot be paid goes idle during the time away`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        rig.settlements.update { it.copy(buildings = setOf(Building.MILL)) }
        val report = rig.simulation.away(100)
        assertEquals(setOf(Building.MILL), report.idle.getValue(20))
    }

    @Test
    fun `growth over a long time stops at the tier cap`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN))))
        rig.simulation.away(OfflineProgress.Tuning.CAP_TICKS)
        assertTrue(rig.settlements.all.first().population <= SettlementTier.HOMESTEAD.populationCap)
    }

    @Test
    fun `a building the income can pay for is not idled by a long time away`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN, Resource.WOOD))))
        rig.settlements.update { it.copy(buildings = setOf(Building.MILL)) }
        val report = rig.simulation.away(1000)
        assertTrue(report.idle.isEmpty(), "idle: ${report.idle}")
        assertTrue(Building.MILL !in rig.settlements.all.first().idle)
    }

    @Test
    fun `a building the income pays for stays working through four hours`() {
        val rig = Rig(world(mapOf(near to listOf(Resource.GRAIN, Resource.WOOD))))
        rig.settlements.update { it.copy(buildings = setOf(Building.MILL)) }
        val report = rig.simulation.away(OfflineProgress.Tuning.CAP_TICKS)
        assertTrue(report.idle.isEmpty(), "idle: ${report.idle}")
    }

    @Test
    fun `people leaving fall as they would tick by tick, linearly below the base`() {
        val site = SettlementSite(IntArray(0), IntArray(Need.entries.size) { 1000 }, false, emptyList())
        val unrest = Happiness(0f, Mood.UNREST, emptyList(), 0f)
        var single = Settlement(20, SettlementTier.VILLAGE, 4f)
        repeat(10) { single = PopulationModel.step(single, site, emptySet(), unrest) }
        val at = PopulationModel.step(Settlement(20, SettlementTier.VILLAGE, 4f), site, emptySet(), unrest, ticks = 10)
        assertEquals(single.population, at.population, 0.05f)
    }
}
