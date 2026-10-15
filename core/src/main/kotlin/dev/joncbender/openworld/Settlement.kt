package dev.joncbender.openworld

/**
 * How far a settlement has grown. [claimRadius] is in tiles (hops over the neighbor graph) around its own tile;
 * [populationCap] is the most people it holds.
 */
enum class SettlementTier(val claimRadius: Int, val populationCap: Int) {
    HOMESTEAD(1, 10),
    VILLAGE(2, 50),
    TOWN(3, 250),
    CITY(4, 1000),
    ;

    /** The next tier up, or null at the top. */
    val next: SettlementTier? get() = entries.getOrNull(ordinal + 1)
}

/**
 * A settlement on [tile] (a fine tile of the loaded world) with [population] people; fractional, as growth is.
 * [allocation] is the player's weight for where those people work, per resource (see [Gathering.shares]); a resource
 * without one gets the automatic weight, so the default - an empty map - spreads the people evenly over the deposits.
 * [recipesOff] are the products the player has switched off (see [Crafting]); by default every recipe the tier allows runs.
 * [buildings] are what it has built (see [Buildings]); [idle] are those whose upkeep went unpaid, which are not saved and do nothing.
 */
data class Settlement(
    val tile: Int,
    val tier: SettlementTier,
    val population: Float = PopulationModel.START,
    val allocation: Map<Resource, Int> = emptyMap(),
    val recipesOff: Set<Product> = emptySet(),
    val buildings: Set<Building> = emptySet(),
    val idle: Set<Building> = emptySet(),
) {
    /** The buildings that work now: built, and not idle for want of upkeep. */
    fun working(): Set<Building> = buildings - idle
}

/** Why a settlement can or cannot be founded on a tile. */
enum class FoundResult { OK, NOT_LAND, OPEN_OCEAN, CLAIMED }

/** Why a settlement can or cannot grow into the next tier. */
enum class UpgradeResult { OK, MAX_TIER, TOO_SMALL, BLOCKED }

object SettlementRules {
    private val WATER = setOf(Biome.OCEAN, Biome.RIVER, Biome.LAKE)

    /**
     * The tiles a settlement of [radius] on [tile] claims, its own tile included. Open-ocean tiles (ids from
     * `fineCount` on) are never claimed: they are huge, and nothing there can be worked.
     */
    fun claim(world: CompactWorld, tile: Int, radius: Int): IntArray {
        val sphere = world.world.sphere
        val seen = HashSet<Int>()
        val claimed = ArrayList<Int>()
        var ring = listOf(tile)
        seen.add(tile)
        claimed.add(tile)
        repeat(radius) {
            val next = ArrayList<Int>()
            for (t in ring) sphere.forEachNeighbor(t) { n ->
                if (n < world.fineCount && seen.add(n)) {
                    next.add(n)
                    claimed.add(n)
                }
            }
            ring = next
        }
        return claimed.toIntArray()
    }

    /** Whether a new homestead may go on [tile]: its claim must not touch the [existing] settlements' claims. */
    fun canFound(world: CompactWorld, tile: Int, existing: Collection<Settlement>): FoundResult {
        if (world.isOpenOcean(tile)) return FoundResult.OPEN_OCEAN
        if (world.world[tile] in WATER) return FoundResult.NOT_LAND
        return if (overlapsClaims(world, claim(world, tile, SettlementTier.HOMESTEAD.claimRadius), existing)) FoundResult.CLAIMED else FoundResult.OK
    }

    /** Whether [settlement] may grow: big enough, not at the top, and its larger claim clear of the [others]' claims. */
    fun canUpgrade(world: CompactWorld, settlement: Settlement, others: Collection<Settlement>): UpgradeResult {
        val next = settlement.tier.next ?: return UpgradeResult.MAX_TIER
        if (settlement.population < settlement.tier.populationCap * PopulationModel.UPGRADE_SHARE) return UpgradeResult.TOO_SMALL
        val grown = claim(world, settlement.tile, next.claimRadius)
        return if (overlapsClaims(world, grown, others.filter { it.tile != settlement.tile })) UpgradeResult.BLOCKED else UpgradeResult.OK
    }

    private fun overlapsClaims(world: CompactWorld, claimed: IntArray, settlements: Collection<Settlement>): Boolean {
        val mine = claimed.toHashSet()
        return settlements.any { s -> claim(world, s.tile, s.tier.claimRadius).any { it in mine } }
    }
}
