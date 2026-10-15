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
    /** The settlement on this tile, if any. */
    val settlement: Settlement? = null,
    /** Whether a homestead may be founded here ([FoundResult.OK]) or why not. */
    val canFound: FoundResult = FoundResult.NOT_LAND,
    /** Population, needs and whether it can grow, for a settled tile. */
    val status: SettlementStatus? = null,
    /** Income, stockpile and locked resources, for a settled tile. */
    val economy: Economy? = null,
    /** Every recipe's standing in the settled tile, empty elsewhere. */
    val crafting: List<CraftStatus> = emptyList(),
)
