package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.Intersector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.Ray

class GlobeScreen : Screen, GestureDetector.GestureAdapter() {

    private val frequency = 89 // total tiles = 10*frequency^2 + 2 (~5x the previous 16002)

    // Gdx.app.getPreferences is libGDX's own cross-platform settings store
    // (backed by SharedPreferences on Android) - using it here instead of an
    // Android-specific API keeps this platform-independent core module free
    // of Android imports.
    private val preferences = Gdx.app.getPreferences("dev.joncbender.openworld.settings")

    /** What the player has collected - persists across restarts, but is reset whenever a new world is generated. */
    val inventory = Inventory(preferences)

    /** Fraction of tiles that get a resource on (re)generation. Settable from outside (the Android settings menu). */
    var resourceDensity = preferences.getFloat(PREF_RESOURCE_DENSITY, WorldGenerator.DEFAULT_RESOURCE_DENSITY)
        set(value) {
            field = value
            preferences.putFloat(PREF_RESOURCE_DENSITY, value)
            preferences.flush()
        }

    /** The seed behind the current world - readable so the settings menu can show/copy it. */
    var seed: Long = preferences.getLong(PREF_SEED, System.nanoTime())
        private set

    init {
        // getLong's fallback (a fresh System.nanoTime()) only exists in memory
        // until something writes it back - without this, a first-ever launch
        // (or any launch that happens to fall back) would pick a new random
        // seed every time instead of settling on one to persist.
        preferences.putLong(PREF_SEED, seed)
        preferences.flush()
    }

    private var world = WorldGenerator(seed).generate(frequency, resourceDensity)

    /** Reverses the sense of one- and two-finger drag gestures. Settable from outside (the Android settings menu). */
    var navigationFlipped = preferences.getBoolean(PREF_NAVIGATION_FLIPPED, false)
        set(value) {
            field = value
            preferences.putBoolean(PREF_NAVIGATION_FLIPPED, value)
            preferences.flush()
        }

    /** Fired from tap() with whatever tile was hit - set by the Android layer to pop up a detail dialog. */
    var onTileSelected: ((TileInfo) -> Unit)? = null

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
    private lateinit var biomeTexture: Texture
    private lateinit var compass: Compass

    override fun show() {
        val perf = PerfTimer()
        biomeTexture = BiomeTextures.build()
        perf.lap("BiomeTextures.build")
        mesh = buildMesh()
        perf.lap("buildMesh")
        graticule = Graticule.build()
        perf.lap("Graticule.build")
        shader = ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(shader.isCompiled) { shader.log }
        compass = Compass()
        compass.resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.input.inputProcessor = InputMultiplexer(GestureDetector(this))
    }

    private fun buildMesh(): Mesh {
        var floatCount = 0
        for (face in world.faces) floatCount += face.corners.size * 3 * VERTEX_SIZE
        val data = FloatArray(floatCount)
        var p = 0
        val uvBuffer = FloatArray(6 * 2) // every face has 5 or 6 corners, so 6 is enough for either

        for ((i, face) in world.faces.withIndex()) {
            val biome = world[i]
            val (centerU, centerV) = BiomeTextures.centerUV(biome)
            val corners = face.corners
            val n = corners.size
            BiomeTextures.cornerUVsInto(biome, n, uvBuffer)
            for (c in corners.indices) {
                val next = (c + 1) % n
                p = appendVertex(data, p, face.center, Color.WHITE, centerU, centerV)
                p = appendVertex(data, p, corners[c], Color.WHITE, uvBuffer[c * 2], uvBuffer[c * 2 + 1])
                p = appendVertex(data, p, corners[next], Color.WHITE, uvBuffer[next * 2], uvBuffer[next * 2 + 1])
            }
        }

        val mesh = Mesh(
            true,
            floatCount / VERTEX_SIZE,
            0,
            VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
            VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
        )
        mesh.setVertices(data)
        return mesh
    }

