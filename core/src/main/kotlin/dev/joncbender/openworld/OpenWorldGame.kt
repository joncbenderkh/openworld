package dev.joncbender.openworld

import com.badlogic.gdx.Game

class OpenWorldGame : Game() {
    lateinit var globeScreen: GlobeScreen
        private set

    override fun create() {
        globeScreen = GlobeScreen()
        setScreen(globeScreen)
    }
}
