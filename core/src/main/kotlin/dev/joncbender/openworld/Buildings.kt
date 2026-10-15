package dev.joncbender.openworld

/** What a settlement can build on its land. See [Buildings] for what each one costs and does. */
enum class Building { FARM, FISHERY, LUMBER_CAMP, QUARRY, MINE, SMELTER, WORKSHOP, MILL, MARKET, GRANARY, WAREHOUSE, SHRINE }

/**
 * What one building costs and gives. [cost] is paid once from the stockpile; [upkeep] again every [Buildings.Tuning.UPKEEP_EVERY]
 * ticks, and a building that cannot be paid idles until the next payment. [boosts] are the resources it makes more of, and it
 * needs one of them on a claimed tile. A building also enables the recipes that [Recipe.requires] it. [storage] is extra room
 * for every item, [foodUnits] extra food supply and [mood] a gain in happiness.
 */
data class BuildingSpec(
    val cost: Map<Item, Int>,
    val upkeep: Map<Item, Int>,
    val minTier: SettlementTier,
    val boosts: Set<Resource> = emptySet(),
    val storage: Int = 0,
    val foodUnits: Int = 0,
    val mood: Float = 0f,
)

/** Why a building can or cannot be built now. */
enum class BuildResult { OK, BUILT, TIER, FULL, NO_SITE, NO_STOCK }

/** One building as the panel shows it: [built], [idle] (upkeep unpaid) and whether it could be built [now]. */
data class BuildingStatus(val building: Building, val built: Boolean, val idle: Boolean, val now: BuildResult)

object Buildings {
    object Tuning {
        /** The gain in yield for a resource a working building boosts. */
        const val GATHER_BONUS = 0.5f

        /** Settlement ticks between upkeep payments. */
        const val UPKEEP_EVERY = 10

        /** Buildings a settlement of each tier can hold, by tier. */
        val SLOTS = intArrayOf(2, 4, 7, 12)
    }

    private fun items(vararg pairs: Pair<Item, Int>): Map<Item, Int> = mapOf(*pairs)

    fun spec(building: Building): BuildingSpec = when (building) {
        Building.FARM -> BuildingSpec(items(Resource.WOOD to 10), items(Resource.WOOD to 1), SettlementTier.HOMESTEAD, boosts = setOf(Resource.GRAIN))
        Building.FISHERY -> BuildingSpec(items(Resource.WOOD to 10), items(Resource.WOOD to 1), SettlementTier.HOMESTEAD, boosts = setOf(Resource.FISH, Resource.WHALES))
        Building.LUMBER_CAMP -> BuildingSpec(items(Resource.WOOD to 8, Resource.STONE to 4), items(Resource.STONE to 1), SettlementTier.HOMESTEAD, boosts = setOf(Resource.WOOD))
        Building.QUARRY -> BuildingSpec(items(Resource.WOOD to 10, Resource.STONE to 5), items(Resource.WOOD to 1), SettlementTier.HOMESTEAD, boosts = setOf(Resource.STONE))
        Building.MINE -> BuildingSpec(items(Resource.WOOD to 15, Resource.STONE to 10), items(Resource.WOOD to 1), SettlementTier.VILLAGE, boosts = setOf(Resource.ORE, Resource.GOLD, Resource.GEMS))
        Building.SMELTER -> BuildingSpec(items(Resource.STONE to 15, Resource.WOOD to 10), items(Resource.WOOD to 2), SettlementTier.VILLAGE)
        Building.WORKSHOP -> BuildingSpec(items(Resource.WOOD to 20, Resource.STONE to 10), items(Resource.WOOD to 1), SettlementTier.HOMESTEAD)
        Building.MILL -> BuildingSpec(items(Resource.WOOD to 15, Resource.STONE to 10), items(Resource.WOOD to 1), SettlementTier.HOMESTEAD)
        Building.MARKET -> BuildingSpec(items(Resource.WOOD to 25, Resource.STONE to 15), items(Resource.GRAIN to 2), SettlementTier.TOWN, mood = 0.05f)
        Building.GRANARY -> BuildingSpec(items(Resource.WOOD to 20, Resource.STONE to 10), items(Resource.GRAIN to 1), SettlementTier.VILLAGE, foodUnits = 2)
        Building.WAREHOUSE -> BuildingSpec(items(Resource.WOOD to 25, Resource.STONE to 15), items(Resource.WOOD to 1), SettlementTier.VILLAGE, storage = 100)
        Building.SHRINE -> BuildingSpec(items(Resource.STONE to 20), items(Resource.STONE to 1), SettlementTier.VILLAGE, mood = 0.1f)
    }

