package dev.joncbender.openworld

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
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
    private var alerts: List<Alert> = emptyList()
    private var stock: List<StockLine> = emptyList()
    private var showStock = false

    override fun create() {
        game.onTileSelected = { selected = it }
        game.onAlerts = { alerts = it }
        game.onStockpile = { stock = it }
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
                Input.Keys.B -> screen.foundSettlementOnSelection()
                Input.Keys.U -> screen.upgradeSettlementOnSelection()
                // No list here: V builds the first building the settlement can now afford, in the order of the panel.
                Input.Keys.V -> selected?.let { info -> info.buildings.firstOrNull { it.now == BuildResult.OK }?.let { screen.build(info.faceIndex, it.building) } }
                Input.Keys.S -> {
                    showStock = !showStock
                    screen.watchStockpile(showStock)
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
        val lines = ArrayList<String>()
        fun text(s: String) {
            lines += s
        }
        if (showHelp) {
            text("drag: spin   wheel / + -: zoom   Q E: twist   click: inspect tile   X: clear   B: found a homestead   U: grow it   V: build")
            text("R: reset view   F: flip drag   1 2 3: resource density   N: new world   S: stockpile   C: copy seed   H: hide help   Esc: quit")
        }
        text("seed ${game.globeScreen.seed}")
        if (notice.isNotEmpty()) text(notice)
        Alerts.line(alerts)?.let(::text)
        if (showStock) {
            text(if (stock.isEmpty()) "Stockpile: empty" else "Stockpile:")
            // At most as many rows as leave room for the lines already listed and the tile's details below.
            val room = ((Gdx.graphics.height - 2 * MARGIN) / LINE_HEIGHT).toInt() - lines.size - TILE_LINES
            val shown = if (stock.size <= room) stock.size else (room - 1).coerceAtLeast(0)
            Stockpile.text(stock).take(shown).forEach { text("  $it") }
            if (shown < stock.size) text("  and ${stock.size - shown} more")
        }
        selected?.let {
            text("Biome: ${it.biome.name.toDisplayName()}${if (it.isOpenOcean) " (open ocean)" else ""}")
            text(if (it.resources.isEmpty()) "Resources: none" else "Resources: ${it.resources.joinToString(", ") { r -> r.name.toDisplayName() }}")
            val settlement = it.settlement
            when {
                settlement != null -> {
                    text("Settlement: ${settlement.tier.name.toDisplayName()}, ${settlement.population.toInt()} of ${settlement.tier.populationCap} people")
                    it.status?.let { status ->
                        text(status.happiness.line())
                        text(status.needs.joinToString("   ") { n -> "${n.need.name.toDisplayName()}: ${if (n.met) "ok" else "short"} (${if (n.byGoods) "clothing" else n.supported.toString()})" })
                        it.economy?.let { economy ->
                            text(economy.incomeLine())
                            text(economy.stockLine())
                            economy.lockedLine()?.let(::text)
                            Crafting.line(it.crafting)?.let(::text)
                            if (it.buildings.any { b -> b.built }) text("Buildings: " + it.buildings.filter { b -> b.built }.joinToString(", ") { b -> b.building.name.toDisplayName() + if (b.idle) " (idle)" else "" })
                        }
                        if (status.upgrade == UpgradeResult.OK) text("Press U to grow into a ${settlement.tier.next!!.name.lowercase()}")
                    }
                }
                it.canFound == FoundResult.OK -> text("Press B to found a homestead")
                it.canFound == FoundResult.CLAIMED -> text("Inside a settlement's land")
            }
        }
        // The text goes on a dark panel: bare on the map's busy tiles it is hard to read.
        val top = Gdx.graphics.height - MARGIN
        val width = lines.maxOfOrNull { layout.setText(font, it); layout.width } ?: 0f
        batch.begin()
        if (lines.isNotEmpty()) {
            batch.setColor(0f, 0f, 0f, PANEL_ALPHA)
            batch.draw(panel, MARGIN - PADDING, top - lines.size * LINE_HEIGHT - PADDING + LINE_HEIGHT / 2, width + 2 * PADDING, lines.size * LINE_HEIGHT + 2 * PADDING)
            batch.setColor(Color.WHITE)
        }
        lines.forEachIndexed { i, line -> font.draw(batch, line, MARGIN, top - i * LINE_HEIGHT) }
        batch.end()
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
    }

    private val layout = GlyphLayout()
    private var panelTexture: Texture? = null

    // One white texel, tinted by the batch; made on first use, as it needs GL.
    private val panel: Texture
        get() = panelTexture ?: run {
            val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply { setColor(Color.WHITE); fill() }
            Texture(pixmap).also { pixmap.dispose(); panelTexture = it }
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
        panelTexture?.dispose()
        font.dispose()
    }

    private companion object {
        const val MARGIN = 12f
        const val LINE_HEIGHT = 18f
        const val PADDING = 8f
        const val PANEL_ALPHA = 0.7f
        // Lines the tapped tile's details can take below the stockpile.
        const val TILE_LINES = 12
        const val TWIST_DEGREES = 15f
        const val KEY_ZOOM_FACTOR = 1.15f
    }
}
