package dev.joncbender.openworld

/**
 * Keeps the near (full-resolution) level of detail's patches in memory only while they're
 * wanted. Each frame the caller says which patches are in view; this builds the missing
 * ones, closest to the middle of the view first, and evicts the ones that have gone
 * unseen longest once too many are resident.
 *
 * Building a patch costs a few milliseconds of GL-thread time, so a frame builds at most
 * as many as fit in its time budget - but always at least one, so progress is guaranteed
 * even when the budget is tiny. Patches still waiting simply show the far level beneath.
 *
 * Knows nothing about GL or meshes: it drives [buildPatch] and [releasePatch], which keeps
 * the policy unit-testable.
 */
class NearPatchStreamer(
    patchCount: Int,
    private val maxResident: Int,
    private val buildPatch: (Int) -> Unit,
    private val releasePatch: (Int) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val resident = BooleanArray(patchCount)
    private val lastUsed = IntArray(patchCount)

    var residentCount = 0
        private set

    fun isResident(patch: Int): Boolean = resident[patch]

    /**
     * One frame's update. [visible] holds the patches in view in `[0, visibleCount)`;
     * [priority] is indexed by patch and larger means build sooner. Returns how many
     * patches were built this frame.
     */
    fun update(frame: Int, visible: IntArray, visibleCount: Int, priority: FloatArray, budgetNanos: Long): Int {
        for (k in 0 until visibleCount) {
            val patch = visible[k]
            if (resident[patch]) lastUsed[patch] = frame
        }

        var built = 0
        val start = nanoTime()
        while (true) {
            var best = -1
            var bestPriority = Float.NEGATIVE_INFINITY
            for (k in 0 until visibleCount) {
                val patch = visible[k]
                if (!resident[patch] && priority[patch] > bestPriority) {
                    best = patch
                    bestPriority = priority[patch]
                }
            }
            if (best == -1) break
            if (built > 0 && nanoTime() - start >= budgetNanos) break

            buildPatch(best)
            resident[best] = true
            residentCount++
            lastUsed[best] = frame
            built++
        }

        evict(frame)
        return built
    }

    /** Releases every resident patch, e.g. once the near level has been out of use for a while. */
    fun releaseAll() {
        for (patch in resident.indices) {
            if (resident[patch]) {
                releasePatch(patch)
                resident[patch] = false
            }
        }
        residentCount = 0
    }

    /**
     * While over the cap, releases the resident patch unused longest. A patch used this very
     * frame is never released - they're being drawn - so the count may stay over the cap when
     * more than [maxResident] patches are in view at once.
     */
    private fun evict(frame: Int) {
        while (residentCount > maxResident) {
            var victim = -1
            var oldest = frame
            for (patch in resident.indices) {
                if (resident[patch] && lastUsed[patch] < oldest) {
                    victim = patch
                    oldest = lastUsed[patch]
                }
            }
            if (victim == -1) return
            releasePatch(victim)
            resident[victim] = false
            residentCount--
        }
    }
}
