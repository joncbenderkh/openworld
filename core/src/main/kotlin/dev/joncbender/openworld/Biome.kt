package dev.joncbender.openworld

import com.badlogic.gdx.graphics.Color

/** Each biome's base color, used both for its procedural tile texture (see BiomeTextures) and as the tint under it. */
enum class Biome(val color: Color) {
    OCEAN(Color(0.11f, 0.29f, 0.55f, 1f)),
    RIVER(Color(0.25f, 0.55f, 0.80f, 1f)),
    LAKE(Color(0.20f, 0.45f, 0.75f, 1f)),
    DESERT(Color(0.87f, 0.75f, 0.42f, 1f)),
    SAVANNAH(Color(0.76f, 0.70f, 0.30f, 1f)),
    PLAINS(Color(0.62f, 0.78f, 0.38f, 1f)),
    FOREST(Color(0.16f, 0.45f, 0.20f, 1f)),
    JUNGLE(Color(0.05f, 0.50f, 0.25f, 1f)),
    DEEP_FOREST(Color(0.08f, 0.28f, 0.12f, 1f)),
    TAIGA(Color(0.18f, 0.38f, 0.35f, 1f)),
    SWAMP(Color(0.30f, 0.40f, 0.28f, 1f)),
    MOUNTAIN(Color(0.55f, 0.53f, 0.50f, 1f)),
    FOOTHILLS(Color(0.50f, 0.47f, 0.36f, 1f)),
    VOLCANO(Color(0.30f, 0.12f, 0.08f, 1f)),
    TUNDRA(Color(0.58f, 0.56f, 0.42f, 1f)),
    ARCTIC(Color(0.92f, 0.94f, 0.97f, 1f)),
}
