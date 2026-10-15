package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingsTest {
    private val prefs = FakePreferences()
    private val inventory = Inventory(prefs)

    private fun site(vararg resources: Resource) =
        SettlementSite(IntArray(0), intArrayOf(1000, 1000, 1000), false, resources.mapIndexed { i, r -> Deposit(i, 0, r) })

    private val homestead = Settlement(20, SettlementTier.HOMESTEAD, 10f)
    private val village = Settlement(20, SettlementTier.VILLAGE, 30f)
    private val plenty: (Item) -> Int = { 100 }

    @Test
    fun `the table is sound, affordable, with every building doing something`() {
        for (building in Building.entries) {
            val spec = Buildings.spec(building)
            assertTrue(spec.cost.isNotEmpty() && spec.cost.values.all { it > 0 }, "$building cost")
            assertTrue(spec.upkeep.isNotEmpty() && spec.upkeep.values.all { it > 0 }, "$building upkeep")
            assertTrue(spec.cost.values.all { it <= Gathering.storage(SettlementTier.HOMESTEAD) }, "$building costs more than a store holds")
            val enables = Recipes.ALL.any { it.requires == building }
            assertTrue(spec.boosts.isNotEmpty() || spec.storage > 0 || spec.foodUnits > 0 || spec.mood > 0f || enables, "$building does nothing")
        }
        for (recipe in Recipes.ALL) assertTrue(Buildings.spec(recipe.requires).minTier <= recipe.minTier, "${recipe.output} is open before its ${recipe.requires}")
    }

    @Test
    fun `slots grow with the tier`() {
        assertEquals(listOf(2, 4, 7, 12), SettlementTier.entries.map { Buildings.slots(it) })
    }

    @Test
    fun `a building is refused for its tier, a full settlement, missing land or missing stock`() {
        val farmland = site(Resource.GRAIN)
        assertEquals(BuildResult.OK, Buildings.canBuild(homestead, farmland, Building.FARM, plenty))
        assertEquals(BuildResult.BUILT, Buildings.canBuild(homestead.copy(buildings = setOf(Building.FARM)), farmland, Building.FARM, plenty))
        assertEquals(BuildResult.TIER, Buildings.canBuild(homestead, farmland, Building.MINE, plenty))
        assertEquals(BuildResult.NO_SITE, Buildings.canBuild(homestead, site(Resource.WOOD), Building.FARM, plenty))
        assertEquals(BuildResult.NO_STOCK, Buildings.canBuild(homestead, farmland, Building.FARM) { 0 })
        val full = homestead.copy(buildings = setOf(Building.MILL, Building.WORKSHOP))
        assertEquals(BuildResult.FULL, Buildings.canBuild(full, farmland, Building.FARM, plenty))
        assertEquals(BuildResult.OK, Buildings.canBuild(full.copy(tier = SettlementTier.VILLAGE), farmland, Building.FARM, plenty))
    }

    @Test
    fun `a building that works on a resource needs it on the land, one that does not does not`() {
        assertEquals(BuildResult.OK, Buildings.canBuild(homestead, site(), Building.MILL, plenty))
        assertEquals(BuildResult.OK, Buildings.canBuild(village, site(Resource.GOLD), Building.MINE, plenty))
    }

    @Test
    fun `the status lists every building`() {
        val status = Buildings.status(homestead.copy(buildings = setOf(Building.MILL), idle = setOf(Building.MILL)), site(), plenty)
        assertEquals(Building.entries, status.map { it.building })
        val mill = status.first { it.building == Building.MILL }
        assertTrue(mill.built && mill.idle)
    }

    @Test
    fun `a working building boosts its resources, an idle one does not`() {
        val land = site(Resource.GRAIN)
        val plain = Gathering.income(Gathering.harvest(homestead, land, Deposits(FakePreferences()))).getValue(Resource.GRAIN)
        val farm = homestead.copy(buildings = setOf(Building.FARM))
        val boosted = Gathering.income(Gathering.harvest(farm, land, Deposits(FakePreferences()))).getValue(Resource.GRAIN)
        assertEquals(plain * (1f + Buildings.Tuning.GATHER_BONUS), boosted, 1e-5f)
        val idle = Gathering.income(Gathering.harvest(farm.copy(idle = setOf(Building.FARM)), land, Deposits(FakePreferences()))).getValue(Resource.GRAIN)
        assertEquals(plain, idle, 1e-5f)
    }

    @Test
    fun `a warehouse adds room for every item`() {
        val base = Gathering.capacity(listOf(village))
        assertEquals(base + Buildings.spec(Building.WAREHOUSE).storage, Gathering.capacity(listOf(village.copy(buildings = setOf(Building.WAREHOUSE)))))
    }

    @Test
    fun `a granary feeds more people, a shrine and a market lift the mood`() {
        val hungry = SettlementSite(IntArray(0), intArrayOf(2, 5, 0), false)
        val before = PopulationModel.served(village, hungry, Need.FOOD, emptySet())
        val granary = village.copy(buildings = setOf(Building.GRANARY))
        assertTrue(PopulationModel.served(granary, hungry, Need.FOOD, emptySet()) > before)
        assertEquals(before, PopulationModel.served(granary.copy(idle = setOf(Building.GRANARY)), hungry, Need.FOOD, emptySet()))
        val plain = HappinessModel.of(village, hungry, emptySet()).value
        assertTrue(HappinessModel.of(village.copy(buildings = setOf(Building.SHRINE, Building.MARKET)), hungry, emptySet()).value > plain)
    }

    @Test
    fun `crafting waits for its building`() {
        val crafter = Crafter(inventory)
        inventory.add(Resource.GRAIN, 10)
        val bare = Settlement(21, SettlementTier.HOMESTEAD, 5f)
        repeat(10) { crafter.tick(listOf(bare)) { 1f } }
        assertEquals(0, inventory.count(Product.FLOUR))
        assertTrue(crafter.status(bare, listOf(bare), 1f).first { it.recipe.output == Product.FLOUR }.unbuilt)
        assertNull(Crafting.line(crafter.status(bare, listOf(bare), 1f)))
        val mill = bare.copy(buildings = setOf(Building.MILL))
        repeat(10) { crafter.tick(listOf(mill)) { 1f } }
        assertTrue(inventory.count(Product.FLOUR) + inventory.count(Product.BREAD) > 0)
        assertFalse(crafter.status(mill, listOf(mill), 1f).first { it.recipe.output == Product.FLOUR }.unbuilt)
    }

    @Test
    fun `upkeep is paid every tenth tick, and what cannot be paid idles until it can`() {
        val upkeeper = Upkeeper(inventory)
        val settlement = homestead.copy(buildings = setOf(Building.FARM, Building.MILL))
        val every = Buildings.Tuning.UPKEEP_EVERY
        repeat(every - 1) { assertNull(upkeeper.tick(listOf(settlement))) }
        // Both need wood, one unit each: only one unit in store, so the first (by order) is paid and the second idles.
        inventory.add(Resource.WOOD, 1)
        assertEquals(mapOf(20 to setOf(Building.MILL)), upkeeper.tick(listOf(settlement)))
        assertEquals(0, inventory.count(Resource.WOOD))
        repeat(every - 1) { assertNull(upkeeper.tick(listOf(settlement))) }
        inventory.add(Resource.WOOD, 5)
        assertEquals(mapOf(20 to emptySet()), upkeeper.tick(listOf(settlement)))
        assertEquals(3, inventory.count(Resource.WOOD))
    }

    @Test
    fun `an upkeep payment is not saved by itself`() {
        val upkeeper = Upkeeper(inventory)
        inventory.add(Resource.WOOD, 5)
        val before = prefs.flushed.size
        repeat(Buildings.Tuning.UPKEEP_EVERY) { upkeeper.tick(listOf(homestead.copy(buildings = setOf(Building.MILL)))) }
        assertEquals(before, prefs.flushed.size)
    }

    private fun book(): SettlementBook {
        val sphere = GeodesicSphere.generate(8)
        val world = CompactWorld(SphereWorld(sphere, Array(sphere.faceCount) { Biome.PLAINS }), sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
        return SettlementBook(prefs).also { it.found(world, 20) }
    }

    @Test
    fun `buildings are saved per settlement with the world seed, and demolishing clears them`() {
        val book = book()
        book.build(20, Building.MILL)
        book.build(20, Building.FARM)
        assertEquals(setOf(Building.MILL, Building.FARM), SettlementBook(prefs).at(20)?.buildings)
        book.demolish(20, Building.MILL)
        assertEquals(setOf(Building.FARM), SettlementBook(prefs).at(20)?.buildings)
        prefs.putLong("seed", 99L)
        assertEquals(emptySet(), SettlementBook(prefs).at(20)?.buildings, "another world's buildings are not kept")
    }

    @Test
    fun `unknown names in a save are ignored and idle is never saved`() {
        val book = book()
        prefs.putString("buildings", "0/20:NOPE|MILL")
        val loaded = SettlementBook(prefs).at(20)
        assertEquals(setOf(Building.MILL), loaded?.buildings)
        assertEquals(emptySet(), loaded?.idle)
        book.build(20, Building.FARM)
        book.update { it.copy(idle = setOf(Building.FARM)) }
        assertEquals(emptySet(), SettlementBook(prefs).at(20)?.idle)
    }

    @Test
    fun `demolishing an idle building forgets that it was idle`() {
        val book = book()
        book.build(20, Building.FARM)
        book.update { it.copy(idle = setOf(Building.FARM)) }
        book.demolish(20, Building.FARM)
        book.build(20, Building.FARM)
        assertEquals(emptySet(), book.at(20)?.idle)
    }
}
