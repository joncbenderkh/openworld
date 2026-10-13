package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
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
import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere

class GlobeScreen(private val options: GameOptions = GameOptions()) : Screen, GestureDetector.GestureAdapter() {

    // total tiles = 10*frequency^2 + 2 = 2,916,002 (~7.4x the 396,012 before the compact world). Too many
    // to draw at once: see CoarseWorldBuilder and the near/far terrain layers below.
    private val frequency = 540

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

    // options.storage is Gdx.files.local by default - the app's private files directory on Android.
    // The world is cached on disk so a launch with an unchanged seed/density can load it instead of
    // paying generation's cost (upwards of ten seconds at the game's current tile count) all over
    // again: the compact world itself (see CompactWorldCache), and the far layer's biomes
    // (see CoarseWorldCache), cached separately so a warm start can build that layer before the
    // world is loaded.
    private val compactCacheFile = options.storage("world_cache_compact.bin")
    private val coarseCacheFile = options.storage("world_cache_coarse.bin")

    // The world: fine tiles near land, a few large ocean tiles elsewhere (see WorldCompactor).
    private lateinit var compact: CompactWorld

    // The resource density the loaded world was made with; null while there is no world.
    private var loadedDensity: Float? = null

    // False while there is no world to inspect: before it loads, or when it could not be stored.
    private var worldReady = false

    // The tile outlined on the globe, or -1. Set by a tap; cleared by [clearSelection] and whenever the world goes away.
    @Volatile
    private var selectedTile = -1

    /** Removes the outline from the globe - what dismissing the tile's panel does. */
    fun clearSelection() {
        selectedTile = -1
    }

    // Built on a background thread once a world has loaded (see [prepareTilePicker]). Only picking
    // needs it, and building its coarse sphere on the render thread at the first tap is a visible hitch.
    @Volatile
    private var picker: TilePicker? = null

    private fun pickTile(x: Float, y: Float, z: Float): Int {
        val world = compact
        // A picker made for another world (a new one replaced it while this was being built) is not used.
        val current = picker?.takeIf { it.compact === world } ?: newTilePicker(world).also { picker = it }
        return current.pick(x, y, z)
    }

    private fun newTilePicker(world: CompactWorld) = TilePicker(world, GeodesicSphere.generate(WorldCompactor.COARSE_FREQUENCY))

    /** Builds the picker for the world that just loaded without making the first tap wait for it. */
    private fun prepareTilePicker() {
        val world = compact
        Thread {
            val perf = PerfTimer()
            val built = newTilePicker(world)
            perf.lap("tile picker built")
            if (compact === world) picker = built
        }.apply {
            name = "tile-picker"
            isDaemon = true
        }.start()
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

    /**
     * Fired with a sentence for the player when something that is not their doing goes wrong - the
     * world cannot be stored, say. Set by the launcher to show it; may be called on the GL thread.
     */
    var onMessage: ((String) -> Unit)? = null

    private val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        near = 0.1f
        far = 20f
        direction.set(0f, 0f, -1f)
        up.set(0f, 1f, 0f)
    }
    private var distance = 3f
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
    private val lightDirection = Vector3()
    private val cameraDirection = Vector3()
    private val capCenter = Vector3()
    private val inverseRotation = Quaternion()

    // Level of detail. farTerrain is always drawn: the whole globe at coarse resolution when the
    // world is finer than CoarseWorldBuilder.COARSE_FREQUENCY, otherwise the world itself.
    // nearTerrain is the full-resolution world, built patch by patch only while zoomed in
    // (null when no coarser level is needed).
    private var farTerrain: TerrainLayer? = null
    // A coarse all-ocean sphere under everything. The far and near layers leave out open ocean
    // (see DeepOcean), and this shows through where they do.
    private lateinit var underlay: TerrainLayer
    private var nearTerrain: TerrainLayer? = null
    private var nearStreamer: NearPatchStreamer? = null
    private var nearVisible = IntArray(0)
    private var nearPriority = FloatArray(0)
    private var nearActive = false
    private var nearInactiveFrames = 0
    private var nearWindowBuilds = 0
    private var frameCount = 0
    private lateinit var graticule: Mesh
    private lateinit var highlight: TileHighlight
    private lateinit var shader: ShaderProgram
    private lateinit var biomeTexture: Texture
    private lateinit var compass: Compass

