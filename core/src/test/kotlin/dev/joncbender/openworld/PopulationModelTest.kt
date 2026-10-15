package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopulationModelTest {
    private val sphere = GeodesicSphere.generate(8)

    // Plains everywhere; the given resources sit on the tiles named.
    private fun world(biome: Biome = Biome.PLAINS, resources: Map<Int, List<Resource>> = emptyMap()): CompactWorld {
        val w = SphereWorld(sphere, Array(sphere.faceCount) { biome })
        resources.forEach { (tile, list) -> w.setResources(tile, list) }
        return CompactWorld(w, sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
    }

    private fun site(world: CompactWorld, settlement: Settlement) = SettlementSite.of(world, settlement)

    private fun settle(world: CompactWorld, population: Float, tier: SettlementTier = SettlementTier.HOMESTEAD) =
        Settlement(20, tier, population).let { it to site(world, it) }

    @Test
    fun `supply counts the resource units on the claimed tiles only`() {
        val near = SettlementRules.claim(world(), 20, 1).first { it != 20 }
        val far = (0 until sphere.faceCount).first { it !in SettlementRules.claim(world(), 20, 2) }
        val world = world(resources = mapOf(near to listOf(Resource.GRAIN, Resource.WOOD), far to listOf(Resource.FISH)))
        val site = site(world, Settlement(20, SettlementTier.HOMESTEAD))
        assertEquals(1, site.supply(Need.FOOD))
        assertEquals(1, site.supply(Need.SHELTER))
        assertEquals(1, site.supply(Need.WARMTH))
    }

    @Test
    fun `fed people grow and are capped by the tier`() {
        val tile = SettlementRules.claim(world(), 20, 1).first { it != 20 }
        val world = world(resources = mapOf(tile to List(3) { Resource.GRAIN }))
        var (settlement, site) = settle(world, 2f)
        repeat(500) { settlement = PopulationModel.step(settlement, site) }
        assertEquals(SettlementTier.HOMESTEAD.populationCap.toFloat(), settlement.population)
    }

    @Test
    fun `growth stops at half satisfaction and turns into decline below it`() {
        val tile = SettlementRules.claim(world(), 20, 1).first { it != 20 }
        val world = world(resources = mapOf(tile to listOf(Resource.GRAIN)))
        val site = site(world, Settlement(20, SettlementTier.HOMESTEAD))
        // One unit feeds 5: ten people are at half satisfaction, so they neither grow nor shrink.
        assertEquals(10f, PopulationModel.step(Settlement(20, SettlementTier.VILLAGE, 10f), SettlementSite(site.tiles, intArrayOf(1, 1, 0), false)).population, 1e-4f)
        assertTrue(PopulationModel.step(Settlement(20, SettlementTier.VILLAGE, 20f), SettlementSite(site.tiles, intArrayOf(1, 1, 0), false)).population < 20f)
    }

    @Test
    fun `below four people growth and decline are those of four`() {
        val site = SettlementSite(IntArray(0), intArrayOf(10, 0, 0), false)
        assertEquals(2.2f, PopulationModel.step(Settlement(20, SettlementTier.HOMESTEAD, 2f), site).population, 1e-4f)
        val starving = SettlementSite(IntArray(0), intArrayOf(0, 0, 0), false)
        assertEquals(1.0f, PopulationModel.step(Settlement(20, SettlementTier.HOMESTEAD, 1.1f), starving).population, 1e-4f)
        assertEquals(1.8f, PopulationModel.step(Settlement(20, SettlementTier.HOMESTEAD, 2f), starving).population, 1e-4f)
    }

    @Test
    fun `starvation declines but never below the minimum`() {
        var (settlement, site) = settle(world(), 8f)
        repeat(1000) { settlement = PopulationModel.step(settlement, site) }
        assertEquals(PopulationModel.MIN, settlement.population)
    }

    @Test
    fun `a homestead needs food only, a village shelter too and a cold one warmth`() {
        assertEquals(listOf(Need.FOOD), PopulationModel.needs(SettlementTier.HOMESTEAD, cold = true))
        assertEquals(listOf(Need.FOOD, Need.SHELTER), PopulationModel.needs(SettlementTier.VILLAGE, cold = false))
        assertEquals(listOf(Need.FOOD, Need.SHELTER, Need.WARMTH), PopulationModel.needs(SettlementTier.TOWN, cold = true))
        assertTrue(site(world(Biome.TAIGA), Settlement(20, SettlementTier.VILLAGE)).cold)
        assertFalse(site(world(Biome.PLAINS), Settlement(20, SettlementTier.VILLAGE)).cold)
    }

    @Test
    fun `a village short of shelter is held back by its worst need`() {
        val site = SettlementSite(IntArray(0), intArrayOf(10, 0, 0), false)
        assertEquals(0f, PopulationModel.satisfaction(Settlement(20, SettlementTier.VILLAGE, 10f), site))
    }

    @Test
    fun `upgrading needs population and room, and grows the tier`() {
        val world = world()
        val book = SettlementBook(FakePreferences())
        book.found(world, 20)
        assertEquals(UpgradeResult.TOO_SMALL, book.upgrade(world, 20))
        book.update { it.copy(population = 9f) }
        assertEquals(UpgradeResult.OK, book.upgrade(world, 20))
        assertEquals(SettlementTier.VILLAGE, book.at(20)!!.tier)
        assertEquals(UpgradeResult.TOO_SMALL, book.upgrade(world, 20))
    }

    @Test
    fun `an upgrade whose claim would reach another is blocked`() {
        val world = world()
        val book = SettlementBook(FakePreferences())
        book.found(world, 20)
        // Just outside the homestead's claim and its neighbor's: three hops away, so a village (radius 2) next to it touches.
        val other = SettlementRules.claim(world, 20, 3).first { SettlementRules.canFound(world, it, book.all) == FoundResult.OK }
        book.found(world, other)
        book.update { it.copy(population = 9f) }
        assertEquals(UpgradeResult.BLOCKED, book.upgrade(world, 20))
    }

    @Test
    fun `population survives a reload and an old save without it still loads`() {
        val prefs = FakePreferences()
        val world = world()
        SettlementBook(prefs).apply { found(world, 20); update { it.copy(population = 6.5f) } }
        assertEquals(6.5f, SettlementBook(prefs).at(20)!!.population)
        prefs.putString("settlements", "20:VILLAGE")
        assertEquals(PopulationModel.START, SettlementBook(prefs).at(20)!!.population)
    }

    @Test
    fun `status reports the trend`() {
        val full = SettlementSite(IntArray(0), intArrayOf(10, 10, 0), false)
        val status = PopulationModel.status(Settlement(20, SettlementTier.VILLAGE, 5f), full, UpgradeResult.TOO_SMALL)
        assertEquals(Trend.GROWING, status.trend)
        assertTrue(status.needs.all { it.met })
        val starving = PopulationModel.status(Settlement(20, SettlementTier.HOMESTEAD, 5f), SettlementSite(IntArray(0), intArrayOf(0, 0, 0), false), UpgradeResult.TOO_SMALL)
        assertEquals(Trend.DECLINING, starving.trend)
    }
}
