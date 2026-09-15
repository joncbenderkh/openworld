package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3

class GlobeScreen : Screen, GestureDetector.GestureAdapter() {

    private val frequency = 12 // total tiles = 10*frequency^2 + 2
    private val world = WorldGenerator(seed = System.currentTimeMillis()).generate(frequency)

    private val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        near = 0.1f
        far = 20f
    }
    private var distance = 3f
    private val minDistance = 1.6f
    private val maxDistance = 6f
    private var theta = 0f
    private var phi = MathUtils.PI / 2f // colatitude from +Y: 0 = north pole, PI = south pole

    private lateinit var mesh: Mesh
    private lateinit var shader: ShaderProgram

    override fun show() {
        mesh = buildMesh()
        shader = ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(shader.isCompiled) { shader.log }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.input.inputProcessor = InputMultiplexer(GestureDetector(this))
    }

    private fun buildMesh(): Mesh {
        var floatCount = 0
        for (face in world.faces) floatCount += face.corners.size * 3 * VERTEX_SIZE
        val data = FloatArray(floatCount)
        var p = 0

        for ((i, face) in world.faces.withIndex()) {
            val color = world[i].color
            val apex = face.center
            val corners = face.corners
            for (c in corners.indices) {
                p = appendVertex(data, p, apex, color)
                p = appendVertex(data, p, corners[c], color)
                p = appendVertex(data, p, corners[(c + 1) % corners.size], color)
            }
        }

        val mesh = Mesh(
            true,
            floatCount / VERTEX_SIZE,
            0,
            VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
        )
        mesh.setVertices(data)
        return mesh
    }

    private fun appendVertex(data: FloatArray, offset: Int, pos: Vector3, color: Color): Int {
        var o = offset
        data[o++] = pos.x; data[o++] = pos.y; data[o++] = pos.z
        data[o++] = color.r; data[o++] = color.g; data[o++] = color.b; data[o++] = color.a
        return o
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        updateCameraPosition()
        camera.update()

        shader.bind()
        shader.setUniformMatrix("u_projViewTrans", camera.combined)
        mesh.render(shader, GL20.GL_TRIANGLES)
    }

    private fun updateCameraPosition() {
        val x = distance * MathUtils.sin(phi) * MathUtils.cos(theta)
        val y = distance * MathUtils.cos(phi)
        val z = distance * MathUtils.sin(phi) * MathUtils.sin(theta)
        camera.position.set(x, y, z)
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(0f, 0f, 0f)
        camera.normalizeUp()
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        theta -= deltaX * ROTATE_SPEED
        phi = (phi - deltaY * ROTATE_SPEED).coerceIn(POLE_MARGIN, MathUtils.PI - POLE_MARGIN)
        return true
    }

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        val ratio = initialDistance / distance
        this.distance = (this.distance * ratio).coerceIn(minDistance, maxDistance)
        return true
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        mesh.dispose()
        shader.dispose()
    }

    companion object {
        private const val VERTEX_SIZE = 7 // position(3) + color(4)
        private const val ROTATE_SPEED = 0.005f
        private const val POLE_MARGIN = 0.05f

        private const val VERTEX_SHADER = """
            attribute vec4 a_position;
            attribute vec4 a_color;
            uniform mat4 u_projViewTrans;
            varying vec4 v_color;
            void main() {
                v_color = a_color;
                gl_Position = u_projViewTrans * a_position;
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            void main() {
                gl_FragColor = v_color;
            }
        """
    }
}
