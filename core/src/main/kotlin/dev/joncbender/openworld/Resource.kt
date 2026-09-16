package dev.joncbender.openworld

/** A special resource a tile can hold, alongside its biome. Data only for now - no tile art yet. */
enum class Resource {
    FISH, WHALES, PEARLS,
    WOOD, FURS, GAME, GRAIN,
    GOLD, GEMS, ORE, STONE,
    OIL, SPICES, EXOTIC_FRUIT, PEAT, ICE_CRYSTALS,
}

/**
 * Which resources can turn up on a biome's tiles. Every biome has at least
 * one entry - see ResourceTest for a check that this stays true as biomes
 * are added.
 */
val BIOME_RESOURCES: Map<Biome, List<Resource>> = mapOf(
    Biome.OCEAN to listOf(Resource.FISH, Resource.WHALES, Resource.PEARLS),
    Biome.RIVER to listOf(Resource.FISH),
    Biome.LAKE to listOf(Resource.FISH, Resource.PEARLS),
    Biome.DESERT to listOf(Resource.GOLD, Resource.OIL, Resource.STONE),
    Biome.SAVANNAH to listOf(Resource.GAME, Resource.GRAIN, Resource.SPICES),
    Biome.PLAINS to listOf(Resource.GAME, Resource.GRAIN),
    Biome.FOREST to listOf(Resource.WOOD, Resource.GAME),
    Biome.JUNGLE to listOf(Resource.GEMS, Resource.SPICES, Resource.EXOTIC_FRUIT),
    Biome.DEEP_FOREST to listOf(Resource.WOOD, Resource.EXOTIC_FRUIT),
    Biome.TAIGA to listOf(Resource.WOOD, Resource.FURS),
    Biome.SWAMP to listOf(Resource.OIL, Resource.PEAT),
    Biome.MOUNTAIN to listOf(Resource.GOLD, Resource.GEMS, Resource.ORE, Resource.STONE),
    Biome.FOOTHILLS to listOf(Resource.ORE, Resource.STONE, Resource.GAME),
    Biome.TUNDRA to listOf(Resource.FURS, Resource.ORE, Resource.STONE),
    Biome.ARCTIC to listOf(Resource.ICE_CRYSTALS),
)
