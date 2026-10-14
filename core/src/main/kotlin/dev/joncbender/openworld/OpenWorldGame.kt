package dev.joncbender.openworld

import com.badlogic.gdx.Game

class OpenWorldGame(private val options: GameOptions = GameOptions()) : Game() {
    lateinit var globeScreen: GlobeScreen
        private set

    /** Set before initialize()/initializeForView() runs create() on the GL thread - see GlobeScreen.onTileSelected. */
    var onTileSelected: ((TileInfo) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onMessage]. */
    var onMessage: ((String) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onGenerationStage]. */
    var onGenerationStage: ((String?) -> Unit)? = null

    override fun create() {
        globeScreen = GlobeScreen(options)
        globeScreen.onTileSelected = onTileSelected
        globeScreen.onMessage = onMessage
        globeScreen.onGenerationStage = onGenerationStage
        setScreen(globeScreen)
    }
}
