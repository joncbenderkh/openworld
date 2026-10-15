package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HappinessTest {
    private val homestead = Settlement(20, SettlementTier.HOMESTEAD, 4f)

    private fun site(food: Int, deposits: List<Resource> = emptyList()) =
        SettlementSite(IntArray(0), intArrayOf(food, 0, 0), false, deposits.mapIndexed { i, r -> Deposit(i, 0, r) })

    @Test
    fun `fed people are content, and variety and luxuries raise it`() {
        val plain = HappinessModel.of(homestead, site(10), emptySet())
        assertEquals(Mood.CONTENT, plain.mood)
        val varied = HappinessModel.of(homestead, site(10, listOf(Resource.GRAIN, Resource.FISH, Resource.GAME)), setOf(Resource.GEMS, Resource.GOLD))
        assertEquals(Mood.HAPPY, varied.mood)
        assertTrue(varied.value > plain.value)
        assertTrue(varied.grievances.isEmpty())
    }

    @Test
    fun `a missing need is named and lowers happiness`() {
        val hungry = HappinessModel.of(homestead, site(0), emptySet())
        assertTrue(Grievance.NO_FOOD in hungry.grievances)
        assertEquals(Mood.UNREST, hungry.mood)
        val short = HappinessModel.of(homestead.copy(population = 10f), site(1), emptySet())
        assertTrue(Grievance.SHORT_FOOD in short.grievances)
        assertFalse(Grievance.NO_FOOD in short.grievances)
    }

    @Test
    fun `luxuries only count from the list`() {
        val grievances = HappinessModel.of(homestead, site(10), setOf(Resource.WOOD)).grievances
        assertTrue(Grievance.NO_LUXURIES in grievances)
        assertTrue(Grievance.FEW_LUXURIES in HappinessModel.of(homestead, site(10), setOf(Resource.GEMS)).grievances)
    }

    @Test
    fun `a full settlement is crowded`() {
        val roomy = HappinessModel.of(homestead, site(10), emptySet())
        val full = HappinessModel.of(homestead.copy(population = 10f), site(10), emptySet())
        assertTrue(Grievance.CROWDED in full.grievances)
        assertTrue(full.value < roomy.value)
    }

    @Test
    fun `output runs from none in unrest to full when content`() {
        assertEquals(0f, HappinessModel.of(homestead, site(0), emptySet()).output)
        assertEquals(1f, HappinessModel.of(homestead, site(10), emptySet()).output)
    }

    @Test
    fun `unrest stops gathering and makes people leave`() {
        val grain = Resource.GRAIN
        val starving = SettlementSite(IntArray(0), intArrayOf(0, 0, 0), false, listOf(Deposit(1, 0, grain)))
        val deposits = Deposits(FakePreferences())
        assertEquals(0f, Gathering.harvest(homestead, starving, deposits, 0f).sumOf { it.amount.toDouble() }.toFloat())
        assertTrue(PopulationModel.step(homestead, starving, emptySet()).population < homestead.population)
    }

    @Test
    fun `unhappy people grow more slowly than content ones`() {
        val village = Settlement(20, SettlementTier.VILLAGE, 27f)
        val unhappy = SettlementSite(IntArray(0), intArrayOf(3, 3, 0), false)
        // Satisfaction 0.56 would add 27 * 0.05 * 0.11 = 0.15 people, but happiness 0.48 holds that back.
        val grown = PopulationModel.step(village, unhappy, emptySet()).population - 27f
        assertTrue(grown > 0f)
        assertTrue(grown < 0.15f)
    }

    @Test
    fun `a starving settlement loses a tenth of its people a tick down to one`() {
        val starving = SettlementSite(IntArray(0), intArrayOf(0, 0, 0), false)
        var settlement = Settlement(20, SettlementTier.VILLAGE, 40f)
        settlement = PopulationModel.step(settlement, starving, emptySet())
        assertEquals(36f, settlement.population, 1e-4f)
        repeat(100) { settlement = PopulationModel.step(settlement, starving, emptySet()) }
        assertEquals(PopulationModel.MIN, settlement.population)
    }

    @Test
    fun `a luxury counts only once enough of it is stocked`() {
        val stocked = HappinessModel.stocked(mapOf(Resource.SPICES to 1, Resource.GEMS to HappinessModel.Tuning.STOCKED))
        assertEquals(setOf(Resource.GEMS), stocked)
    }

    @Test
    fun `stocked jewelry counts as a luxury`() {
        val grievances = HappinessModel.of(homestead, site(10), setOf(Product.JEWELRY)).grievances
        assertTrue(Grievance.FEW_LUXURIES in grievances)
        assertFalse(Grievance.NO_LUXURIES in grievances)
    }

    @Test
    fun `stocked clothing keeps a cold village warm without furs on its land`() {
        val village = Settlement(20, SettlementTier.VILLAGE, 10f)
        val cold = SettlementSite(IntArray(0), intArrayOf(10, 10, 0), true)
        assertTrue(Grievance.NO_WARMTH in HappinessModel.of(village, cold, emptySet()).grievances)
        assertEquals(1f, PopulationModel.served(village, cold, Need.WARMTH, setOf(Product.CLOTHING)))
        assertFalse(Grievance.NO_WARMTH in HappinessModel.of(village, cold, setOf(Product.CLOTHING)).grievances)
        assertEquals(1f, PopulationModel.satisfaction(village, cold, setOf(Product.CLOTHING)))
    }

    @Test
    fun `a need met by stocked clothing says so`() {
        val village = Settlement(20, SettlementTier.VILLAGE, 10f)
        val cold = SettlementSite(IntArray(0), intArrayOf(10, 10, 0), true)
        val warmth = PopulationModel.status(village, cold, UpgradeResult.TOO_SMALL, setOf(Product.CLOTHING)).needs.first { it.need == Need.WARMTH }
        assertTrue(warmth.met && warmth.byGoods)
        assertFalse(PopulationModel.status(village, cold, UpgradeResult.TOO_SMALL, emptySet()).needs.first { it.need == Need.WARMTH }.byGoods)
    }
}
