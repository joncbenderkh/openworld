package dev.joncbender.openworld

import kotlin.math.floor

/**
 * How a resource is gathered. A renewable one regrows as fast as it is taken, so a tile gives its slow, steady
 * [Gathering.RENEWABLE_RATE] for ever; a finite one yields faster but each deposit holds only [Gathering.DEPOSIT] units. A settlement
 * below [minTier] cannot work it at all.
 */
data class Gatherable(val renewable: Boolean, val minTier: SettlementTier)

/** One resource slot of one tile (a tile holds up to [SphereWorld.MAX_RESOURCES]): the unit of gathering and of depletion. */
data class Deposit(val tile: Int, val slot: Int, val resource: Resource) {
    val key: Int get() = tile * SphereWorld.MAX_RESOURCES + slot
}

/**
 * How one resource's workers are decided: [weight] is its claim on the population, relative to the other workable
 * resources' weights ([explicit] when the player set it, else the number of [deposits] it has), and [people] the share of the
 * population that comes to. A resource whose store is [full] is not worked, so it takes no people and does not count in the split.
 */
data class WorkShare(val resource: Resource, val deposits: Int, val weight: Int, val explicit: Boolean, val people: Float, val full: Boolean = false)

/** What a settlement takes in each tick, what is stored, and what its land holds that it cannot work yet. */
data class Economy(
    val income: Map<Resource, Float>,
    val stock: Map<Item, Int>,
    val capacity: Int,
    val locked: List<Resource>,
    val shares: List<WorkShare> = emptyList(),
) {
    fun incomeLine(): String =
        if (income.isEmpty()) "Gathers nothing yet" else "Per tick: " + listed(income.entries.map { "${it.key.displayName()} +${String.format(java.util.Locale.ROOT, "%.1f", it.value)}" })

    fun stockLine(): String =
        if (stock.isEmpty()) "Stockpile empty (holds $capacity of each)"
        else "Stockpile: " + listed(stock.entries.sortedByDescending { it.value }.map { "${it.key.displayName()} ${it.value}" }) + " (holds $capacity of each)"

    fun lockedLine(): String? =
        if (locked.isEmpty()) null else "Needs a larger settlement: " + listed(locked.map { it.displayName() })

    // The panel does not scroll, so a long list is cut short: at most [SHOWN] entries, then a count of the rest.
    private fun listed(entries: List<String>): String =
        if (entries.size <= SHOWN) entries.joinToString(", ") else entries.take(SHOWN).joinToString(", ") + " and ${entries.size - SHOWN} more"

    private companion object {
        const val SHOWN = 4
    }
}

/**
 * Gathering. Pure like [PopulationModel]: what a settlement yields in a tick depends only on it, its site and what
 * the deposits still hold. Each unit on a claimed tile it can work takes [PEOPLE_PER_DEPOSIT] people to work fully; a
 * smaller population works every deposit proportionally less. Tools improve with the tier.
 */
object Gathering {
    const val RENEWABLE_RATE = 0.25f
    const val FINITE_RATE = 0.5f
    const val DEPOSIT = 300f
    const val PEOPLE_PER_DEPOSIT = 2f

    /** The gain in yield while the stockpile holds tools (see [Crafting]). */
    const val CRAFTED_TOOLS = 0.25f

    /** The most weight the player can give one resource. */
    const val MAX_WEIGHT = 99

    // A finite deposit holding less than this counts as spent, so float rounding cannot leave it workable for ever.
    private const val EXHAUSTED = 0.001f

    private val TOOLS = floatArrayOf(1f, 1.5f, 2.5f, 4f)
    private val STORAGE = intArrayOf(50, 150, 500, 2000)

    fun spec(resource: Resource): Gatherable = when (resource) {
        Resource.FISH, Resource.GRAIN, Resource.WOOD, Resource.GAME, Resource.FURS,
        Resource.EXOTIC_FRUIT, Resource.SPICES, Resource.PEAT,
        -> Gatherable(true, SettlementTier.HOMESTEAD)
        Resource.WHALES -> Gatherable(true, SettlementTier.VILLAGE)
        Resource.STONE -> Gatherable(false, SettlementTier.HOMESTEAD)
        Resource.ORE, Resource.PEARLS, Resource.ICE_CRYSTALS, Resource.SULFUR -> Gatherable(false, SettlementTier.VILLAGE)
        Resource.GOLD, Resource.GEMS, Resource.OIL, Resource.OBSIDIAN -> Gatherable(false, SettlementTier.TOWN)
    }

    /** How much of each resource a settlement of this tier adds to the shared stockpile's room. */
    fun storage(tier: SettlementTier): Int = STORAGE[tier.ordinal]

    fun tools(tier: SettlementTier): Float = TOOLS[tier.ordinal]

    /** What stocked [Product.TOOLS] add to every yield: [CRAFTED_TOOLS] more. */
    fun toolsFactor(held: Set<Item>): Float = if (Product.TOOLS in held) 1f + CRAFTED_TOOLS else 1f

    /** One deposit's yield in a tick. */
    class Harvest(val deposit: Deposit, val amount: Float)

    private fun workable(settlement: Settlement, site: SettlementSite, deposits: Deposits): List<Deposit> =
        site.deposits.filter { spec(it.resource).minTier <= settlement.tier && (spec(it.resource).renewable || deposits.remaining(it) > EXHAUSTED) }

    private fun shares(settlement: Settlement, workable: List<Deposit>, wanted: (Resource) -> Boolean): List<WorkShare> {
        val counts = workable.groupingBy { it.resource }.eachCount().toSortedMap()
        val weights = counts.mapValues { (resource, count) -> settlement.allocation[resource] ?: count }
        val total = weights.filterKeys(wanted).values.sum()
        return counts.map { (resource, count) ->
            val weight = weights.getValue(resource)
            val full = !wanted(resource)
            WorkShare(resource, count, weight, resource in settlement.allocation, if (!full && total > 0) settlement.population * weight / total else 0f, full)
        }
    }

