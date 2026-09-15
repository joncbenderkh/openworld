package dev.joncbender.openworld

/** What's on a tapped tile - enough to show in a popup, or later drive actions. */
data class TileInfo(val faceIndex: Int, val biome: Biome, val resource: Resource?)
