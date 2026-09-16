package dev.joncbender.openworld

import com.badlogic.gdx.Gdx

/**
 * Phase timer for world generation and render startup, logged under the
 * "perf" tag (`adb logcat -s perf`). Kept permanently rather than stripped
 * after a one-off profiling pass, so a future regression in generation or
 * mesh-build time (both scale with tile count, currently ~79k tiles) shows
 * up immediately instead of needing to be re-instrumented from scratch.
 */
class PerfTimer(private val tag: String = "perf") {
    private var last = System.nanoTime()

    fun lap(label: String) {
        val now = System.nanoTime()
        Gdx.app?.log(tag, "$label: ${(now - last) / 1_000_000}ms")
        last = now
    }
}