    /** Where the people of [settlement] work, for every resource it can work on its land; resources not [wanted] (a full store) get none. */
    fun shares(settlement: Settlement, site: SettlementSite, deposits: Deposits, wanted: (Resource) -> Boolean = { true }): List<WorkShare> =
        shares(settlement, workable(settlement, site, deposits), wanted)

    /**
     * What each workable deposit of the site yields this tick. Exhausted finite deposits and resources not [wanted] (a full store)
     * are not worked, so they take none of the people. The rest share the population by the [shares] weights, and a resource's
     * people are spread evenly over its deposits. [output] is the settlement's happiness factor, 0 in unrest, and [toolsFactor] what crafted tools add.
     */
    fun harvest(settlement: Settlement, site: SettlementSite, deposits: Deposits, output: Float = 1f, toolsFactor: Float = 1f, wanted: (Resource) -> Boolean = { true }): List<Harvest> {
        val workable = workable(settlement, site, deposits).filter { wanted(it.resource) }
        if (workable.isEmpty()) return emptyList()
        val people = shares(settlement, workable) { true }.associate { it.resource to it.people / it.deposits }
        return workable.map {
            val effort = minOf(1f, people.getValue(it.resource) / PEOPLE_PER_DEPOSIT)
            val rate = if (spec(it.resource).renewable) RENEWABLE_RATE else FINITE_RATE
            val amount = rate * effort * tools(settlement.tier) * toolsFactor * output
            Harvest(it, if (spec(it.resource).renewable) amount else minOf(amount, deposits.remaining(it)))
        }
    }

    /** Resources on the site's tiles that [settlement] is too small to work. */
    fun locked(settlement: Settlement, site: SettlementSite): List<Resource> =
        site.deposits.map { it.resource }.filter { spec(it).minTier > settlement.tier }.distinct()

    fun income(harvests: List<Harvest>): Map<Resource, Float> =
        harvests.groupBy { it.deposit.resource }.mapValues { (_, list) -> list.sumOf { it.amount.toDouble() }.toFloat() }.toSortedMap()

    /** The room for each resource in the shared stockpile: every settlement adds its tier's storage. */
    fun capacity(settlements: Collection<Settlement>): Int = settlements.sumOf { storage(it.tier) }
}

/**
 * Gathers for every settlement into the [inventory] on each tick. Fractions of a unit wait in [carry] until whole;
 * they are not saved (a restart loses under one unit of each). A resource whose stockpile is full is not worked, so a
 * finite deposit is not spent on units that could not be stored.
 */
class Gatherer(private val inventory: Inventory, private val deposits: Deposits) {
    private val carry = FloatArray(Resource.entries.size)

    /** What each settlement takes this tick, and who works what, in order, with the shared room handed out first come first served. */
    private class Plan(val taken: List<Pair<Settlement, Gathering.Harvest>>, val shares: Map<Int, List<WorkShare>>)

    private fun plan(settlements: Collection<Settlement>, siteOf: (Settlement) -> SettlementSite): Plan {
        val room = FloatArray(Resource.entries.size) { Gathering.capacity(settlements) - inventory.count(Resource.entries[it]) - carry[it] }
        val held = HappinessModel.stocked(inventory.held())
        val taken = ArrayList<Pair<Settlement, Gathering.Harvest>>()
        val shares = HashMap<Int, List<WorkShare>>()
        for (settlement in settlements) {
            val site = siteOf(settlement)
            // The same room test for the shares as for the harvest, before this settlement takes any.
            shares[settlement.tile] = Gathering.shares(settlement, site, deposits) { room[it.ordinal] > 0f }
            val output = HappinessModel.of(settlement, site, held).output
            for (harvest in Gathering.harvest(settlement, site, deposits, output, Gathering.toolsFactor(held)) { room[it.ordinal] > 0f }) {
                val r = harvest.deposit.resource.ordinal
                // Only what the store still has room for is taken, so no finite deposit is spent on units that are lost.
                val amount = minOf(harvest.amount, room[r])
                if (amount <= 0f) continue
                room[r] -= amount
                taken += settlement to Gathering.Harvest(harvest.deposit, amount)
            }
        }
        return Plan(taken, shares)
    }

    fun tick(settlements: Collection<Settlement>, siteOf: (Settlement) -> SettlementSite) {
        val capacity = Gathering.capacity(settlements)
        for ((_, harvest) in plan(settlements, siteOf).taken) {
            deposits.mine(harvest.deposit, harvest.amount)
            carry[harvest.deposit.resource.ordinal] += harvest.amount
        }
        deposits.stage()
        for (resource in Resource.entries) {
            val whole = floor(carry[resource.ordinal]).toInt()
            if (whole <= 0) continue
            inventory.addUpTo(resource, whole, capacity)
            carry[resource.ordinal] -= whole
        }
    }

    /** What [settlement] will take on the next tick, as [tick] hands out the room, plus the store and what is locked. */
    fun economy(settlement: Settlement, settlements: Collection<Settlement>, siteOf: (Settlement) -> SettlementSite): Economy {
        val plan = plan(settlements, siteOf)
        val income = Gathering.income(plan.taken.filter { it.first == settlement }.map { it.second })
        return Economy(income, inventory.held(), Gathering.capacity(settlements), Gathering.locked(settlement, siteOf(settlement)), plan.shares[settlement.tile].orEmpty())
    }

    fun clear() = carry.fill(0f)
}
