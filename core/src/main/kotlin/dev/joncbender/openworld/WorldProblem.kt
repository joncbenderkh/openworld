package dev.joncbender.openworld

/**
 * Why a world is not there: what the player is told, whether the world they had is still playable
 * ([worldKept]) and whether freeing storage could fix it ([needsStorage]).
 */
enum class WorldProblem(val message: String, val worldKept: Boolean, val needsStorage: Boolean) {
    /** A new world was refused before anything was touched. */
    NO_ROOM_FOR_NEW("Not enough free storage to create the world (about 160 MB is needed).", worldKept = true, needsStorage = true),

    /** Generation found no room once the old caches were gone, so the globe is bare. */
    NO_ROOM("Not enough free storage to create the world (about 160 MB is needed).", worldKept = false, needsStorage = true),

    NOT_SAVED("The world could not be saved, so it cannot be shown. Free some storage and start a new world.", worldKept = false, needsStorage = true),

    UNREADABLE("The saved world could not be read. Start a new world.", worldKept = false, needsStorage = false),
}
