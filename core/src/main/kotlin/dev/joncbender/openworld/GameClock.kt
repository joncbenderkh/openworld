package dev.joncbender.openworld

/** How fast the world runs: [factor] times real time, 0 when paused. */
enum class Speed(val factor: Int) { PAUSED(0), X1(1), X3(3) }

/**
 * Turns frame time into settlement ticks ([Buildings.Tuning.TICK_SECONDS] of game time each). It stands still while paused or
 * while something holds it (the settings sheet), and a long frame is not made up for - away time is [OfflineProgress]'s job.
 * Not saved: a speed with no control to undo it would be a trap.
 */
class GameClock {
    var speed = Speed.X1

    private val holds = HashSet<String>()
    private var elapsed = 0f

    /** Stops the clock while [on], for [reason]; every reason must be released before it runs again. */
    fun hold(reason: String, on: Boolean) {
        if (on) holds.add(reason) else holds.remove(reason)
    }

    /** True while something holds the clock; the offline replay waits too, though a paused [speed] does not stop it. */
    val held: Boolean get() = holds.isNotEmpty()

    val running: Boolean get() = speed != Speed.PAUSED && !held

    /** The whole ticks due after [delta] seconds of real time, at most [MAX_TICKS_PER_FRAME]. */
    fun advance(delta: Float): Int {
        if (!running) return 0
        elapsed += minOf(delta, TICK_SECONDS) * speed.factor
        val due = minOf((elapsed / TICK_SECONDS).toInt(), MAX_TICKS_PER_FRAME)
        elapsed = minOf(elapsed - due * TICK_SECONDS, TICK_SECONDS)
        return due
    }

    companion object {
        const val MAX_TICKS_PER_FRAME = 3
        private const val TICK_SECONDS = Buildings.Tuning.TICK_SECONDS
    }
}
