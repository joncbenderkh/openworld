package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatheringTest {
    private val sphere = GeodesicSphere.generate(8)
    private val prefs = FakePreferences()
    private val inventory = Inventory(prefs)
    private val deposits = Deposits(prefs)
    private val gatherer = Gatherer(inventory, deposits)

    private fun world(resources: Map<Int, List<Resource>>): CompactWorld {
        val w = SphereWorld(sphere, Array(sphere.faceCount) { Biome.PLAINS })
        resources.forEach { (tile, list) -> w.setResources(tile, list) }
        return CompactWorld(w, sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
    }

    private val near = SettlementRules.claim(world(emptyMap()), 20, 1).first { it != 20 }

    private fun tickMany(world: CompactWorld, settlement: Settlement, ticks: Int) {
        val site = SettlementSite.of(world, settlement)
        repeat(ticks) { gatherer.tick(listOf(settlement), { site }) }
    }

    @Test
    fun `renewable resources are gathered tick after tick`() {
        val world = world(mapOf(near to listOf(Resource.GRAIN)))
        tickMany(world, Settlement(20, SettlementTier.HOMESTEAD, 10f), 40)
        // One deposit, ten people (more than it needs): 0.25 a tick.
        assertEquals(10, inventory.count(Resource.GRAIN))
    }

    @Test
    fun `fewer people work a deposit less`() {
        val world = world(mapOf(near to listOf(Resource.GRAIN)))
        val site = SettlementSite.of(world, Settlement(20, SettlementTier.HOMESTEAD, 1f))
        val full = Gathering.income(Gathering.harvest(Settlement(20, SettlementTier.HOMESTEAD, 10f), site, deposits))
        val half = Gathering.income(Gathering.harvest(Settlement(20, SettlementTier.HOMESTEAD, 1f), site, deposits))
        assertEquals(full.getValue(Resource.GRAIN) / 2, half.getValue(Resource.GRAIN), 1e-5f)
    }

    @Test
    fun `better tools gather more`() {
        val world = world(mapOf(near to listOf(Resource.GRAIN)))
        val site = SettlementSite.of(world, Settlement(20, SettlementTier.VILLAGE, 50f))
        val homestead = Gathering.income(Gathering.harvest(Settlement(20, SettlementTier.HOMESTEAD, 50f), site, deposits))
        val village = Gathering.income(Gathering.harvest(Settlement(20, SettlementTier.VILLAGE, 50f), site, deposits))
        assertTrue(village.getValue(Resource.GRAIN) > homestead.getValue(Resource.GRAIN))
    }

    @Test
    fun `a settlement too small cannot work a resource and lists it as locked`() {
        val world = world(mapOf(near to listOf(Resource.GOLD)))
        val settlement = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val site = SettlementSite.of(world, settlement)
        assertTrue(Gathering.harvest(settlement, site, deposits).isEmpty())
        assertEquals(listOf(Resource.GOLD), Gathering.locked(settlement, site))
        assertTrue(Gathering.locked(Settlement(20, SettlementTier.TOWN, 10f), site).isEmpty())
    }

    @Test
    fun `a finite deposit runs out and then yields nothing`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.CITY, 1000f)
        val roomy = Inventory(FakePreferences())
        val spender = Gatherer(roomy, deposits)
        val site = SettlementSite.of(world, settlement)
        repeat(2000) { spender.tick(listOf(settlement), { site }) }
        assertEquals(Gathering.DEPOSIT, roomy.count(Resource.STONE).toFloat(), 1f)
        assertTrue(Gathering.harvest(settlement, site, deposits).isEmpty())
    }

    @Test
    fun `renewable resources never deplete`() {
        val world = world(mapOf(near to listOf(Resource.WOOD)))
        val settlement = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val site = SettlementSite.of(world, settlement)
        tickMany(world, settlement, 100)
        assertEquals(Gathering.DEPOSIT, deposits.remaining(site.deposits.single()))
    }

    @Test
    fun `the stockpile stops at the storage of the tiers and a full resource is not worked`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val site = SettlementSite.of(world, settlement)
        tickMany(world, settlement, 1000)
        assertEquals(Gathering.storage(SettlementTier.HOMESTEAD), inventory.count(Resource.STONE))
        // About 50 units were mined (and a tick's worth at most over), not the 125 that 1000 ticks would yield.
        assertTrue(Gathering.DEPOSIT - deposits.remaining(site.deposits.single()) < 60f)
    }

    @Test
    fun `storage rises with the tier and adds up over settlements`() {
        assertTrue(SettlementTier.entries.zipWithNext().all { (a, b) -> Gathering.storage(a) < Gathering.storage(b) })
        assertEquals(Gathering.storage(SettlementTier.HOMESTEAD) + Gathering.storage(SettlementTier.TOWN),
            Gathering.capacity(listOf(Settlement(1, SettlementTier.HOMESTEAD), Settlement(2, SettlementTier.TOWN))))
    }

    @Test
    fun `income and lines name the resources`() {
        val world = world(mapOf(near to listOf(Resource.GRAIN, Resource.GOLD)))
        val settlement = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val economy = gatherer.economy(settlement, listOf(settlement)) { SettlementSite.of(world, settlement) }
        assertEquals("Per tick: Grain +0.3", economy.incomeLine())
        assertEquals("Needs a larger settlement: Gold", economy.lockedLine())
        assertEquals("Stockpile empty (holds 50 of each)", economy.stockLine())
        assertEquals("Gathers nothing yet", Economy(emptyMap(), emptyMap(), 50, emptyList()).incomeLine())
        assertNull(Economy(emptyMap(), emptyMap(), 50, emptyList()).lockedLine())
    }

    @Test
    fun `deposits persist and a damaged save is ignored`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.CITY, 1000f)
        val site = SettlementSite.of(world, settlement)
        deposits.mine(site.deposits.single(), 40f)
        deposits.flush()
        assertEquals(260f, Deposits(prefs).remaining(site.deposits.single()), 1e-4f)
        prefs.putString("deposits", "junk,-3:5,4:NaN,7:5:9,12:99999")
        val damaged = Deposits(prefs)
        // Only the well-formed entry (12 -> deposit of tile 3, slot 0) is read, clamped to the whole deposit.
        assertEquals(0f, damaged.remaining(Deposit(3, 0, Resource.STONE)), 1e-4f)
        assertEquals(Gathering.DEPOSIT, damaged.remaining(Deposit(3, 1, Resource.STONE)), 1e-4f)
        damaged.clear()
        assertEquals(Gathering.DEPOSIT, damaged.remaining(site.deposits.single()))
        assertFalse(prefs.contains("deposits"))
    }

    @Test
    fun `adding up to a limit reports what fitted`() {
        assertEquals(3, inventory.addUpTo(Resource.FISH, 5, 3))
        assertEquals(0, inventory.addUpTo(Resource.FISH, 5, 3))
        assertEquals(3, inventory.count(Resource.FISH))
    }

    @Test
    fun `nothing is mined that the store cannot hold and a full store shows no income`() {
        val world = world(mapOf(near to listOf(Resource.STONE, Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.CITY, 1000f)
        val site = SettlementSite.of(world, settlement)
        inventory.add(Resource.STONE, Gathering.storage(SettlementTier.CITY) - 1)
        gatherer.tick(listOf(settlement), { site })
        assertEquals(Gathering.storage(SettlementTier.CITY), inventory.count(Resource.STONE) + 0)
        val mined = site.deposits.sumOf { (Gathering.DEPOSIT - deposits.remaining(it)).toDouble() }
        assertEquals(1.0, mined, 1e-3)
        assertTrue(gatherer.economy(settlement, listOf(settlement)) { site }.income.isEmpty())
    }

    @Test
    fun `a thinly staffed finite deposit is spent exactly and then stops`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.CITY, 1f)
        val roomy = Inventory(FakePreferences())
        val spender = Gatherer(roomy, deposits)
        val site = SettlementSite.of(world, settlement)
        repeat(3000) { spender.tick(listOf(settlement), { site }) }
        assertTrue(Gathering.harvest(settlement, site, deposits).isEmpty())
        assertEquals(Gathering.DEPOSIT, roomy.count(Resource.STONE).toFloat(), 1f)
    }

    @Test
    fun `income near the cap is what fits and mining is saved with the inventory`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val settlement = Settlement(20, SettlementTier.CITY, 1000f)
        val site = SettlementSite.of(world, settlement)
        inventory.add(Resource.STONE, Gathering.storage(SettlementTier.CITY) - 1)
        assertEquals(1f, gatherer.economy(settlement, listOf(settlement)) { site }.income.getValue(Resource.STONE), 1e-4f)
        inventory.clear()
        gatherer.tick(listOf(settlement), { site })
        // No flush of the deposits: a flush of the shared preferences alone already carries the mining.
        assertEquals(Gathering.DEPOSIT - 2f, Deposits(prefs).remaining(site.deposits.single()), 1e-3f)
    }

    @Test
    fun `income of settlements shares the last room in tick order`() {
        val world = world(mapOf(near to listOf(Resource.STONE)))
        val first = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val second = Settlement(near, SettlementTier.HOMESTEAD, 10f)
        val third = Settlement(near + 100, SettlementTier.HOMESTEAD, 10f)
        val site = SettlementSite.of(world, first)
        val all = listOf(first, second, third)
        inventory.add(Resource.STONE, 3 * Gathering.storage(SettlementTier.HOMESTEAD) - 1)
        // One unit of room, half a unit apiece: the first two take it, the third gets nothing.
        assertEquals(0.5f, gatherer.economy(first, all) { site }.income.getValue(Resource.STONE), 1e-4f)
        assertEquals(0.5f, gatherer.economy(second, all) { site }.income.getValue(Resource.STONE), 1e-4f)
        assertEquals(emptyMap(), gatherer.economy(third, all) { site }.income)
    }
}
