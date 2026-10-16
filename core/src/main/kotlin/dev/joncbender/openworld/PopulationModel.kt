package dev.joncbender.openworld

/** What a settlement's people need. Each is met by units of the listed resources found on the tiles it claims. */
enum class Need(val sources: Set<Resource>) {
    FOOD(setOf(Resource.FISH, Resource.GRAIN, Resource.GAME, Resource.EXOTIC_FRUIT)),
    SHELTER(setOf(Resource.WOOD, Resource.STONE)),
    WARMTH(setOf(Resource.FURS, Resource.PEAT, Resource.WOOD)),
}

/**
 * What a settlement has to work with: the tiles it claims and, per [Need], how many resource units lie on them.
 * Found again only when the settlement or the world changes. A resource that meets two needs (wood) counts toward both.
 */
class SettlementSite(val tiles: IntArray, private val units: IntArray, val cold: Boolean, val deposits: List<Deposit> = emptyList()) {
    fun supply(need: Need): Int = units[need.ordinal]

    companion object {
        private val COLD = setOf(Biome.TAIGA, Biome.TUNDRA, Biome.ARCTIC)

        fun of(world: CompactWorld, settlement: Settlement): SettlementSite {
            val tiles = SettlementRules.claim(world, settlement.tile, settlement.tier.claimRadius)
            val units = IntArray(Need.entries.size)
            val deposits = ArrayList<Deposit>()
            for (tile in tiles) world.world.resourcesAt(tile).forEachIndexed { slot, resource ->
                deposits += Deposit(tile, slot, resource)
                for (need in Need.entries) if (resource in need.sources) units[need.ordinal]++
            }
            return SettlementSite(tiles, units, world.world[settlement.tile] in COLD, deposits)
        }
    }
}

enum class Trend { GROWING, STEADY, FULL, DECLINING }

/** How one need stands: [supported] people can be served from the claimed tiles. */
data class NeedStatus(val need: Need, val supported: Int, val met: Boolean, val byGoods: Boolean = false)

/** Everything the panel shows about a settlement. */
data class SettlementStatus(
    val settlement: Settlement,
    val needs: List<NeedStatus>,
    val trend: Trend,
    val upgrade: UpgradeResult,
    val happiness: Happiness,
)

/**
 * Population growth. Pure: the result depends only on the settlement and its site, so it can be tested and later
 * stepped by any clock. Growth is [GROWTH_RATE] of the population per tick, scaled by `2 * satisfaction - 1`: full at
 * every need met, none at half, decline below. Satisfaction is that of the worst-met need.
 */
object PopulationModel {
    const val START = 2f
    const val MIN = 1f
    const val UPGRADE_SHARE = 0.9f
    const val PEOPLE_PER_UNIT = 5f
    const val GROWTH_RATE = 0.05f

    // Below this a settlement would grow by a fraction of a person a minute.
    private const val GROWTH_BASE = 4f

    fun needs(tier: SettlementTier, cold: Boolean): List<Need> = when {
        tier == SettlementTier.HOMESTEAD -> listOf(Need.FOOD)
        cold -> listOf(Need.FOOD, Need.SHELTER, Need.WARMTH)
        else -> listOf(Need.FOOD, Need.SHELTER)
    }

    /** Resource units serving [need]: those on the land, and a working granary's for food. */
    fun supply(settlement: Settlement, site: SettlementSite, need: Need): Int =
        site.supply(need) + if (need == Need.FOOD) Buildings.foodUnits(settlement) else 0

    fun supported(settlement: Settlement, site: SettlementSite, need: Need): Int = (supply(settlement, site, need) * PEOPLE_PER_UNIT).toInt()

    /** Share of the population [need] serves, 0..1. Stocked [Product.CLOTHING] covers warmth, wherever the people live. */
    fun served(settlement: Settlement, site: SettlementSite, need: Need, held: Set<Item>): Float =
        if (need == Need.WARMTH && Product.CLOTHING in held) 1f
        else minOf(1f, supply(settlement, site, need) * PEOPLE_PER_UNIT / settlement.population)

    /** Share of the population the worst-met need serves, 0..1. */
    fun satisfaction(settlement: Settlement, site: SettlementSite, held: Set<Item>): Float =
        needs(settlement.tier, site.cold).minOf { served(settlement, site, it, held) }

    /**
     * [ticks] ticks of growth at once. A tick changes the population by `base * k`, `base` being the larger of the people and
     * [GROWTH_BASE] and `k` the growth (or, in unrest, the leaving) rate, so over a stretch it is exponential once the people reach
     * the base and linear below it, then held between [MIN] and the tier's cap. One tick is exactly the old step.
     */
    fun step(settlement: Settlement, site: SettlementSite, held: Set<Item>, happiness: Happiness = HappinessModel.of(settlement, site, held), ticks: Int = 1): Settlement {
        val k = when {
            happiness.mood == Mood.UNREST -> -HappinessModel.Tuning.LEAVE_RATE
            else -> GROWTH_RATE * (2 * satisfaction(settlement, site, held) - 1).let { if (it > 0f) it * happiness.output else it }
        }
        var people = settlement.population
        var left = ticks
        if (people < GROWTH_BASE && k != 0f) {
            val toBase = if (k > 0f) kotlin.math.ceil((GROWTH_BASE - people) / (GROWTH_BASE * k)).toInt() else Int.MAX_VALUE
            if (toBase >= left) {
                people += GROWTH_BASE * k * left
                left = 0
            } else {
                people += GROWTH_BASE * k * toBase
                left -= toBase
            }
        }
        if (left > 0) people *= Math.pow((1f + k).toDouble(), left.toDouble()).toFloat()
        return settlement.copy(population = people.coerceIn(MIN, settlement.tier.populationCap.toFloat()))
    }

    fun status(settlement: Settlement, site: SettlementSite, upgrade: UpgradeResult, held: Set<Item>): SettlementStatus {
        val needs = needs(settlement.tier, site.cold).map { NeedStatus(it, supported(settlement, site, it), served(settlement, site, it, held) >= 1f, it == Need.WARMTH && Product.CLOTHING in held) }
        val satisfaction = satisfaction(settlement, site, held)
        val happiness = HappinessModel.of(settlement, site, held)
        val trend = when {
            happiness.mood == Mood.UNREST -> Trend.DECLINING
            satisfaction < 0.5f -> Trend.DECLINING
            settlement.population >= settlement.tier.populationCap -> Trend.FULL
            satisfaction > 0.5f && happiness.output > 0f -> Trend.GROWING
            else -> Trend.STEADY
        }
        return SettlementStatus(settlement, needs, trend, upgrade, happiness)
    }
}
