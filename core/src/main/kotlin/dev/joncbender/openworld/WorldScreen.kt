package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector

class WorldScreen : Screen, GestureDetector.GestureAdapter() {

    private val hexSize = 24f
    private val geometry = HexGeometry(hexSize)
    private val worldWidth = 48 // even, so the wrap seam lines up with the odd-q row offset
    private val worldHeight = 32

    private val world = WorldGenerator(seed = System.currentTimeMillis()).generate(worldWidth, worldHeight)
    private val worldPixelWidth = geometry.horizontalSpacing * worldWidth

    private val camera = OrthographicCamera()
    private val shapes = ShapeRenderer()

    init {
        camera.position.set(worldPixelWidth / 2f, geometry.verticalSpacing * worldHeight / 2f, 0f)
    }

    override fun show() {
        Gdx.input.inputProcessor = InputMultiplexer(GestureDetector(this))
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.05f, 0.05f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)

        val halfViewCols = (camera.viewportWidth * camera.zoom / geometry.horizontalSpacing / 2f).toInt() + 2
        val centerCol = (camera.position.x / geometry.horizontalSpacing).toInt()

        for (row in 0 until worldHeight) {
            for (colOffset in -halfViewCols..halfViewCols) {
                val col = centerCol + colOffset
                val biome = world[HexCoord(col, row).wrapped(worldWidth)]
                val center = geometry.center(col, row)
                shapes.color = biome.color
                drawHex(center.x, center.y)
            }
        }

        shapes.end()
    }

    private fun drawHex(cx: Float, cy: Float) {
        val corners = geometry.corners(cx, cy)
        for (i in 1 until 5) {
            shapes.triangle(
                corners[0], corners[1],
                corners[i * 2], corners[i * 2 + 1],
                corners[i * 2 + 2], corners[i * 2 + 3],
            )
        }
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        camera.position.x -= deltaX * camera.zoom
        camera.position.y += deltaY * camera.zoom
        wrapCameraX()
        return true
    }

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        val ratio = initialDistance / distance
        camera.zoom = (camera.zoom * ratio).coerceIn(0.25f, 4f)
        return true
    }

    private fun wrapCameraX() {
        camera.position.x = ((camera.position.x % worldPixelWidth) + worldPixelWidth) % worldPixelWidth
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        shapes.dispose()
    }
}
