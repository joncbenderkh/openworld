package dev.joncbender.openworld

import java.util.concurrent.CancellationException

/** Thrown at a checkpoint after [GenerationProgress.cancel]; nothing a cancelled generation made is kept. */
class GenerationCancelled : CancellationException("world generation cancelled")

/**
 * Where a world being made has got to, and the one place it can be called off. Generation reports each [Step] when it
 * is done ([done]), which is also where a [cancel] takes effect, so the delay is the longest single step.
 * [onChange] gets the share done, the estimate, and the label of the step now under way (null after the last).
 * Safe to share between the thread making the world and the one watching it.
 *
 * The weights are each step's share of a cold start, measured on the phone on 2026-10-14 (13.4 s to generate and
 * save; `adb logcat -s perf`). Only the ETA depends on them being close; the emulator's shares differ a lot.
 */
class GenerationProgress(
    private val clock: () -> Long = System::nanoTime,
    private val onChange: (fraction: Float, etaMillis: Long?, next: String?) -> Unit = { _, _, _ -> },
) {
    enum class Step(val weight: Int, val label: String) {
        SPHERE(12, "Shaping the terrain"),
        ELEVATION(13, "Shaping the terrain"),
        BIOMES(18, "Shaping the terrain"),
        OCEANS(3, "Placing the seas"),
        VOLCANOES(3, "Placing the seas"),
        LAKES(1, "Cutting rivers and lakes"),
        RIVERS(3, "Cutting rivers and lakes"),
        RESOURCES(5, "Placing resources"),
        COARSE(11, "Saving the world"),
        SAVE(31, "Saving the world"),
    }

    private val started = clock()

    @Volatile
    private var cancelled = false

    @Volatile
    var fraction = 0f
        private set

    fun cancel() {
        cancelled = true
    }

    /** Throws [GenerationCancelled] if asked to stop; call between steps and inside long loops. */
    fun checkpoint() {
        if (cancelled) throw GenerationCancelled()
    }

    /** [step] is finished: stop here if cancelled, otherwise report the new total. */
    fun done(step: Step) {
        checkpoint()
        val finished = Step.entries.takeWhile { it != step }.sumOf { it.weight } + step.weight
        fraction = maxOf(fraction, finished / TOTAL.toFloat())
        onChange(fraction, etaMillis(), Step.entries.getOrNull(step.ordinal + 1)?.label)
    }

    /** Milliseconds left at the pace so far, or null while too little is done for a steady guess. */
    fun etaMillis(): Long? {
        if (fraction < MIN_FRACTION_FOR_ETA) return null
        val elapsed = (clock() - started) / 1_000_000
        return (elapsed * (1f - fraction) / fraction).toLong()
    }

    companion object {
        private val TOTAL = Step.entries.sumOf { it.weight }
        private const val MIN_FRACTION_FOR_ETA = 0.15f

        /** For callers that neither report nor cancel. */
        val NONE = GenerationProgress()
    }
}
