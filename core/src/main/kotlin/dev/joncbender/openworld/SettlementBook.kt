package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * Every settlement the player has founded in the current world, persisted in the given [Preferences] as one
 * string ("tile:tier,tile:tier"). Like [Inventory] it belongs to one world: GlobeScreen calls [clear] when a new
 * seed is made. Tile ids are stable for a seed and density, which is all a world is rebuilt from.
 */
class SettlementBook(private val preferences: Preferences) {
    private val byTile = LinkedHashMap<Int, Settlement>().also { map ->
        preferences.getString(KEY, "").split(',').forEach { entry ->
            val (tile, tier) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val parsed = Settlement(tile.toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach, SettlementTier.entries.firstOrNull { it.name == tier } ?: return@forEach)
            map[parsed.tile] = parsed
        }
    }

    /** Bumped on every change, so a drawing layer can tell when to rebuild. */
    var version = 0
        private set

    val all: Collection<Settlement> get() = byTile.values

    fun at(tile: Int): Settlement? = byTile[tile]

    /** Founds a homestead on [tile] if the rules allow; returns why not otherwise. */
    fun found(world: CompactWorld, tile: Int): FoundResult {
        val result = SettlementRules.canFound(world, tile, all)
        if (result == FoundResult.OK) {
            byTile[tile] = Settlement(tile, SettlementTier.HOMESTEAD)
            save(flush = true)
        }
        return result
    }

    /** Drops any settlement whose tile is not a fine tile of the loaded world (a damaged save, say). */
    fun retainWithin(fineCount: Int) {
        if (byTile.keys.removeAll { it >= fineCount }) save(flush = true)
    }

    /** Resets to no settlements - a new world. [flush] false leaves saving to the caller. */
    fun clear(flush: Boolean = true) {
        byTile.clear()
        save(flush)
    }

    private fun save(flush: Boolean) {
        version++
        if (byTile.isEmpty()) preferences.remove(KEY) else preferences.putString(KEY, byTile.values.joinToString(",") { "${it.tile}:${it.tier.name}" })
        if (flush) preferences.flush()
    }

    private companion object {
        const val KEY = "settlements"
    }
}