    /**
     * Regenerates the world and rebuilds the terrain mesh. Called from the
     * settings menu, either with a fresh random seed ("New world seed") or a
     * specific one the player typed in. A new world means whatever the player
     * collected no longer corresponds to anything on the map, so the
     * inventory resets along with it.
     */
    fun regenerateWorld(newSeed: Long = System.nanoTime()) {
        seed = newSeed
        preferences.putLong(PREF_SEED, seed)
        preferences.flush()
        inventory.clear()
        world = WorldGenerator(seed).generate(frequency, resourceDensity)
        mesh.dispose()
        mesh = buildMesh()
    }

    private fun appendVertex(data: FloatArray, offset: Int, pos: Vector3, color: Color, u: Float, v: Float): Int {
        var o = offset
        data[o++] = pos.x; data[o++] = pos.y; data[o++] = pos.z
        data[o++] = color.r; data[o++] = color.g; data[o++] = color.b; data[o++] = color.a
        data[o++] = u; data[o++] = v
        return o
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        camera.position.set(0f, 0f, distance)
        camera.update()

        modelMatrix.idt().rotate(rotation)
        mvpMatrix.set(camera.combined).mul(modelMatrix)

        biomeTexture.bind(0)
        shader.bind()
        shader.setUniformMatrix("u_projViewTrans", mvpMatrix)
        shader.setUniformi("u_texture", 0)
        mesh.render(shader, GL20.GL_TRIANGLES)
        graticule.render(shader, GL20.GL_TRIANGLES)

        compass.render(rotation)
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        if (::compass.isInitialized) compass.resize(width, height)
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

    override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
        // GestureDetector only calls tap() for a genuine tap (touch down+up
        // with little movement) - anything that moves far enough is already
        // routed to pan()/zoom()/pinch() instead, so no extra drag-vs-tap
        // threshold is needed here.
        val worldRay = camera.getPickRay(x, y)
        // The camera never moves (only the globe's model matrix rotates), so a
        // pick ray from the camera is in the *unrotated* mesh's coordinate
        // space rotated backwards - apply the inverse rotation to bring it
        // into the same local space the mesh data (face centers/corners) live in.
        val inverse = Quaternion(rotation).conjugate()
        val localRay = Ray(worldRay.origin.cpy().mul(inverse), worldRay.direction.cpy().mul(inverse).nor())

        val hit = Vector3()
        if (!Intersector.intersectRaySphere(localRay, Vector3.Zero, 1f, hit)) return false

        val faceIndex = nearestFace(hit.nor())
        onTileSelected?.invoke(TileInfo(faceIndex, world[faceIndex], world.resourcesAt(faceIndex)))
        return true
    }

    /**
     * Nearest face by angular distance to a point on the unit sphere. Not an
     * exact point-in-polygon test against each face's actual (irregular)
     * boundary, but a face's corners sit roughly evenly around its center, so
     * nearest-center is a very close approximation - and cheap enough to just
     * scan linearly since this only runs once per tap, not per frame.
     */
    private fun nearestFace(point: Vector3): Int {
        var bestIndex = 0
        var bestDot = -2f
        for (i in world.faces.indices) {
            val d = world.faces[i].center.dot(point)
            if (d > bestDot) {
                bestDot = d
                bestIndex = i
            }
        }
        return bestIndex
    }

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
        biomeTexture.dispose()
        compass.dispose()
    }

    companion object {
        private const val VERTEX_SIZE = 9 // position(3) + color(4) + texCoord(2)
        private const val ROTATE_SPEED_DEG = 0.3f

        private const val PREF_SEED = "seed"
        private const val PREF_RESOURCE_DENSITY = "resource_density"
        private const val PREF_NAVIGATION_FLIPPED = "navigation_flipped"

        private const val VERTEX_SHADER = """
            attribute vec4 a_position;
            attribute vec4 a_color;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projViewTrans;
            varying vec4 v_color;
            varying vec2 v_texCoord;
            void main() {
                v_color = a_color;
                v_texCoord = a_texCoord0;
                gl_Position = u_projViewTrans * a_position;
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_texCoord;
            uniform sampler2D u_texture;
            void main() {
                gl_FragColor = texture2D(u_texture, v_texCoord) * v_color;
            }
        """
    }
}
