package dev.joncbender.openworld

import com.badlogic.gdx.Game

class OpenWorldGame : Game() {
    lateinit var globeScreen: GlobeScreen
        private set

    /** Set before initialize()/initializeForView() runs create() on the GL thread - see GlobeScreen.onTileSelected. */
    var onTileSelected: ((TileInfo) -> Unit)? = null

    override fun create() {
        globeScreen = GlobeScreen()
        globeScreen.onTileSelected = onTileSelected
        setScreen(globeScreen)
    }
}
