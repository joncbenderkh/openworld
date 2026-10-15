package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * How much of each [Item] (raw [Resource] or crafted [Product]) the player has. Keyed generically by
 * the item itself (never one field per item), so a new item added later works here without any changes.
 *
 * Persists across app restarts via the given [Preferences] (same store
 * GlobeScreen uses for settings, under an "inventory_" key prefix), but
 * belongs to one world, not the app install - see [clear], called whenever
 * GlobeScreen switches to a new seed (not when it only has to rebuild the same world's cache).
 */
class Inventory(private val preferences: Preferences) {

    // Crafted goods are saved with the seed of their world: a build that does not know them never clears them on a new world.
    private val productsCurrent = preferences.getLong(PRODUCTS_SEED_KEY, NONE) == worldSeed()

    private val counts: MutableMap<Item, Int> =
        Items.all.associateWithTo(mutableMapOf()) { if (it is Product && !productsCurrent) 0 else preferences.getInteger(keyFor(it), 0) }

    init {
        // The old world's goods are dropped for good: the next save of one product restamps the seed for all of them.
        if (!productsCurrent) Product.entries.forEach { preferences.remove(keyFor(it)) }
    }

    fun count(item: Item): Int = counts.getValue(item)

    fun add(item: Item, amount: Int = 1) {
        require(amount > 0) { "amount must be positive, was $amount" }
        set(item, counts.getValue(item) + amount)
    }

    /** Adds up to [amount] without going past [limit] and without saving (call [flush]); returns how much was added. */
    fun addUpTo(item: Item, amount: Int, limit: Int): Int {
        val added = minOf(amount, limit - counts.getValue(item))
        if (added <= 0) return 0
        counts[item] = counts.getValue(item) + added
        save(item)
        return added
    }

    fun flush() = preferences.flush()

    /** Removes [amount] without saving (call [flush]), like [addUpTo]: a batch that takes and gives must reach the store together. False, and unchanged, if there is not enough. */
    fun take(item: Item, amount: Int): Boolean {
        require(amount > 0) { "amount must be positive, was $amount" }
        val current = counts.getValue(item)
        if (current < amount) return false
        counts[item] = current - amount
        save(item)
        return true
    }

    /** Removes up to [amount], returning true if there was enough; leaves the count unchanged otherwise. */
    fun remove(item: Item, amount: Int = 1): Boolean {
        require(amount > 0) { "amount must be positive, was $amount" }
        val current = counts.getValue(item)
        if (current < amount) return false
        set(item, current - amount)
        return true
    }

    /** Every item currently held, in a positive amount. */
    fun held(): Map<Item, Int> = counts.filterValues { it > 0 }

    /** Resets every count to zero - called when a new world is generated. [flush] false leaves saving to the caller. */
    fun clear(flush: Boolean = true) {
        for (item in Items.all) {
            counts[item] = 0
            preferences.remove(keyFor(item))
        }
        preferences.remove(PRODUCTS_SEED_KEY)
        if (flush) preferences.flush()
    }

    private fun set(item: Item, amount: Int) {
        counts[item] = amount
        save(item)
        preferences.flush()
    }

    private fun save(item: Item) {
        preferences.putInteger(keyFor(item), counts.getValue(item))
        if (item is Product) preferences.putLong(PRODUCTS_SEED_KEY, worldSeed())
    }

    // GlobeScreen's seed preference.
    private fun worldSeed() = preferences.getLong("seed", 0L)

    private fun keyFor(item: Item) = "inventory_${item.name}"

    private companion object {
        const val PRODUCTS_SEED_KEY = "inventory_products_seed"
        const val NONE = Long.MIN_VALUE
    }
}
