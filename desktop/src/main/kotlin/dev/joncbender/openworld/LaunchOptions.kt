package dev.joncbender.openworld

/**
 * What the desktop launcher reads at startup. Each setting comes from a command-line flag
 * (`--max-near-patches=N`) or, failing that, a system property (`-Dopenworld.maxNearPatches=N`),
 * and otherwise keeps the phone's value, so launching with nothing set changes nothing.
 */
data class LaunchOptions(val maxNearPatches: Int = GameOptions.DEFAULT_MAX_NEAR_PATCHES) {

    companion object {
        private const val MAX_NEAR_PATCHES_FLAG = "--max-near-patches="
        const val MAX_NEAR_PATCHES_PROPERTY = "openworld.maxNearPatches"

        fun parse(args: Array<String>, properties: (String) -> String? = System::getProperty): LaunchOptions {
            val fromFlag = args.lastOrNull { it.startsWith(MAX_NEAR_PATCHES_FLAG) }?.removePrefix(MAX_NEAR_PATCHES_FLAG)
            val unknown = args.firstOrNull { !it.startsWith(MAX_NEAR_PATCHES_FLAG) }
            require(unknown == null) { "unknown argument '$unknown' (expected ${MAX_NEAR_PATCHES_FLAG}N)" }
            val raw = fromFlag ?: properties(MAX_NEAR_PATCHES_PROPERTY)
            val patches = if (raw == null) {
                GameOptions.DEFAULT_MAX_NEAR_PATCHES
            } else {
                raw.trim().toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("max near patches must be a positive integer, got '$raw'")
            }
            return LaunchOptions(patches)
        }
    }
}
