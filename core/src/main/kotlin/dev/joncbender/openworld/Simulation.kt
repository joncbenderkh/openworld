package dev.joncbender.openworld

/** What time away did, for whatever shows it: the stock change per item, each settlement's people before and after, and the buildings that went idle. */
data class AwayReport(
    val ticks: Int,
    val stock: Map<Item, Int>,
    val settlements: List<SettlementChange>,
    val idle: Map<Int, Set<Building>>,
)

data class SettlementChange(val tile: Int, val before: Float, val after: Float)

/**
 * The settlement tick: upkeep, gathering, crafting, then growth, in that order, from one reading of the stockpile. [advance] does
 * any number of ticks at once (rates times ticks, clamped by the store, the deposits, the inputs and the tier cap), and one tick
 * is [tick]; time away is [away], which splits it into a few such steps. Nothing is saved or shown here: the caller flushes and
 * refreshes the panel.
 */
class Simulation(
    private val settlements: SettlementBook,
    private val inventory: Inventory,
    private val gatherer: Gatherer,
    private val crafter: Crafter,
    private val upkeeper: Upkeeper,
    private val siteOf: (Settlement) -> SettlementSite,
) {
    fun tick() = advance(1)

    fun advance(ticks: Int) {
        if (ticks <= 0) return
        // One reading of the stockpile for the whole step, so gathering and growth see the same mood.
        val held = HappinessModel.stocked(inventory.held())
        // One tick pays first: a building that could not be paid does nothing from this tick on, here and in the panel.
        if (ticks == 1) payUpkeep(1)
        // Everyone's mood is read once, from the settlements as upkeep left them, and shared by gathering, crafting and growth.
        val mood = HashMap<Int, Happiness>(settlements.all.size * 2)
        for (settlement in settlements.all) mood[settlement.tile] = HappinessModel.of(settlement, siteOf(settlement), held)
        // Gather before growing: the people who work this step's deposits are the ones counted in the panel.
        gatherer.tick(settlements.all, siteOf, { mood.getValue(it.tile).output }, ticks)
        // Several ticks pay after the step's income is in: the buildings worked the whole step, and many payments made from the stock the step began with would idle them though the income covers them.
        if (ticks > 1) payUpkeep(ticks)
        crafter.tick(settlements.all, ticks) { mood.getValue(it.tile).output }
        settlements.update { PopulationModel.step(it, siteOf(it), held, mood.getValue(it.tile), ticks) }
    }

    private fun payUpkeep(ticks: Int) {
        upkeeper.advance(settlements.all, ticks)?.let { idle -> settlements.update { it.copy(idle = idle[it.tile].orEmpty()) } }
    }

    /**
     * [ticks] ticks of time away, applied in steps with the moods, stores and idle buildings read again between them. The steps
     * double in size from one tick (growth and a filling store change fastest at the start, and a step uses the rates it begins with),
     * up to an eighth of the time and to what the store can pay in upkeep at once, so a few dozen steps at most for a store that holds plenty.
     */
    fun away(ticks: Int): AwayReport {
        val stockBefore = inventory.held()
        val peopleBefore = settlements.all.associate { it.tile to it.population }
        val idleBefore = settlements.all.associate { it.tile to it.idle }
        var left = maxOf(ticks, 0)
        var step = 1
        val longest = maxOf(1, ticks / LONGEST_SHARE)
        while (left > 0) {
            val now = minOf(step, longest, left, longestToPay())
            advance(now)
            left -= now
            step = minOf(step * 2, longest)
        }
        val stockAfter = inventory.held()
        return AwayReport(
            ticks = maxOf(ticks, 0),
            stock = (stockBefore.keys + stockAfter.keys).associateWith { (stockAfter[it] ?: 0) - (stockBefore[it] ?: 0) }.filterValues { it != 0 },
            settlements = settlements.all.map { SettlementChange(it.tile, peopleBefore[it.tile] ?: it.population, it.population) },
            idle = settlements.all.associate { it.tile to it.idle - idleBefore[it.tile].orEmpty() }.filterValues { it.isNotEmpty() },
        )
    }

    /**
     * The longest step whose upkeep the store can hold at once: income past the store's room is lost, and a step pays all its cycles
     * together, so a step owing more of an item than the store holds would idle buildings the income covers.
     */
    private fun longestToPay(): Int {
        val perCycle = HashMap<Item, Int>()
        for (settlement in settlements.all) for (building in settlement.buildings) {
            for ((item, units) in Buildings.spec(building).upkeep) perCycle.merge(item, units, Int::plus)
        }
        val heaviest = perCycle.values.maxOrNull() ?: return Int.MAX_VALUE
        val cycles = maxOf(1, Gathering.capacity(settlements.all) / heaviest)
        return cycles * Buildings.Tuning.UPKEEP_EVERY
    }

    companion object {
        /** No step is longer than this share of the time away. */
        const val LONGEST_SHARE = 8
    }
}