    override fun show() {
        val perf = PerfTimer()
        biomeTexture = BiomeTextures.build()
        perf.lap("BiomeTextures.build")
        loadTerrain()
        perf.lap("terrain")
        graticule = Graticule.build()
        highlight = TileHighlight()
        perf.lap("Graticule.build")
        shader = ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(shader.isCompiled) { shader.log }
        compass = Compass()
        compass.resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.input.inputProcessor = InputMultiplexer(
            GestureDetector(this),
            object : InputAdapter() {
                override fun scrolled(amountX: Float, amountY: Float): Boolean {
                    zoomBy(1f + amountY * SCROLL_ZOOM_STEP)
                    return true
                }
            },
        )
    }

    /**
     * Regenerates the world and rebuilds the terrain mesh. Called from the
     * settings menu, either with a fresh random seed ("New world seed") or a
     * specific one the player typed in. A new world means whatever the player
     * collected no longer corresponds to anything on the map, so the
     * inventory resets along with it.
     */
    fun regenerateWorld(newSeed: Long = System.nanoTime()) {
        // Decide before touching anything: a refused regeneration leaves the current world playable.
        // The caches about to be replaced count as free space, since they are deleted first.
        val reclaimable = cacheFiles().sumOf { it.lengthOrZero() }
        if (!CacheFiles.hasRoomFor(compactCacheFile, MIN_FREE_BYTES_FOR_WORLD, reclaimable)) {
            // The settings menu may have changed the density just before asking; put it back, or the
            // next launch would find the saved world does not match it and try to replace that world.
            loadedDensity?.let { resourceDensity = it }
            onMessage?.invoke(NOT_ENOUGH_STORAGE)
            return
        }
        seed = newSeed
        preferences.putLong(PREF_SEED, seed)
        preferences.flush()
        inventory.clear()
        // Let go of everything that still references the old world before generating the new one:
        // the caches no longer match the seed, so loadWorld() generates, and the generator's full
        // sphere is ~175 MB.
        disposeTerrain()
        picker = null
        worldReady = false
        selectedTile = -1
        compact = placeholderWorld()
        loadWorld()
    }

    private fun FileHandle.lengthOrZero(): Long = if (exists()) length() else 0L

    /** Both caches and the temporary files a write that was cut short could have left beside them. */
    private fun cacheFiles(): List<FileHandle> =
        listOf(coarseCacheFile, compactCacheFile).flatMap { listOf(it, CacheFiles.temporaryOf(it)) }

    /**
     * Builds the underlay, then loads the world and builds both layers. Generation happens in
     * [generateAndCacheWorld], whose frame - and the ~175 MB full world in it - is gone by the time
     * the compact world is loaded; the interpreter would otherwise keep a dead reference alive.
     * On a warm start the far layer comes first, built from the cached coarse biomes while the heap
     * is still empty, so building the coarse sphere never overlaps the world.
     */
    private fun loadTerrain() {
        val perf = PerfTimer()
        buildUnderlay()
        perf.lap("ocean underlay")
        // The cache of builds before the compact world: ~180 MB nothing reads any more.
        options.storage("world_cache.bin").delete()
        loadWorld()
    }

    /**
     * Loads the world and builds both layers, or - if the world cannot be stored or read back -
     * leaves the globe bare (just the underlay) and tells the player why.
     */
    private fun loadWorld() {
        val problem = loadWorldOrExplain()
        worldReady = problem == null
        loadedDensity = if (problem == null) resourceDensity else null
        if (problem == null) prepareTilePicker()
        if (problem != null) {
            picker = null
            compact = placeholderWorld()
            onMessage?.invoke(problem)
        }
    }

    /** Null on success, otherwise what to tell the player. */
    private fun loadWorldOrExplain(): String? {
        val perf = PerfTimer()
        // Two attempts: the second only happens if a cache that looked current could not be read.
        repeat(2) {
            var coarseBiomes = CoarseWorldCache.load(coarseCacheFile, frequency, CoarseWorldBuilder.COARSE_FREQUENCY, seed)
            if (coarseBiomes == null || !CompactWorldCache.isCurrent(compactCacheFile, frequency, seed, resourceDensity)) {
                generateAndCacheWorld()?.let { return it }
                // The inventory stays: it belongs to the seed, which regenerateWorld() resets it for. A
                // miss here only means the cache is stale or missing (an app update that changed the
                // tile layout, say), not that the player's world is a different one.
                coarseBiomes = CoarseWorldCache.load(coarseCacheFile, frequency, CoarseWorldBuilder.COARSE_FREQUENCY, seed)
            }
            if (coarseBiomes != null) {
                farTerrain = buildFarLayer(CoarseWorldBuilder.fromBiomes(coarseBiomes))
                perf.lap("far layer from cached coarse biomes")
                val loaded = CompactWorldCache.load(compactCacheFile, frequency, seed, resourceDensity)
                if (loaded != null) {
                    picker = null
                    compact = loaded
                    perf.lap("world: loaded from cache")
                    buildNearLayer()
                    return null
                }
                farTerrain?.dispose()
                farTerrain = null
            }
            coarseCacheFile.delete()
            compactCacheFile.delete()
        }
        return WORLD_UNREADABLE
    }

