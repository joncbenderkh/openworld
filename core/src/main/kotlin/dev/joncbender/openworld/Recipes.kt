package dev.joncbender.openworld

/**
 * One crafting recipe: [amount] of [output] from [inputs] (item to units), a batch taking [ticks] settlement ticks of
 * work, for a settlement of at least [minTier] that has built [requires]. Recipes are data: the model reads this table and nothing else.
 */
data class Recipe(
    val output: Product,
    val amount: Int,
    val inputs: Map<Item, Int>,
    val ticks: Int,
    val minTier: SettlementTier,
    val requires: Building,
)

object Recipes {
    val ALL: List<Recipe> = listOf(
        Recipe(Product.FLOUR, 1, mapOf(Resource.GRAIN to 2), 3, SettlementTier.HOMESTEAD, Building.MILL),
        Recipe(Product.BREAD, 1, mapOf(Product.FLOUR to 1), 3, SettlementTier.HOMESTEAD, Building.MILL),
        Recipe(Product.PRESERVED_FISH, 2, mapOf(Resource.FISH to 2, Resource.STONE to 1), 3, SettlementTier.HOMESTEAD, Building.FISHERY),
        Recipe(Product.CLOTHING, 1, mapOf(Resource.FURS to 2), 4, SettlementTier.HOMESTEAD, Building.WORKSHOP),
        Recipe(Product.METAL, 1, mapOf(Resource.ORE to 2, Resource.WOOD to 1), 4, SettlementTier.VILLAGE, Building.SMELTER),
        Recipe(Product.TOOLS, 1, mapOf(Product.METAL to 1, Resource.WOOD to 1), 6, SettlementTier.VILLAGE, Building.WORKSHOP),
        Recipe(Product.BUILDING_MATERIALS, 2, mapOf(Resource.STONE to 2, Resource.WOOD to 1), 4, SettlementTier.VILLAGE, Building.WORKSHOP),
        Recipe(Product.FUEL, 1, mapOf(Resource.PEAT to 2), 3, SettlementTier.VILLAGE, Building.SMELTER),
        Recipe(Product.JEWELRY, 1, mapOf(Resource.GOLD to 1, Resource.GEMS to 1), 8, SettlementTier.TOWN, Building.WORKSHOP),
        Recipe(Product.EXPLOSIVES, 2, mapOf(Resource.SULFUR to 2, Product.FUEL to 1), 6, SettlementTier.TOWN, Building.SMELTER),
    )

    /** The recipe that makes [product]; there is one for each. */
    fun making(product: Product): Recipe = ALL.first { it.output == product }
}
