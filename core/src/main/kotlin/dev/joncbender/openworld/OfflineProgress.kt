package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * Remembers when the world was last played and says how many ticks the time away is worth, capped at [Tuning.CAP_TICKS] and
 * at full rate. The ticks themselves are replayed by the caller through [Simulation], not computed here. The wall time is stamped
 * with the world's seed, so a stamp from another world is worth nothing.
 */
class OfflineProgress(private val preferences: Preferences, private val now: () -> Long = System::currentTimeMillis) {

    object Tuning {
        const val CAP_SECONDS = 4 * 3600
        const val CAP_TICKS = (CAP_SECONDS / Buildings.Tuning.TICK_SECONDS).toInt()
    }

    /** Notes that the world of [seed] was last played now; saved with the next flush. */
    fun stamp(seed: Long) {
        preferences.putLong(TIME_KEY, now())
        preferences.putLong(SEED_KEY, seed)
    }

    /** The ticks the time since the last [stamp] of this world is worth: none for another world, a clock gone backwards or no stamp. */
    fun pending(seed: Long): Int {
        if (!preferences.contains(TIME_KEY) || preferences.getLong(SEED_KEY, 0L) != seed) return 0
        return ticksBetween(preferences.getLong(TIME_KEY, 0L), now())
    }

    fun clear() {
        preferences.remove(TIME_KEY)
        preferences.remove(SEED_KEY)
    }

    companion object {
        private const val TIME_KEY = "clock_saved_ms"
        private const val SEED_KEY = "clock_saved_seed"

        fun ticksBetween(savedMillis: Long, nowMillis: Long): Int {
            val elapsed = nowMillis - savedMillis
            if (elapsed <= 0) return 0
            return minOf(elapsed / (Buildings.Tuning.TICK_SECONDS * 1000).toLong(), Tuning.CAP_TICKS.toLong()).toInt()
        }
    }
}
