package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/**
 * How much has been taken from each [Deposit], persisted in the given [Preferences] as one string
 * ("key:taken,..."). Only deposits that were worked appear; the rest hold their full [Gathering.DEPOSIT]. Like
 * [Inventory] it belongs to one world: GlobeScreen calls [clear] when a new seed is made. Mining is staged with [stage] after each tick and saved by [flush].
 */
class Deposits(private val preferences: Preferences) {
    private val taken = HashMap<Int, Float>().also { map ->
        preferences.getString(KEY, "").split(',').forEach { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@forEach
            val key = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val amount = parts[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return@forEach
            map[key] = minOf(amount, Gathering.DEPOSIT)
        }
    }
    private var dirty = false

    /** What a finite deposit still holds, or [Gathering.DEPOSIT] untouched. */
    fun remaining(deposit: Deposit): Float = Gathering.DEPOSIT - (taken[deposit.key] ?: 0f)

    fun mine(deposit: Deposit, amount: Float) {
        if (Gathering.spec(deposit.resource).renewable) return
        taken[deposit.key] = minOf(Gathering.DEPOSIT, (taken[deposit.key] ?: 0f) + amount)
        dirty = true
    }

    /** Hands what was mined to the preferences without saving, so any flush of them (the settings sheet's, say) keeps it consistent with the inventory. */
    fun stage() {
        if (!dirty) return
        dirty = false
        if (taken.isEmpty()) preferences.remove(KEY) else preferences.putString(KEY, taken.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    fun flush() {
        stage()
        preferences.flush()
    }

    /** Resets every deposit to full - a new world. [flush] false leaves saving to the caller. */
    fun clear(flush: Boolean = true) {
        taken.clear()
        dirty = false
        preferences.remove(KEY)
        if (flush) preferences.flush()
    }

    private companion object {
        const val KEY = "deposits"
    }
}