    /** A tiny stand-in that lets the old world be collected before a new one is built. */
    private fun placeholderWorld() = CompactWorld(
        SphereWorld(GeodesicSphere.generate(1), ByteArray(12)), fineCount = 12, sourceFace = IntArray(12) { it }, coarseFace = IntArray(0),
    )

    /**
     * Generates the world and caches it, returning null or what to tell the player if it could not.
     * The full world never leaves this frame, so it is freed on return. The stale caches go first:
     * they cannot match, and their space is needed for the new ones - as is that of any temporary
     * file a killed save left behind.
     */
    private fun generateAndCacheWorld(): String? {
        cacheFiles().forEach { it.delete() }
        if (!CacheFiles.hasRoomFor(compactCacheFile, MIN_FREE_BYTES_FOR_WORLD)) return NOT_ENOUGH_STORAGE
        val perf = PerfTimer()
        val full = WorldGenerator(seed).generate(frequency, resourceDensity, corners = false)
        perf.lap("world: generated")
        if (!cacheCoarseBiomes(full)) return WORLD_NOT_SAVED
        if (!CompactWorldCache.save(compactCacheFile, full, frequency, seed, resourceDensity)) return WORLD_NOT_SAVED
        perf.lap("world: saved to cache")
        return null
    }

    /** In its own frame so the coarse world is freed before the compact cache is written. */
    private fun cacheCoarseBiomes(full: SphereWorld): Boolean {
        val coarse = CoarseWorldBuilder.build(full)
        return CoarseWorldCache.save(coarseCacheFile, coarse.biomeBytes(), frequency, CoarseWorldBuilder.COARSE_FREQUENCY, seed)
    }

    private fun buildFarLayer(source: SphereWorld): TerrainLayer {
        val draw = DeepOcean.tilesToDraw(source, FAR_DEEP_OCEAN_RINGS)
        Gdx.app?.log("perf", "far layer draws ${draw.count { it }} of ${draw.size} tiles (the rest is open ocean, left to the underlay)")
        return TerrainLayer(source, draw).also {
            it.buildAll()
            // A coarse world exists only to build this layer; let its sphere go.
            it.releaseSource()
        }
    }

    /** The ocean under everything: one all-ocean sphere at a frequency far coarser than either layer. */
    private fun buildUnderlay() {
        val sphere = GeodesicSphere.generate(UNDERLAY_FREQUENCY)
        val ocean = SphereWorld(sphere, ByteArray(sphere.faceCount) { Biome.OCEAN.ordinal.toByte() })
        underlay = TerrainLayer(ocean, flatColor = UNDERLAY_COLOR).also {
            it.buildAll()
            it.releaseSource()
        }
    }

    private fun buildNearLayer() {
        val world = compact.world
        val draw = DeepOcean.tilesToDraw(world, NEAR_DEEP_OCEAN_RINGS)
        // The ocean tiles are open ocean by definition, and far too big for a patch of fine tiles.
        for (t in compact.fineCount until compact.tileCount) draw[t] = false
        Gdx.app?.log("perf", "near layer covers ${draw.count { it }} of ${draw.size} tiles (the rest is open ocean)")
        val near = TerrainLayer(world, draw)
        nearTerrain = near
        nearStreamer = NearPatchStreamer(near.patchCount, options.maxNearPatches, { near.build(it) }, { near.release(it) })
        nearVisible = IntArray(near.patchCount)
        nearPriority = FloatArray(near.patchCount)
        nearActive = false
        nearInactiveFrames = 0
    }

    private fun disposeTerrain() {
        nearStreamer?.releaseAll()
        farTerrain?.dispose()
        farTerrain = null
        nearTerrain?.dispose()
        nearTerrain = null
        nearStreamer = null
    }

