package dev.joncbender.openworld

/** How far a settlement has grown. [claimRadius] is in tiles (hops over the neighbor graph) around its own tile. */
enum class SettlementTier(val claimRadius: Int) {
    HOMESTEAD(1),
    VILLAGE(2),
    TOWN(3),
    CITY(4),
    ;

    /** The next tier up, or null at the top. */
    val next: SettlementTier? get() = entries.getOrNull(ordinal + 1)
}

/** A settlement on [tile] (a fine tile of the loaded world). */
data class Settlement(val tile: Int, val tier: SettlementTier)

/** Why a settlement can or cannot be founded on a tile. */
enum class FoundResult { OK, NOT_LAND, OPEN_OCEAN, CLAIMED }

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

    /** Whether a new homestead may go on [tile], given the [existing] settlements' claims. */
    fun canFound(world: CompactWorld, tile: Int, existing: Collection<Settlement>): FoundResult {
        if (world.isOpenOcean(tile)) return FoundResult.OPEN_OCEAN
        if (world.world[tile] in WATER) return FoundResult.NOT_LAND
        for (s in existing) {
            if (tile in claim(world, s.tile, s.tier.claimRadius)) return FoundResult.CLAIMED
        }
        return FoundResult.OK
    }
}
