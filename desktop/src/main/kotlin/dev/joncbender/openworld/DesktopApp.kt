package dev.joncbender.openworld

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import java.io.File

/**
 * The game plus what the phone gets from Android views: a text overlay for the tapped tile and the
 * seed, and keys for the settings menu and the gestures a mouse cannot make.
 */
class DesktopApp(private val options: LaunchOptions, private val dataDir: File) : ApplicationListener {

    private val game = OpenWorldGame(
        GameOptions(
            maxNearPatches = options.maxNearPatches,
            storage = { name -> Gdx.files.absolute(File(dataDir, name).path) },
        ),
    )
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private var selected: TileInfo? = null
    private var showHelp = true
    private var notice = ""

    override fun create() {
        game.onTileSelected = { selected = it }
        game.onWorldProblem = { problem, _ -> notice = problem.message }
        game.create()
        batch = SpriteBatch()
        font = BitmapFont()
        (Gdx.input.inputProcessor as InputMultiplexer).addProcessor(keys)
    }

    private val keys = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            val screen = game.globeScreen
            when (keycode) {
                Input.Keys.ESCAPE -> Gdx.app.exit()
                Input.Keys.H -> showHelp = !showHelp
                Input.Keys.R -> screen.resetOrientation()
                Input.Keys.F -> {
                    screen.navigationFlipped = !screen.navigationFlipped
                    notice = "Navigation ${if (screen.navigationFlipped) "flipped" else "normal"}"
                }
                Input.Keys.Q -> screen.twist(TWIST_DEGREES)
                Input.Keys.E -> screen.twist(-TWIST_DEGREES)
                Input.Keys.EQUALS, Input.Keys.PLUS, Input.Keys.NUMPAD_ADD -> screen.zoomBy(1f / KEY_ZOOM_FACTOR)
                Input.Keys.MINUS, Input.Keys.NUMPAD_SUBTRACT -> screen.zoomBy(KEY_ZOOM_FACTOR)
                Input.Keys.NUM_1 -> setDensity(0.12f, "Sparse")
                Input.Keys.NUM_2 -> setDensity(0.18f, "Normal")
                Input.Keys.NUM_3 -> setDensity(0.28f, "Abundant")
                Input.Keys.N -> {
                    screen.regenerateWorld(System.nanoTime())
                    selected = null
                }
                Input.Keys.X -> {
                    screen.clearSelection()
                    selected = null
                }
                Input.Keys.C -> {
                    Gdx.app.clipboard.contents = screen.seed.toString()
                    notice = "Seed ${screen.seed} copied"
                }
                else -> return false
            }
            return true
        }
    }

    private fun setDensity(value: Float, label: String) {
        val screen = game.globeScreen
        if (screen.isGenerating || screen.resourceDensity == value) return
        screen.resourceDensity = value
        screen.regenerateWorld()
        selected = null
        notice = "$label resources"
    }

    override fun render() {
        game.render()
        drawOverlay()
    }

    private fun drawOverlay() {
        // The globe leaves depth testing, culling and polygon offset on; the batch needs them off.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
        batch.begin()
        val top = Gdx.graphics.height - MARGIN
        var line = 0
        fun text(s: String) {
            font.draw(batch, s, MARGIN, top - line++ * LINE_HEIGHT)
        }
        if (showHelp) {
            text("drag: spin   wheel / + -: zoom   Q E: twist   click: inspect tile   X: clear")
            text("R: reset view   F: flip drag   1 2 3: resource density   N: new world   C: copy seed   H: hide help   Esc: quit")
        }
        text("seed ${game.globeScreen.seed}")
        if (notice.isNotEmpty()) text(notice)
        selected?.let {
            text("Biome: ${it.biome.name.toDisplayName()}${if (it.isOpenOcean) " (open ocean)" else ""}")
            text(if (it.resources.isEmpty()) "Resources: none" else "Resources: ${it.resources.joinToString(", ") { r -> r.name.toDisplayName() }}")
        }
        batch.end()
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
    }

    private fun String.toDisplayName(): String =
        lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    override fun resize(width: Int, height: Int) {
        game.resize(width, height)
        batch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
    }

    override fun pause() = game.pause()
    override fun resume() = game.resume()
    override fun dispose() {
        game.dispose()
        batch.dispose()
        font.dispose()
    }

    private companion object {
        const val MARGIN = 12f
        const val LINE_HEIGHT = 18f
        const val TWIST_DEGREES = 15f
        const val KEY_ZOOM_FACTOR = 1.15f
    }
}
