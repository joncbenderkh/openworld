package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
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

    private val frequency = 199 // total tiles = 10*frequency^2 + 2 (~5x the previous 79212)

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

    // Gdx.files.local resolves to the app's private files directory on
    // Android - a plain on-disk cache of the generated world (see
    // WorldCache) so a launch with an unchanged seed/density can load it
    // instead of paying generation's cost (upwards of ten seconds at the
    // game's current tile count) all over again.
    private val worldCacheFile = Gdx.files.local("world_cache.bin")

    private var world = run {
        val perf = PerfTimer()
        val cached = WorldCache.load(worldCacheFile, frequency, seed, resourceDensity)
        if (cached != null) {
            perf.lap("world: loaded from cache")
            cached
        } else {
            // A cache miss here (as opposed to regenerateWorld()'s explicit
            // reseed) can still mean this is a genuinely different world from
            // whatever the inventory was tracking - e.g. a stale/missing
            // cache file, or a frequency bump from an app update changing
            // the whole tile layout even with the same seed. Clearing keeps
            // the same "persists between restarts, not between worlds" rule
            // regenerateWorld() already follows; harmless on a first-ever
            // launch, where the inventory is already empty.
            inventory.clear()
            val generated = WorldGenerator(seed).generate(frequency, resourceDensity)
            perf.lap("world: generated")
            WorldCache.save(worldCacheFile, generated, frequency, seed, resourceDensity)
            perf.lap("world: saved to cache")
            generated
        }
    }

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
    private val cameraDirection = Vector3()
    private val capCenter = Vector3()
    private val inverseRotation = Quaternion()

    // Level of detail. farTerrain is always drawn: the whole globe at coarse resolution when the
    // world is finer than CoarseWorldBuilder.COARSE_FREQUENCY, otherwise the world itself.
    // nearTerrain is the full-resolution world, built patch by patch only while zoomed in
    // (null when no coarser level is needed).
    private lateinit var farTerrain: TerrainLayer
    private var nearTerrain: TerrainLayer? = null
    private var nearStreamer: NearPatchStreamer? = null
    private var nearVisible = IntArray(0)
    private var nearPriority = FloatArray(0)
    private var nearActive = false
    private var nearInactiveFrames = 0
    private var nearWindowBuilds = 0
    private var frameCount = 0
    private lateinit var graticule: Mesh
    private lateinit var shader: ShaderProgram
    private lateinit var biomeTexture: Texture
    private lateinit var compass: Compass

    override fun show() {
        val perf = PerfTimer()
        biomeTexture = BiomeTextures.build()
        perf.lap("BiomeTextures.build")
        buildTerrain()
        perf.lap("buildMeshes")
        graticule = Graticule.build()
        perf.lap("Graticule.build")
        shader = ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(shader.isCompiled) { shader.log }
        compass = Compass()
        compass.resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.input.inputProcessor = InputMultiplexer(GestureDetector(this))
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
        WorldCache.save(worldCacheFile, world, frequency, seed, resourceDensity)
        disposeTerrain()
        buildTerrain()
    }

    private fun buildTerrain() {
        val coarse = if (frequency > CoarseWorldBuilder.COARSE_FREQUENCY) CoarseWorldBuilder.build(world) else null
        farTerrain = TerrainLayer(coarse ?: world).also {
            it.buildAll()
            // A coarse world exists only to build this layer; let its sphere go.
            if (coarse != null) it.releaseSource()
        }
        val near = if (coarse != null) TerrainLayer(world) else null
        nearTerrain = near
        nearStreamer = near?.let { layer ->
            NearPatchStreamer(layer.patchCount, MAX_NEAR_PATCHES, { layer.build(it) }, { layer.release(it) })
        }
        nearVisible = IntArray(near?.patchCount ?: 0)
        nearPriority = FloatArray(near?.patchCount ?: 0)
        nearActive = false
        nearInactiveFrames = 0
    }

    private fun disposeTerrain() {
        nearStreamer?.releaseAll()
        farTerrain.dispose()
        nearTerrain?.dispose()
    }

    /** Spins the globe back to its starting orientation - the world itself is untouched. */
    fun resetOrientation() {
        rotation.idt()
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
        // Only the terrain is back-face culled: its triangles are known to wind
        // counter-clockwise from outside (see GeodesicSphereTest), while the
        // graticule's quads make no such promise.
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        Gdx.gl.glCullFace(GL20.GL_BACK)
        drawVisibleTerrain()
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        graticule.render(shader, GL20.GL_TRIANGLES)

        compass.render(rotation)
    }

    /**
     * Draws the terrain: the far layer always, plus - once zoomed in close enough that a
     * full-resolution tile is several pixels wide - the near layer on top of it.
     */
    private fun drawVisibleTerrain() {
        frameCount++
        // The camera's direction in the globe's own frame: undo the globe's rotation.
        cameraDirection.set(0f, 0f, 1f)
        inverseRotation.set(rotation).conjugate().transform(cameraDirection)

        val near = nearTerrain
        updateNearActive()
        if (near != null && nearActive) {
            // The two layers tile the same surface but not with the same polygons, so they
            // are only a few millionths apart in depth: push the far layer back so the
            // near patches always win where they exist.
            Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL)
            Gdx.gl.glPolygonOffset(FAR_POLYGON_OFFSET_FACTOR, FAR_POLYGON_OFFSET_UNITS)
            drawLayer(farTerrain)
            Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
            drawNear(near)
        } else {
            drawLayer(farTerrain)
        }
    }

    /**
     * Switches the near layer on below [NEAR_ENTER_DISTANCE] and back off above
     * [NEAR_EXIT_DISTANCE] (a gap, so hovering at the threshold doesn't flicker), and frees
     * its patches once it has been off for a while.
     */
    private fun updateNearActive() {
        if (nearTerrain == null) return
        if (!nearActive && distance < NEAR_ENTER_DISTANCE) nearActive = true
        else if (nearActive && distance > NEAR_EXIT_DISTANCE) nearActive = false

        nearInactiveFrames = if (nearActive) 0 else nearInactiveFrames + 1
        if (nearInactiveFrames == NEAR_RELEASE_AFTER_FRAMES) nearStreamer?.releaseAll()
    }

    /** Draws every visible, built patch of an eagerly built [layer]. */
    private fun drawLayer(layer: TerrainLayer) {
        for (patch in 0 until layer.patchCount) {
            val mesh = layer.meshOrNull(patch) ?: continue
            if (!isVisible(layer.cap(patch))) continue
            mesh.render(shader, GL20.GL_TRIANGLES)
        }
    }

    /**
     * Draws the near layer's visible patches, building the missing ones (closest to the view's
     * center first) within a small per-frame time budget. A patch not built yet just shows the
     * far layer beneath it.
     */
    private fun drawNear(near: TerrainLayer) {
        val streamer = nearStreamer ?: return
        var visibleCount = 0
        for (patch in 0 until near.patchCount) {
            val cap = near.cap(patch)
            if (!isVisible(cap)) continue
            nearVisible[visibleCount++] = patch
            nearPriority[patch] = cap.ax * cameraDirection.x + cap.ay * cameraDirection.y + cap.az * cameraDirection.z
        }

        nearWindowBuilds += streamer.update(frameCount, nearVisible, visibleCount, nearPriority, NEAR_BUILD_BUDGET_NANOS)
        for (k in 0 until visibleCount) near.meshOrNull(nearVisible[k])?.render(shader, GL20.GL_TRIANGLES)

        if (frameCount % NEAR_LOG_EVERY_FRAMES == 0) {
            Gdx.app?.log(
                "perf",
                "near layer: ${streamer.residentCount}/${near.patchCount} patches resident, $visibleCount in view, " +
                    "$nearWindowBuilds built in the last $NEAR_LOG_EVERY_FRAMES frames",
            )
            nearWindowBuilds = 0
        }
    }

    /** Whether any part of [cap] can be seen. Call after [cameraDirection] is set for this frame. */
    private fun isVisible(cap: SphereCap): Boolean {
        if (cap.isBeyondHorizon(cameraDirection.x, cameraDirection.y, cameraDirection.z, distance)) return false
        capCenter.set(cap.ax, cap.ay, cap.az)
        rotation.transform(capCenter)
        return camera.frustum.sphereInFrustum(capCenter, cap.chordRadius)
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
        val sphere = world.sphere
        for (i in sphere.indices) {
            val d = sphere.centerX(i) * point.x + sphere.centerY(i) * point.y + sphere.centerZ(i) * point.z
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
        disposeTerrain()
        graticule.dispose()
        shader.dispose()
        biomeTexture.dispose()
        compass.dispose()
    }

    companion object {
        private const val ROTATE_SPEED_DEG = 0.3f

        // Level of detail. The near layer switches on once a full-resolution tile is a few
        // pixels wide (at a 5x tile count, around this camera distance) and off again
        // slightly further out, so hovering at the threshold doesn't flicker.
        private const val NEAR_ENTER_DISTANCE = 2.2f
        private const val NEAR_EXIT_DISTANCE = 2.4f
        // Frames out of use (3 s at 60 fps) before the near layer's patches are freed.
        private const val NEAR_RELEASE_AFTER_FRAMES = 180
        // A cap on resident near patches: ~2.5k tiles each, so ~600k tiles in all. Patches in
        // view are never evicted, so more than this can be resident while they are all visible.
        private const val MAX_NEAR_PATCHES = 256
        // Time a frame may spend building near patches (at least one is always built).
        private const val NEAR_BUILD_BUDGET_NANOS = 4_000_000L
        private const val NEAR_LOG_EVERY_FRAMES = 120
        private const val FAR_POLYGON_OFFSET_FACTOR = 2f
        private const val FAR_POLYGON_OFFSET_UNITS = 4f

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
