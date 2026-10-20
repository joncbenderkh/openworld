package dev.joncbender.openworld

import com.badlogic.gdx.Game

class OpenWorldGame(private val options: GameOptions = GameOptions()) : Game() {
    lateinit var globeScreen: GlobeScreen
        private set

    /** Set before initialize()/initializeForView() runs create() on the GL thread - see GlobeScreen.onTileSelected. */
    var onTileSelected: ((TileInfo) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onAlerts]. */
    var onAlerts: ((List<Alert>) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onStockpile]. */
    var onStockpile: ((List<StockLine>) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onWorldProblem]. */
    var onWorldProblem: ((WorldProblem, Long) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onGenerationStage]. */
    var onGenerationStage: ((String?) -> Unit)? = null

    /** Same timing as [onTileSelected]; see [GlobeScreen.onGenerationProgress]. */
    var onGenerationProgress: ((fraction: Float, etaMillis: Long?, cancellable: Boolean) -> Unit)? = null

    override fun create() {
        globeScreen = GlobeScreen(options)
        globeScreen.onTileSelected = onTileSelected
        globeScreen.onAlerts = onAlerts
        globeScreen.onStockpile = onStockpile
        globeScreen.onWorldProblem = onWorldProblem
        globeScreen.onGenerationStage = onGenerationStage
        globeScreen.onGenerationProgress = onGenerationProgress
        setScreen(globeScreen)
    }
}
