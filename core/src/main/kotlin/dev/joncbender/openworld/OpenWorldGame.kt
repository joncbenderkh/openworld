package dev.joncbender.openworld

import com.badlogic.gdx.Game

class OpenWorldGame : Game() {
    override fun create() {
        setScreen(WorldScreen())
    }
}