    /** Scales the camera's height above the surface by [factor], within the zoom limits. */
    fun zoomBy(factor: Float) {
        distance = CameraRig.zoomed(distance, factor)
    }

    /** Turns the globe by [degrees] about the camera's view axis - what a two-finger twist does. */
    fun twist(degrees: Float) {
        rotation.mulLeft(Quaternion(Vector3.Z, degrees))
    }

    /** Spins the globe back to its starting orientation - the world itself is untouched. */
    fun resetOrientation() {
        rotation.idt()
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        camera.position.set(0f, 0f, distance)
        camera.near = CameraRig.nearPlane(distance)
        camera.far = CameraRig.farPlane(distance)
        camera.update()

        modelMatrix.idt().rotate(rotation)
        mvpMatrix.set(camera.combined).mul(modelMatrix)

        biomeTexture.bind(0)
        shader.bind()
        shader.setUniformMatrix("u_projViewTrans", mvpMatrix)
        shader.setUniformi("u_texture", 0)
        // The light is fixed in view space; the shader works in the globe's frame, so turn it back.
        lightDirection.set(Lighting.LIGHT_X, Lighting.LIGHT_Y, Lighting.LIGHT_Z)
        inverseRotation.set(rotation).conjugate().transform(lightDirection)
        shader.setUniformf("u_lightDir", lightDirection)
        shader.setUniformf("u_lit", 1f)
        // Only the terrain is back-face culled: its triangles are known to wind
        // counter-clockwise from outside (see GeodesicSphereTest), while the
        // graticule's quads make no such promise.
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        Gdx.gl.glCullFace(GL20.GL_BACK)
        drawVisibleTerrain()
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        shader.setUniformf("u_lit", 0f)
        // Its lines are a fixed width on the globe, so up close they would cover the tiles.
        if (distance > CameraRig.GRATICULE_HIDDEN_BELOW) graticule.render(shader, GL20.GL_TRIANGLES)
        drawHighlight()

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

        // The layers tile the same surface but not with the same polygons, so they are only a
        // few millionths apart in depth. Push each one back behind the layers drawn over it:
        // underlay furthest, then the far layer (only while the near layer is up), then near.
        Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL)
        Gdx.gl.glPolygonOffset(UNDERLAY_POLYGON_OFFSET_FACTOR, UNDERLAY_POLYGON_OFFSET_UNITS)
        drawLayer(underlay)
        if (near != null && nearActive) {
            Gdx.gl.glPolygonOffset(FAR_POLYGON_OFFSET_FACTOR, FAR_POLYGON_OFFSET_UNITS)
            farTerrain?.let { drawLayer(it) }
            Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
            drawNear(near)
        } else {
            Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
            farTerrain?.let { drawLayer(it) }
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

    /**
     * Outlines the selected tile, at least a couple of pixels thick however far out the camera is. It is
     * drawn over everything else (the depth buffer is cleared first) so that nothing covers it and it can
     * lie exactly on the tile; a tile on the far side of the globe is skipped instead.
     */
    private fun drawHighlight() {
        val tile = selectedTile
        if (tile < 0 || !worldReady) return
        val sphere = compact.world.sphere
        val toTile = sphere.centerX(tile) * cameraDirection.x + sphere.centerY(tile) * cameraDirection.y + sphere.centerZ(tile) * cameraDirection.z
        if (toTile < 1f / distance) return
        val pixel = CameraRig.pixelSize(distance, camera.viewportHeight)
        highlight.update(sphere, tile, maxOf(pixel * maxOf(HIGHLIGHT_MIN_HALF_WIDTH_PIXELS, HIGHLIGHT_MIN_HALF_WIDTH_DP * Gdx.graphics.density), HIGHLIGHT_RELATIVE_HALF_WIDTH * tileRadius(sphere, tile)))
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        highlight.render(shader)
    }

    private fun tileRadius(sphere: Sphere, tile: Int): Float {
        val dx = sphere.cornerX(tile, 0) - sphere.centerX(tile)
        val dy = sphere.cornerY(tile, 0) - sphere.centerY(tile)
        val dz = sphere.cornerZ(tile, 0) - sphere.centerZ(tile)
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
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
        val speed = ROTATE_SPEED_DEG * CameraRig.dragScale(distance)
        rotation.mulLeft(Quaternion(Vector3.Y, -deltaX * sign * speed))
        rotation.mulLeft(Quaternion(Vector3.X, -deltaY * sign * speed))
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
        this.distance = CameraRig.zoomed(start, ratio)
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

        if (!worldReady) return false
        val point = hit.nor()
        val tile = pickTile(point.x, point.y, point.z)
        val world = compact.world
        selectedTile = tile
        onTileSelected?.invoke(TileInfo(tile, world[tile], world.resourcesAt(tile), compact.isOpenOcean(tile)))
        return true
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
        underlay.dispose()
        graticule.dispose()
        highlight.dispose()
        shader.dispose()
        biomeTexture.dispose()
        compass.dispose()
    }

    companion object {
        // The compact cache is ~80-90 MB; a world with more land is bigger, and the coarse cache is small.
        private const val MIN_FREE_BYTES_FOR_WORLD = 160L * 1024 * 1024
        private const val NOT_ENOUGH_STORAGE = "Not enough free storage to create the world (about 160 MB is needed)."
        private const val WORLD_NOT_SAVED = "The world could not be saved, so it cannot be shown. Free some storage and start a new world."
        private const val WORLD_UNREADABLE = "The saved world could not be read. Start a new world."

        // Never thinner than this on screen: 2.5 pixels, or 2.2 dp on a dense phone screen where that is a speck.
        private const val HIGHLIGHT_MIN_HALF_WIDTH_PIXELS = 2.5f
        private const val HIGHLIGHT_MIN_HALF_WIDTH_DP = 2.2f
        private const val HIGHLIGHT_RELATIVE_HALF_WIDTH = 0.08f

        private const val ROTATE_SPEED_DEG = 0.3f
        // Fraction of the camera's height above the surface changed per wheel notch.
        private const val SCROLL_ZOOM_STEP = 0.1f

        // Level of detail. The near layer switches on once a full-resolution tile is a few
        // pixels wide (at a 5x tile count, around this camera distance) and off again
        // slightly further out, so hovering at the threshold doesn't flicker.
        private const val NEAR_ENTER_DISTANCE = 2.2f
        private const val NEAR_EXIT_DISTANCE = 2.4f
        // Frames out of use (3 s at 60 fps) before the near layer's patches are freed.
        private const val NEAR_RELEASE_AFTER_FRAMES = 180
        // Time a frame may spend building near patches (at least one is always built).
        private const val NEAR_BUILD_BUDGET_NANOS = 4_000_000L
        private const val NEAR_LOG_EVERY_FRAMES = 120
        private const val FAR_POLYGON_OFFSET_FACTOR = 2f
        private const val FAR_POLYGON_OFFSET_UNITS = 4f
        private const val UNDERLAY_POLYGON_OFFSET_FACTOR = 4f
        private const val UNDERLAY_POLYGON_OFFSET_UNITS = 8f

        // Open ocean (see DeepOcean) is left to the underlay: ocean tiles with no land within this
        // many tiles of themselves, counted in each layer's own tiles. Wider keeps more of the
        // shallows at full detail.
        private const val FAR_DEEP_OCEAN_RINGS = 2
        private const val NEAR_DEEP_OCEAN_RINGS = 3
        // The underlay's sphere. It is one flat color, so only its silhouette matters: at this
        // frequency (~16k tiles, each ~1.7 degrees across) the polygons stray from a true sphere by
        // well under a pixel, and it builds far faster than a finer one.
        private const val UNDERLAY_FREQUENCY = 40
        // Open ocean is one flat color: what the ocean's art averages to (see BiomeArt.meanColor).
        private val UNDERLAY_COLOR = BiomeTextures.meanColor(Biome.OCEAN).let { Color(it[0], it[1], it[2], 1f) }

        private const val PREF_SEED = "seed"
        private const val PREF_RESOURCE_DENSITY = "resource_density"
        private const val PREF_NAVIGATION_FLIPPED = "navigation_flipped"

        private const val VERTEX_SHADER = """
            attribute vec4 a_position;
            attribute vec4 a_color;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projViewTrans;
            uniform vec3 u_lightDir;
            uniform float u_lit;
            varying vec4 v_color;
            varying vec2 v_texCoord;
            void main() {
                float shade = ${Lighting.AMBIENT} + ${Lighting.DIFFUSE} * max(dot(a_position.xyz, u_lightDir), 0.0);
                v_color = a_color * vec4(vec3(mix(1.0, shade, u_lit)), 1.0);
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
