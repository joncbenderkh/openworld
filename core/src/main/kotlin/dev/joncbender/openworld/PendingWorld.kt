package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * A world the player asked for that could not be made yet (storage was full). It is saved so that the seed they were
 * promised, and the density they chose, are still there if the app is killed while they free some space.
 */
class PendingWorld(val seed: Long, val density: Float) {
    fun save(preferences: Preferences) {
        preferences.putLong(SEED, seed)
        preferences.putFloat(DENSITY, density)
        preferences.flush()
    }

    companion object {
        private const val SEED = "pending_seed"
        private const val DENSITY = "pending_density"

        fun load(preferences: Preferences): PendingWorld? =
            if (preferences.contains(SEED) && preferences.contains(DENSITY)) {
                PendingWorld(preferences.getLong(SEED), preferences.getFloat(DENSITY))
            } else {
                null
            }

        fun clear(preferences: Preferences) {
            preferences.remove(SEED)
            preferences.remove(DENSITY)
            preferences.flush()
        }
    }
}

/** What [PendingWorlds.retry] decided to build; [density] is null when the density stays as it is. */
class RetryPlan(val seed: Long, val density: Float?, val keepInventory: Boolean)

/**
 * The bookkeeping around a refused world: what is owed, what a retry builds, and when the debt is paid. Kept apart from
 * `GlobeScreen` so the rules can be tested without a GL context.
 */
class PendingWorlds(private val preferences: Preferences) {
    var pending: PendingWorld? = PendingWorld.load(preferences)
        private set

    /**
     * A regeneration was refused for want of storage. Building the current world again ([keepInventory]) is not a
     * replacement, so nothing new is owed.
     */
    fun refused(newSeed: Long, density: Float, keepInventory: Boolean) {
        if (!keepInventory) pending = PendingWorld(newSeed, density).also { it.save(preferences) }
    }

    /** A regeneration passed its storage check. Rebuilding the current world leaves a different refused one owed. */
    fun accepted(newSeed: Long, keepInventory: Boolean) {
        if (!keepInventory || newSeed == pending?.seed) drop()
    }

    /**
     * The world named by the error on screen, [shownSeed]: the refused one with the density it was asked for, else
     * the current world again with what was collected. The same seed is the same world, so its inventory stays too.
     */
    fun retry(shownSeed: Long, currentSeed: Long): RetryPlan {
        val wanted = pending?.takeIf { it.seed == shownSeed } ?: return RetryPlan(currentSeed, null, keepInventory = true)
        return RetryPlan(wanted.seed, wanted.density, keepInventory = wanted.seed == currentSeed)
    }

    /** The player closed the error for [shownSeed]: only a refused world with that seed is given up. */
    fun giveUp(shownSeed: Long) {
        if (pending?.seed == shownSeed) drop()
    }

    private fun drop() {
        if (pending == null) return
        pending = null
        PendingWorld.clear(preferences)
    }
}
