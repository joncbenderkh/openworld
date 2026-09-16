package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * How much of each [Resource] the player has collected. Keyed generically by
 * the enum itself (never one field per resource), so a new resource type
 * added to [Resource] later works here without any changes.
 *
 * Persists across app restarts via the given [Preferences] (same store
 * GlobeScreen uses for settings, under an "inventory_" key prefix), but
 * belongs to one world, not the app install - see [clear], called whenever
 * GlobeScreen generates a new world.
 */
class Inventory(private val preferences: Preferences) {

    private val counts: MutableMap<Resource, Int> =
        Resource.entries.associateWithTo(mutableMapOf()) { preferences.getInteger(keyFor(it), 0) }

    fun count(resource: Resource): Int = counts.getValue(resource)

    fun add(resource: Resource, amount: Int = 1) {
        require(amount > 0) { "amount must be positive, was $amount" }
        set(resource, counts.getValue(resource) + amount)
    }

    /** Removes up to [amount], returning true if there was enough; leaves the count unchanged otherwise. */
    fun remove(resource: Resource, amount: Int = 1): Boolean {
        require(amount > 0) { "amount must be positive, was $amount" }
        val current = counts.getValue(resource)
        if (current < amount) return false
        set(resource, current - amount)
        return true
    }

    /** Every resource currently held, in a positive amount. */
    fun heldResources(): Map<Resource, Int> = counts.filterValues { it > 0 }

    /** Resets every count to zero - called when a new world is generated. */
    fun clear() {
        for (resource in Resource.entries) {
            counts[resource] = 0
            preferences.remove(keyFor(resource))
        }
        preferences.flush()
    }

    private fun set(resource: Resource, amount: Int) {
        counts[resource] = amount
        preferences.putInteger(keyFor(resource), amount)
        preferences.flush()
    }

    private fun keyFor(resource: Resource) = "inventory_${resource.name}"
}
