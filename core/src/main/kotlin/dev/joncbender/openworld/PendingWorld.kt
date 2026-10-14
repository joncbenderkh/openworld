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
