package dev.joncbender.openworld

/**
 * What's on a tapped tile - enough to show in a popup, or later drive actions. [isOpenOcean] marks
 * one of the large ocean tiles far from land (see [WorldCompactor]), as opposed to a fine tile.
 */
data class TileInfo(val faceIndex: Int, val biome: Biome, val resources: List<Resource>, val isOpenOcean: Boolean = false)
