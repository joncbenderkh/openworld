package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle

/** What a platform launcher can tune; the defaults are the Android phone's. */
class GameOptions(
    /**
     * A cap on resident near-layer patches: ~2.5k tiles each, so ~600k tiles in all at the default.
     * Patches in view are never evicted, so more than this can be resident while they are all visible.
     * It is what bounds the near layer's memory, so raise it only where the heap allows.
     */
    val maxNearPatches: Int = DEFAULT_MAX_NEAR_PATCHES,
    /** Where a named file (the world caches) lives. */
    val storage: (String) -> FileHandle = { Gdx.files.local(it) },
) {
    init {
        require(maxNearPatches > 0) { "maxNearPatches must be positive, got $maxNearPatches" }
    }

    companion object {
        const val DEFAULT_MAX_NEAR_PATCHES = 256
    }
}
