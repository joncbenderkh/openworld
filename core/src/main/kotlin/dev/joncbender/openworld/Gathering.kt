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

/** What a settlement takes in each tick, what is stored, and what its land holds that it cannot work yet. */
data class Economy(
    val income: Map<Resource, Float>,
    val stock: Map<Resource, Int>,
    val capacity: Int,
    val locked: List<Resource>,
) {
    fun incomeLine(): String =
        if (income.isEmpty()) "Gathers nothing yet" else "Per tick: " + income.entries.joinToString(", ") { "${it.key.displayName()} +${"%.1f".format(it.value)}" }

    fun stockLine(): String =
        if (stock.isEmpty()) "Stockpile empty (holds $capacity of each)" else "Stockpile: " + stock.entries.joinToString(", ") { "${it.key.displayName()} ${it.value}" } + " (holds $capacity of each)"

    fun lockedLine(): String? =
        if (locked.isEmpty()) null else "Needs a larger settlement: " + locked.joinToString(", ") { it.displayName() }

    private fun Resource.displayName() = name.lowercase().replaceFirstChar(Char::uppercase).replace('_', ' ')
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

    /** One deposit's yield in a tick. */
    class Harvest(val deposit: Deposit, val amount: Float)

    /** What each workable deposit of the site yields this tick. Exhausted finite deposits yield nothing. */
    fun harvest(settlement: Settlement, site: SettlementSite, deposits: Deposits): List<Harvest> {
        val workable = site.deposits.filter { spec(it.resource).minTier <= settlement.tier && (spec(it.resource).renewable || deposits.remaining(it) > EXHAUSTED) }
        if (workable.isEmpty()) return emptyList()
        val effort = minOf(1f, settlement.population / (workable.size * PEOPLE_PER_DEPOSIT))
        return workable.map {
            val rate = if (spec(it.resource).renewable) RENEWABLE_RATE else FINITE_RATE
            val amount = rate * effort * tools(settlement.tier)
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

    fun tick(settlements: Collection<Settlement>, siteOf: (Settlement) -> SettlementSite) {
        val capacity = Gathering.capacity(settlements)
        for (settlement in settlements) {
            for (harvest in Gathering.harvest(settlement, siteOf(settlement), deposits)) {
                val resource = harvest.deposit.resource
                // Only what the store still has room for is taken, so no finite deposit is spent on units that are lost.
                val amount = minOf(harvest.amount, capacity - inventory.count(resource) - carry[resource.ordinal])
                if (amount <= 0f) continue
                deposits.mine(harvest.deposit, amount)
                carry[resource.ordinal] += amount
            }
        }
        deposits.stage()
        for (resource in Resource.entries) {
            val whole = floor(carry[resource.ordinal]).toInt()
            if (whole <= 0) continue
            inventory.addUpTo(resource, whole, capacity)
            carry[resource.ordinal] -= whole
        }
    }

    /** Income leaves out a resource whose store is full: nothing of it is gathered. */
    fun economy(settlement: Settlement, site: SettlementSite, settlements: Collection<Settlement>): Economy {
        val capacity = Gathering.capacity(settlements)
        val working = Gathering.harvest(settlement, site, deposits).filter { inventory.count(it.deposit.resource) < capacity }
        // What the store has room for, as the tick clips it.
        val income = Gathering.income(working).mapValues { (resource, amount) -> minOf(amount, capacity - inventory.count(resource) - carry[resource.ordinal]) }
            .filterValues { it > 0f }
        return Economy(income, inventory.heldResources(), capacity, Gathering.locked(settlement, site))
    }


    fun clear() = carry.fill(0f)
}
