package dev.joncbender.openworld

/**
 * The per-frame decisions about resource icons, apart from the GL work (see [GlobeScreen.drawIcons]): when
 * icons are on, which patches get built this frame and which are let go. Plain Kotlin, so it is unit tested.
 */
internal class IconScheduler(patchCount: Int) {
    var active = false
        private set
    // Internal, not private, so the inline functions below can read it.
    internal val lastSeen = IntArray(patchCount)

    /** Applies the on/off band for [distance] (on below [ENTER_DISTANCE], off above [EXIT_DISTANCE]); returns whether icons are on. */
    fun update(distance: Float): Boolean {
        if (!active && distance < ENTER_DISTANCE) active = true
        else if (active && distance > EXIT_DISTANCE) active = false
        return active
    }

    /** Switches the icons off at once, for when the near layer is dropped. */
    fun deactivate() {
        active = false
    }

    /** Marks the first [count] of [visible] as in view at [frame]. */
    fun see(visible: IntArray, count: Int, frame: Int) {
        for (k in 0 until count) lastSeen[visible[k]] = frame
    }

    /**
     * Builds up to [BUILDS_PER_FRAME] patches, the one with the highest [priority] first. A patch is a
     * candidate when it is in view, [ready] (its terrain is resident) and not [built]; a patch with no
     * resources counts as built once tried, so it never takes a slot from a later one.
     */
    inline fun build(visible: IntArray, count: Int, priority: FloatArray, ready: (Int) -> Boolean, built: (Int) -> Boolean, build: (Int) -> Unit) {
        repeat(BUILDS_PER_FRAME) {
            var best = -1
            for (k in 0 until count) {
                val patch = visible[k]
                if (!ready(patch) || built(patch)) continue
                if (best == -1 || priority[patch] > priority[best]) best = patch
            }
            if (best != -1) build(best)
        }
    }

    /** Releases every patch of [patchCount] that [resident] says holds icons and has been out of view for more than [KEEP_FRAMES]. */
    inline fun releaseStale(frame: Int, patchCount: Int, resident: (Int) -> Boolean, release: (Int) -> Unit) {
        for (patch in 0 until patchCount) {
            if (resident(patch) && frame - lastSeen[patch] > KEEP_FRAMES) release(patch)
        }
    }

    companion object {
        // Icons show below ENTER and hide above EXIT (a gap, so hovering at the threshold doesn't flicker); a tile is then
        // at least ~40 px wide. A few icon meshes are built per frame, each a quick pass over one patch's tiles.
        const val ENTER_DISTANCE = 1.25f
        const val EXIT_DISTANCE = 1.3f
        const val BUILDS_PER_FRAME = 2
        // Frames a patch keeps its icons after leaving the view (~1.5 s at 60 fps).
        const val KEEP_FRAMES = 90
    }
}