    /** What [building] does, in a few words for a panel. */
    fun effect(building: Building): String {
        val spec = spec(building)
        val parts = ArrayList<String>()
        if (spec.boosts.isNotEmpty()) parts += "+${(Tuning.GATHER_BONUS * 100).toInt()}% " + spec.boosts.joinToString(", ") { it.displayName().lowercase() }
        val makes = Recipes.ALL.filter { it.requires == building }.map { it.output.displayName().lowercase() }
        if (makes.isNotEmpty()) parts += "makes " + makes.joinToString(", ")
        if (spec.storage > 0) parts += "+${spec.storage} storage"
        if (spec.foodUnits > 0) parts += "feeds ${(spec.foodUnits * PopulationModel.PEOPLE_PER_UNIT).toInt()} more"
        if (spec.mood > 0f) parts += "happier people"
        return parts.joinToString(", ")
    }

    /** [items] as "15 wood + 10 stone". */
    fun itemsText(items: Map<Item, Int>): String = items.entries.joinToString(" + ") { (item, units) -> "$units ${item.displayName().lowercase()}" }

    /** How many buildings a settlement of [tier] holds. */
    fun slots(tier: SettlementTier): Int = Tuning.SLOTS[tier.ordinal]

    /** Resources the working buildings of [settlement] make more of. */
    fun boosted(settlement: Settlement): Set<Resource> = settlement.working().flatMapTo(HashSet()) { spec(it).boosts }

    /** Room the built buildings add to every item's store. */
    fun storage(settlement: Settlement): Int = settlement.buildings.sumOf { spec(it).storage }

    /** Food units the working buildings add. */
    fun foodUnits(settlement: Settlement): Int = settlement.working().sumOf { spec(it).foodUnits }

    /** Happiness the working buildings add. */
    fun mood(settlement: Settlement): Float = settlement.working().sumOf { spec(it).mood.toDouble() }.toFloat()

    /** Whether [building] may be built now in [settlement] on [site], paying from the stock [count] reads. */
    fun canBuild(settlement: Settlement, site: SettlementSite, building: Building, count: (Item) -> Int): BuildResult {
        val spec = spec(building)
        return when {
            building in settlement.buildings -> BuildResult.BUILT
            spec.minTier > settlement.tier -> BuildResult.TIER
            settlement.buildings.size >= slots(settlement.tier) -> BuildResult.FULL
            spec.boosts.isNotEmpty() && site.deposits.none { it.resource in spec.boosts } -> BuildResult.NO_SITE
            spec.cost.any { (item, units) -> count(item) < units } -> BuildResult.NO_STOCK
            else -> BuildResult.OK
        }
    }

    fun status(settlement: Settlement, site: SettlementSite, count: (Item) -> Int): List<BuildingStatus> =
        Building.entries.map { BuildingStatus(it, it in settlement.buildings, it in settlement.idle, canBuild(settlement, site, it, count)) }
}

/**
 * Pays the buildings' upkeep out of the [inventory] every [Buildings.Tuning.UPKEEP_EVERY] ticks, a building at a time and
 * all or nothing, settlements in order, so a short stockpile idles the later ones. Nothing is saved here; GlobeScreen flushes.
 */
class Upkeeper(private val inventory: Inventory) {
    private var ticks = 0

    /** The buildings left idle for the next [Buildings.Tuning.UPKEEP_EVERY] ticks per settlement tile, on a payment tick; null between payments. */
    fun tick(settlements: Collection<Settlement>): Map<Int, Set<Building>>? {
        if (++ticks % Buildings.Tuning.UPKEEP_EVERY != 0) return null
        return settlements.associate { settlement ->
            settlement.tile to settlement.buildings.sortedBy { it.ordinal }.filterNotTo(LinkedHashSet()) { pay(it) }
        }
    }

    private fun pay(building: Building): Boolean {
        val upkeep = Buildings.spec(building).upkeep
        if (upkeep.any { (item, units) -> inventory.count(item) < units }) return false
        for ((item, units) in upkeep) inventory.take(item, units)
        return true
    }

    fun clear() {
        ticks = 0
    }
}
