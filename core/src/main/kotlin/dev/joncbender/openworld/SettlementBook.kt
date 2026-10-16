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
        // Allocations live under a key of their own, so a build that does not know them still reads every settlement. They
        // carry the seed of the world they were set in: a build that does not know them never clears them on a new world.
        val saved = preferences.getString(ALLOCATION_KEY, "").split('/', limit = 2)
        val current = saved.size == 2 && saved[0] == worldSeed().toString()
        (if (current) saved[1] else "").split(',').forEach { entry ->
            val (tile, pairs) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val settlement = tile.toIntOrNull()?.let { map[it] } ?: return@forEach
            val allocation = pairs.split('|').mapNotNull { pair ->
                val (name, weight) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val resource = Resource.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                resource to (weight.toIntOrNull()?.coerceIn(0, Gathering.MAX_WEIGHT) ?: return@mapNotNull null)
            }.toMap()
            map[settlement.tile] = settlement.copy(allocation = allocation)
        }
        // Switched-off recipes are kept the same way, under a key and a seed of their own.
        val savedOff = preferences.getString(RECIPES_KEY, "").split('/', limit = 2)
        val currentOff = savedOff.size == 2 && savedOff[0] == worldSeed().toString()
        (if (currentOff) savedOff[1] else "").split(',').forEach { entry ->
            val (tile, names) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val settlement = tile.toIntOrNull()?.let { map[it] } ?: return@forEach
            val off = names.split('|').mapNotNull { name -> Product.entries.firstOrNull { it.name == name } }.toSet()
            map[settlement.tile] = settlement.copy(recipesOff = off)
        }
        // And what has been built, the same way.
        val savedBuilt = preferences.getString(BUILDINGS_KEY, "").split('/', limit = 2)
        val currentBuilt = savedBuilt.size == 2 && savedBuilt[0] == worldSeed().toString()
        (if (currentBuilt) savedBuilt[1] else "").split(',').forEach { entry ->
            val (tile, names) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val settlement = tile.toIntOrNull()?.let { map[it] } ?: return@forEach
            val built = names.split('|').mapNotNull { name -> Building.entries.firstOrNull { it.name == name } }.toSet()
            map[settlement.tile] = settlement.copy(buildings = built)
        }
        // Those whose upkeep went unpaid, the same way: a restart must not revive them before the next payment.
        val savedIdle = preferences.getString(IDLE_KEY, "").split('/', limit = 2)
        val currentIdle = savedIdle.size == 2 && savedIdle[0] == worldSeed().toString()
        (if (currentIdle) savedIdle[1] else "").split(',').forEach { entry ->
            val (tile, names) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
            val settlement = tile.toIntOrNull()?.let { map[it] } ?: return@forEach
            val idle = names.split('|').mapNotNull { name -> Building.entries.firstOrNull { it.name == name } }.filter { it in settlement.buildings }.toSet()
            map[settlement.tile] = settlement.copy(idle = idle)
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

    /** Switches the recipe for [product] on or off for the settlement on [tile]. */
    fun craft(tile: Int, product: Product, on: Boolean) {
        val settlement = byTile[tile] ?: return
        byTile[tile] = settlement.copy(recipesOff = if (on) settlement.recipesOff - product else settlement.recipesOff + product)
        write()
        preferences.flush()
    }

    /** Adds [building] to the settlement on [tile]; the caller has checked [Buildings.canBuild] and taken the cost. */
    fun build(tile: Int, building: Building) {
        val settlement = byTile[tile] ?: return
        byTile[tile] = settlement.copy(buildings = settlement.buildings + building)
        write()
        preferences.flush()
    }

    /** Takes [building] down; nothing is refunded. [flush] false leaves saving to the caller, who changes the stockpile too. */
    fun demolish(tile: Int, building: Building, flush: Boolean = true) {
        val settlement = byTile[tile] ?: return
        byTile[tile] = settlement.copy(buildings = settlement.buildings - building, idle = settlement.idle - building)
        write()
        if (flush) preferences.flush()
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
        writeRecipes()
        writeBuildings()
        writeIdle()
        if (byTile.isEmpty()) preferences.remove(KEY) else preferences.putString(KEY, byTile.values.joinToString(",") { "${it.tile}:${it.tier.name}:${it.population}" })
        val allocated = byTile.values.filter { it.allocation.isNotEmpty() }
        if (allocated.isEmpty()) preferences.remove(ALLOCATION_KEY) else preferences.putString(ALLOCATION_KEY, worldSeed().toString() + "/" + allocated.joinToString(",") { "${it.tile}:" + it.allocation.entries.joinToString("|") { (resource, weight) -> "${resource.name}=$weight" } })
    }

    private fun writeIdle() {
        val idle = byTile.values.filter { it.idle.isNotEmpty() }
        if (idle.isEmpty()) preferences.remove(IDLE_KEY)
        else preferences.putString(IDLE_KEY, worldSeed().toString() + "/" + idle.joinToString(",") { "${it.tile}:" + it.idle.sortedBy { b -> b.ordinal }.joinToString("|") { b -> b.name } })
    }

    private fun writeBuildings() {
        val built = byTile.values.filter { it.buildings.isNotEmpty() }
        if (built.isEmpty()) preferences.remove(BUILDINGS_KEY)
        else preferences.putString(BUILDINGS_KEY, worldSeed().toString() + "/" + built.joinToString(",") { "${it.tile}:" + it.buildings.sortedBy { b -> b.ordinal }.joinToString("|") { b -> b.name } })
    }

    private fun writeRecipes() {
        val off = byTile.values.filter { it.recipesOff.isNotEmpty() }
        if (off.isEmpty()) preferences.remove(RECIPES_KEY)
        else preferences.putString(RECIPES_KEY, worldSeed().toString() + "/" + off.joinToString(",") { "${it.tile}:" + it.recipesOff.sortedBy { p -> p.ordinal }.joinToString("|") { p -> p.name } })
    }

    private fun worldSeed() = preferences.getLong(SEED_KEY, 0L)

    private companion object {
        // GlobeScreen's seed preference.
        const val SEED_KEY = "seed"
        const val KEY = "settlements"
        const val ALLOCATION_KEY = "allocations"
        const val RECIPES_KEY = "recipes_off"
        const val BUILDINGS_KEY = "buildings"
        const val IDLE_KEY = "buildings_idle"
    }
}
