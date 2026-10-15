package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * Every settlement the player has founded in the current world, persisted in the given [Preferences] as one
 * string ("tile:tier:population,..."). Like [Inventory] it belongs to one world: GlobeScreen calls [clear] when a new
 * seed is made. Tile ids are stable for a seed and density, which is all a world is rebuilt from.
 */
class SettlementBook(private val preferences: Preferences) {
    private val byTile = LinkedHashMap<Int, Settlement>().also { map ->
        preferences.getString(KEY, "").split(',').forEach { entry ->
            val parts = entry.split(':')
            if (parts.size !in 2..3) return@forEach
            val tile = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val tier = SettlementTier.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
            // An entry saved before population existed has none: it starts like a new one.
            val population = parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: PopulationModel.START
            map[tile] = Settlement(tile, tier, population.coerceIn(PopulationModel.MIN, tier.populationCap.toFloat()))
        }
        // Allocations live under a key of their own, so a build that does not know them still reads every settlement.
        preferences.getString(ALLOCATION_KEY, "").split(',').forEach { entry ->
            val (tile, pairs) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val settlement = tile.toIntOrNull()?.let { map[it] } ?: return@forEach
            val allocation = pairs.split('|').mapNotNull { pair ->
                val (name, weight) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val resource = Resource.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                resource to (weight.toIntOrNull()?.coerceIn(0, Gathering.MAX_WEIGHT) ?: return@mapNotNull null)
            }.toMap()
            map[settlement.tile] = settlement.copy(allocation = allocation)
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

    /** Grows the settlement on [tile] into the next tier if the rules allow; returns why not otherwise. */
    fun upgrade(world: CompactWorld, tile: Int): UpgradeResult {
        val settlement = byTile[tile] ?: return UpgradeResult.MAX_TIER
        val result = SettlementRules.canUpgrade(world, settlement, all)
        if (result == UpgradeResult.OK) {
            byTile[tile] = settlement.copy(tier = settlement.tier.next!!)
            save(flush = true)
        }
        return result
    }

    /** Sets the weight of [resource] in where the settlement on [tile] puts its people (0 to [Gathering.MAX_WEIGHT]). */
    fun allocate(tile: Int, resource: Resource, weight: Int) {
        val settlement = byTile[tile] ?: return
        byTile[tile] = settlement.copy(allocation = settlement.allocation + (resource to weight.coerceIn(0, Gathering.MAX_WEIGHT)))
        write()
        preferences.flush()
    }

    /** Hands the settlement on [tile] back to the automatic split. */
    fun allocateAutomatically(tile: Int) {
        val settlement = byTile[tile]?.takeIf { it.allocation.isNotEmpty() } ?: return
        byTile[tile] = settlement.copy(allocation = emptyMap())
        write()
        preferences.flush()
    }

    /**
     * Replaces every settlement with [change] of it (a simulation tick). This is not a structural change, so
     * [version] stays and nothing is flushed: call [flush] now and then.
     */
    fun update(change: (Settlement) -> Settlement) {
        for ((tile, settlement) in byTile.entries.toList()) byTile[tile] = change(settlement)
        write()
    }

    fun flush() = preferences.flush()

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
        write()
        if (flush) preferences.flush()
    }

    private fun write() {
        if (byTile.isEmpty()) preferences.remove(KEY) else preferences.putString(KEY, byTile.values.joinToString(",") { "${it.tile}:${it.tier.name}:${it.population}" })
        val allocated = byTile.values.filter { it.allocation.isNotEmpty() }
        if (allocated.isEmpty()) preferences.remove(ALLOCATION_KEY) else preferences.putString(ALLOCATION_KEY, allocated.joinToString(",") { "${it.tile}:" + it.allocation.entries.joinToString("|") { (resource, weight) -> "${resource.name}=$weight" } })
    }

    private companion object {
        const val KEY = "settlements"
        const val ALLOCATION_KEY = "allocations"
    }
}
