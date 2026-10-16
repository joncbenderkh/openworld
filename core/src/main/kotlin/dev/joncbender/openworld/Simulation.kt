package dev.joncbender.openworld

/**
 * One settlement tick: upkeep, gathering, crafting, then growth, in that order, all from one reading of the stockpile. The live clock
 * and the offline replay both call [tick], so away time plays out exactly as played time would. Nothing is saved or shown here:
 * the caller flushes and refreshes the panel.
 */
class Simulation(
    private val settlements: SettlementBook,
    private val inventory: Inventory,
    private val gatherer: Gatherer,
    private val crafter: Crafter,
    private val upkeeper: Upkeeper,
    private val siteOf: (Settlement) -> SettlementSite,
) {
    fun tick() {
        // One reading of the stockpile for the whole tick, so gathering and growth see the same mood.
        val held = HappinessModel.stocked(inventory.held())
        // Upkeep first: a building that could not be paid does nothing from this tick on, here and in the panel.
        upkeeper.tick(settlements.all)?.let { idle -> settlements.update { it.copy(idle = idle[it.tile].orEmpty()) } }
        // Gather before growing: the people who work this tick's deposits are the ones counted in the panel.
        gatherer.tick(settlements.all, siteOf)
        crafter.tick(settlements.all) { HappinessModel.of(it, siteOf(it), held).output }
        settlements.update { PopulationModel.step(it, siteOf(it), held) }
    }
}
