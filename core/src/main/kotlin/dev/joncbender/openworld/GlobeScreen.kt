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
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3

class GlobeScreen : Screen, GestureDetector.GestureAdapter() {

    private val frequency = 40 // total tiles = 10*frequency^2 + 2 (~10x the original 1442)
    private var world = WorldGenerator(seed = System.nanoTime()).generate(frequency)

    /** Reverses the sense of one- and two-finger drag gestures. Settable from outside (the Android settings menu). */
    var navigationFlipped = false

    private val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        near = 0.1f
        far = 20f
        direction.set(0f, 0f, -1f)
        up.set(0f, 1f, 0f)
    }
    private var distance = 3f
    private val minDistance = 1.6f
    private val maxDistance = 6f
    private var zoomStartDistance: Float? = null
    private var twistStartRotation: Quaternion? = null
    private var twistStartAngleDeg: Float? = null

    // The globe's orientation, spun by drag gestures. The camera itself never
    // moves except straight along its own view axis for zoom - there is no
    // orbit-around-a-fixed-axis parameterization here, so there's no pole for
    // rotation to behave specially near (that was the root cause behind three
    // separate pole bugs: the dead-stop clamp, the up-vector flip, and rotation
    // degenerating into an in-place spin near the axis).
    private val rotation = Quaternion()
    private val modelMatrix = Matrix4()
    private val mvpMatrix = Matrix4()

    private lateinit var mesh: Mesh
    private lateinit var graticule: Mesh
    private lateinit var shader: ShaderProgram

    override fun show() {
        mesh = buildMesh()
        graticule = Graticule.build()
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

    /** Regenerates the world with a fresh random seed and rebuilds the terrain mesh. Called from the settings menu. */
    fun regenerateWorld() {
        world = WorldGenerator(seed = System.nanoTime()).generate(frequency)
        mesh.dispose()
        mesh = buildMesh()
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

        camera.position.set(0f, 0f, distance)
        camera.update()

        modelMatrix.idt().rotate(rotation)
        mvpMatrix.set(camera.combined).mul(modelMatrix)

        shader.bind()
        shader.setUniformMatrix("u_projViewTrans", mvpMatrix)
        mesh.render(shader, GL20.GL_TRIANGLES)
        graticule.render(shader, GL20.GL_TRIANGLES)
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        // Rotate the globe, not the camera: each drag applies a small rotation
        // about the camera's own (fixed) up/right axes, composed on the
        // *outside* of the accumulated orientation (mulLeft) so the rotation
        // axis is always the one the viewer is currently looking along,
        // regardless of how the globe has already been spun - exactly how
        // spinning a ball with a finger works, with no special axis anywhere.
        val sign = if (navigationFlipped) -1f else 1f
        rotation.mulLeft(Quaternion(Vector3.Y, -deltaX * sign * ROTATE_SPEED_DEG))
        rotation.mulLeft(Quaternion(Vector3.X, -deltaY * sign * ROTATE_SPEED_DEG))
        return true
    }

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        // initialDistance is fixed for the whole pinch gesture (finger separation
        // when it started) while distance keeps updating, so initialDistance/distance
        // is the *cumulative* zoom ratio since the gesture began - applying it against
        // a start-of-gesture camera distance, not against the live one, avoids
        // compounding the same ratio again on every callback.
        val start = zoomStartDistance ?: this.distance.also { zoomStartDistance = it }
        val ratio = initialDistance / distance
        this.distance = (start * ratio).coerceIn(minDistance, maxDistance)
        return true
    }

    override fun pinch(
        initialPointer1: Vector2,
        initialPointer2: Vector2,
        pointer1: Vector2,
        pointer2: Vector2,
    ): Boolean {
        // Two-finger twist: rotate around the camera's own view axis (Z) by
        // however much the angle between the two fingers has changed. Same
        // cumulative-since-gesture-start shape as zoom() above, and for the
        // same reason - the "initial" pointers stay fixed at gesture start
        // while the live ones keep updating, so re-deriving from a snapshot
        // of `rotation` each callback avoids compounding the same twist
        // repeatedly instead of applying it once.
        val start = twistStartRotation ?: Quaternion(rotation).also { twistStartRotation = it }
        val startAngle = twistStartAngleDeg ?: angleDeg(initialPointer1, initialPointer2).also { twistStartAngleDeg = it }
        val currentAngle = angleDeg(pointer1, pointer2)
        rotation.set(start).mulLeft(Quaternion(Vector3.Z, currentAngle - startAngle))
        return true
    }

    private fun angleDeg(a: Vector2, b: Vector2): Float =
        MathUtils.atan2(b.y - a.y, b.x - a.x) * MathUtils.radiansToDegrees

    override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        zoomStartDistance = null
        twistStartRotation = null
        twistStartAngleDeg = null
        return false
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        mesh.dispose()
        graticule.dispose()
        shader.dispose()
    }

    companion object {
        private const val VERTEX_SIZE = 7 // position(3) + color(4)
        private const val ROTATE_SPEED_DEG = 0.3f

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
