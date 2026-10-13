package dev.joncbender.openworld

/**
 * What's on a tapped tile - enough to show in a popup, or later drive actions. [isOpenOcean] marks
 * one of the large ocean tiles far from land (see [WorldCompactor]), as opposed to a fine tile.
 * [latitudeDeg] and [longitudeDeg] locate the tile's center (see [TileCoordinates]).
 */
data class TileInfo(
    val faceIndex: Int,
    val biome: Biome,
    val resources: List<Resource>,
    val isOpenOcean: Boolean = false,
    val latitudeDeg: Float = 0f,
    val longitudeDeg: Float = 0f,
)
