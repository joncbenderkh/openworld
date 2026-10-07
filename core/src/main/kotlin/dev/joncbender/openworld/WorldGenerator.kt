package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere
import kotlin.math.abs
import kotlin.random.Random

/**
 * The generated world: a geodesic sphere where every face has one biome and zero or more resources.
 *
 * Stored compactly, since there is one of each per tile: a biome is one byte (its ordinal),
 * and a tile's resources are packed into one Int - a count in the low 3 bits, then each
 * resource's ordinal in order, 5 bits apiece (no biome offers more than [MAX_RESOURCES]).
 * Order is preserved exactly, so [resourcesAt] returns what [setResources] was given.
 */
class SphereWorld(val sphere: Sphere, private val biomes: ByteArray) {
    constructor(sphere: Sphere, biomes: Array<Biome>) : this(sphere, ByteArray(biomes.size) { biomes[it].ordinal.toByte() })

    private val resources = IntArray(biomes.size)

    operator fun get(faceIndex: Int): Biome = BIOMES[biomes[faceIndex].toInt()]
    operator fun set(faceIndex: Int, biome: Biome) {
        biomes[faceIndex] = biome.ordinal.toByte()
    }

    fun resourcesAt(faceIndex: Int): List<Resource> {
        val packed = resources[faceIndex]
        val count = packed and COUNT_MASK
        if (count == 0) return emptyList()
        return List(count) { RESOURCES[(packed ushr (COUNT_BITS + it * RESOURCE_BITS)) and RESOURCE_MASK] }
    }

    fun setResources(faceIndex: Int, value: List<Resource>) {
        require(value.size <= MAX_RESOURCES) { "a tile holds at most $MAX_RESOURCES resources, got ${value.size}" }
        resources[faceIndex] = 0
        for (resource in value) addResource(faceIndex, resource)
    }

    /** Appends [resource] after the tile's existing ones, without building a list. */
    fun addResource(faceIndex: Int, resource: Resource) {
        val packed = resources[faceIndex]
        val count = packed and COUNT_MASK
        require(count < MAX_RESOURCES) { "a tile holds at most $MAX_RESOURCES resources" }
        resources[faceIndex] = (packed and COUNT_MASK.inv()) or (resource.ordinal shl (COUNT_BITS + count * RESOURCE_BITS)) or (count + 1)
    }

    companion object {
        const val MAX_RESOURCES = 4
        private const val COUNT_BITS = 3
        private const val COUNT_MASK = (1 shl COUNT_BITS) - 1
        private const val RESOURCE_BITS = 5
        private const val RESOURCE_MASK = (1 shl RESOURCE_BITS) - 1

        private val BIOMES = Biome.entries.toTypedArray()
        private val RESOURCES = Resource.entries.toTypedArray()

        init {
            check(RESOURCES.size <= 1 shl RESOURCE_BITS) { "resource ordinals no longer fit $RESOURCE_BITS bits" }
            check(BIOMES.size <= 256) { "biome ordinals no longer fit a byte" }
        }
    }
}

/**
 * A breadth-first frontier over face indices, backed by one primitive array. Replaces
 * ArrayDeque<Int> plus a MutableList<Int> per flood-fill, which boxed every visited tile
 * (a 5x-size ocean is ~1.4M Integers) and made hydrology the heap's real high-water mark.
 * Everything ever added stays in [items] in add order - which is also the order tiles
 * are visited - so the tiles of one flood-fill are `items[begin until tail]`.
 */
private class FaceQueue(capacity: Int) {
    val items = IntArray(capacity)
    var head = 0
    var tail = 0

    fun isNotEmpty() = head < tail
    fun add(face: Int) { items[tail++] = face }
    fun removeFirst(): Int = items[head++]
    fun clear() { head = 0; tail = 0 }
}

/**
 * Elevation/moisture fBm noise sampled directly in 3D (no seams to stitch,
 * unlike a flat map) thresholded into biomes, plus a lightweight hydrology
 * pass (steepest-descent rivers, local-minimum lakes) using the sphere's
 * face-adjacency graph. Tuned for visual variety, not hydrological accuracy.
 */
class WorldGenerator(private val seed: Long) {

    private val elevationNoise = Noise3D(seed)
    private val moistureNoise = Noise3D(seed xor 0x9E3779B97F4A7C15UL.toLong())

    companion object {
        const val DEFAULT_RESOURCE_DENSITY = 0.18f

        // An ocean component smaller than this fraction of the largest one
        // is treated as a landlocked lake instead of part of the ocean.
        private const val LANDLOCKED_OCEAN_THRESHOLD = 0.05f

        // Mountains never actually border the ocean directly - the elevation
        // gradient always leaves a multi-tile buffer of foothills and lower
        // terrain in between (empirically, mountains only start appearing
        // ~5-6 tiles out) - so "coastal" for volcano placement means within
        // this many hex-hops of the ocean, not direct adjacency.
        private const val VOLCANO_MAX_OCEAN_DISTANCE = 6

        // Applied on top of the (already small) coastal-mountain pool, so
        // the final volcano count stays a small fraction of a percent of land.
        private const val VOLCANO_CHANCE = 0.3f

        // A standalone landmass this size or smaller is a candidate "hot spot"
        // island volcano - empirically, real islands of this scale sit only
        // ~0.01-0.02 above sea level (no real elevation "peak" to speak of),
        // so island volcano placement can't reuse the mountain-elevation rule.
        private const val ISLAND_VOLCANO_MAX_SIZE = 15

        // Applied per eligible small island, independent of VOLCANO_CHANCE.
        private const val ISLAND_VOLCANO_CHANCE = 0.2f
    }

    fun generate(frequency: Int, resourceDensity: Float = DEFAULT_RESOURCE_DENSITY): SphereWorld {
        val perf = PerfTimer()
        val sphere = GeodesicSphere.generate(frequency)
        perf.lap("GeodesicSphere.generate")
        val biomes = ByteArray(sphere.faceCount) { Biome.OCEAN.ordinal.toByte() }
        val elevation = FloatArray(sphere.faceCount)

        val elevationScale = 2.2f
        val moistureScale = 3.1f

        Parallel.forEachIndex(sphere.faceCount) { i ->
            val x = sphere.centerX(i)
            val y = sphere.centerY(i)
            val z = sphere.centerZ(i)
            var e = elevationNoise.fbm(x * elevationScale, y * elevationScale, z * elevationScale, octaves = 5)
            val latitude = abs(y) // sphere's Y axis is the polar axis: 0 at equator, 1 at poles
            e = (e - latitude * 0.15f).coerceIn(0f, 1f)
            elevation[i] = e
        }
        perf.lap("elevation")

        // All three thresholds were chosen empirically against a 10-seed
        // sample of this same elevation formula (the fBm's practical range
        // falls well short of its theoretical [0,1] max, so naive-looking
        // thresholds can be wildly off - e.g. the original 0.80 mountain
        // threshold made mountains all but impossible). seaLevel targets
        // ~30-40% land coverage (avg ~35%, vs. Earth's real ~29% - not
        // matched deliberately, just landing close); mountainLevel then
        // averages ~14% of that land (undershooting the real-world ~24%
        // figure on purpose, since a single top-down screenshot of one
        // hemisphere isn't a reliable way to validate that figure - elevation
        // noise clusters spatially and a sphere's near side is a biased,
        // foreshortened sample of the true global distribution); foothillsLevel
        // adds another ~14% of land as a transitional band below the mountains.
        val seaLevel = 0.48f
        val foothillsLevel = 0.58f
        val mountainLevel = 0.62f

        Parallel.forEachIndex(sphere.faceCount) { i ->
            val x = sphere.centerX(i)
            val y = sphere.centerY(i)
            val z = sphere.centerZ(i)
            val e = elevation[i]
            val m = moistureNoise.fbm(x * moistureScale, y * moistureScale, z * moistureScale, octaves = 4)
            val latitude = abs(y)

            biomes[i] = when {
                e < seaLevel -> Biome.OCEAN
                e >= mountainLevel -> Biome.MOUNTAIN
                e >= foothillsLevel -> Biome.FOOTHILLS
                latitude >= 0.88f -> Biome.ARCTIC
                m > 0.75f && e < seaLevel + 0.08f && latitude < 0.75f -> Biome.SWAMP
                latitude >= 0.75f -> if (m > 0.40f) Biome.TAIGA else Biome.TUNDRA
                latitude < 0.40f && m < 0.42f -> Biome.DESERT
                latitude < 0.35f && m > 0.65f -> Biome.JUNGLE
                latitude < 0.55f && m < 0.50f -> Biome.SAVANNAH
                m > 0.75f -> Biome.DEEP_FOREST
                m > 0.55f -> Biome.FOREST
                else -> Biome.PLAINS
            }.ordinal.toByte()
        }

        perf.lap("biomes")
        val world = SphereWorld(sphere, biomes)
        reclassifyLandlockedOceans(world)
        perf.lap("reclassifyLandlockedOceans")
        carveCoastalMountainVolcanoes(world)
        carveIslandVolcanoes(world, elevation)
        perf.lap("carveVolcanoes")
        carveLakes(world, elevation, seaLevel)
        perf.lap("carveLakes")
        carveRivers(world, elevation, seaLevel)
        perf.lap("carveRivers+depool")
        assignResources(world, resourceDensity)
        perf.lap("assignResources")
        return world
    }

    /**
     * "Ocean" should mean the one connected global body of water, the way a
     * player would read the map - not merely "any tile below sea level."
     * Below-sea-level depressions fully enclosed by land (an endorheic basin
     * like the real Caspian Sea) got the same OCEAN classification as the
     * actual ocean purely because of their elevation, even when they're a
     * tiny, clearly landlocked puddle miles from the coast. Reclassifies any
     * connected OCEAN component much smaller than the largest one as LAKE
     * instead (or, this close to a pole, as ARCTIC ice rather than a lake -
     * a landlocked pocket of water doesn't make sense there either) - a
     * relative threshold rather than an absolute size, since a world can
     * legitimately have several comparably large, separate oceans.
     */
    private fun reclassifyLandlockedOceans(world: SphereWorld) {
        val visited = BooleanArray(world.sphere.faceCount)
        // Every component's tiles, back to back in one array; each component is a range of it.
        val queue = FaceQueue(world.sphere.faceCount)
        val components = ArrayList<IntRange>()

        for (start in world.sphere.indices) {
            if (visited[start] || world[start] != Biome.OCEAN) continue

            val begin = queue.tail
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                world.sphere.forEachNeighbor(i) { n ->
                    if (!visited[n] && world[n] == Biome.OCEAN) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }
            components.add(begin until queue.tail)
        }

        val largestSize = components.maxOfOrNull { it.last - it.first + 1 } ?: return
        for (component in components) {
            if (component.last - component.first + 1 >= largestSize * LANDLOCKED_OCEAN_THRESHOLD) continue
            // A landlocked pocket this close to a pole isn't a lake either -
            // it reads as permanent ice, same as land would there (matching
            // the "no lakes in arctic regions" rule applied elsewhere).
            for (k in component) {
                val face = queue.items[k]
                world[face] = if (abs(world.sphere.centerY(face)) >= 0.88f) Biome.ARCTIC else Biome.LAKE
            }
        }
    }

    /**
     * Real volcanoes cluster in two very different settings: subduction-zone
     * coastal mountain ranges (the Andes, the Cascades), and standalone
     * oceanic hot-spot islands (Hawaii, Iceland). Those need two separate
     * placement rules here, not one: a genuinely small island never
     * accumulates enough elevation gradient to reach full MOUNTAIN status (a
     * 1-15 tile island's high point sits barely above sea level - there's no
     * "peak" for the elevation noise to have built), so a rule keyed off
     * MOUNTAIN can only ever produce the coastal-range kind. This handles
     * that one; [carveIslandVolcanoes] handles standalone islands.
     *
     * Reclassifies a small, random fraction of MOUNTAIN tiles within
     * [VOLCANO_MAX_OCEAN_DISTANCE] hex-hops of an OCEAN tile as VOLCANO. Runs
     * after [reclassifyLandlockedOceans] so "ocean" here means the real,
     * connected one, not a landlocked pocket that only looks like it on a map.
     */
    private fun carveCoastalMountainVolcanoes(world: SphereWorld) {
        val distanceToOcean = bfsDistanceToOcean(world)
        val rng = Random(seed xor 0x27D4EB2F165667C5UL.toLong())
        for (i in world.sphere.indices) {
            if (world[i] != Biome.MOUNTAIN) continue
            val distance = distanceToOcean[i]
            if (distance in 1..VOLCANO_MAX_OCEAN_DISTANCE && rng.nextFloat() < VOLCANO_CHANCE) {
                world[i] = Biome.VOLCANO
            }
        }
    }

    /**
     * Standalone hot-spot island volcanoes - see [carveCoastalMountainVolcanoes]
     * for why these need their own rule. A small enough connected landmass
     * (at most [ISLAND_VOLCANO_MAX_SIZE] tiles) that actually borders the
     * real ocean (not just a small patch of land inside a landlocked lake
     * deep within a continent) is a candidate island regardless of how
     * little elevation prominence it has, and with a modest random chance
     * its highest tile becomes the volcano that (fictionally) built it.
     */
    private fun carveIslandVolcanoes(world: SphereWorld, elevation: FloatArray) {
        fun isLand(i: Int) = world[i] != Biome.OCEAN && world[i] != Biome.LAKE

        val visited = BooleanArray(world.sphere.faceCount)
        val queue = FaceQueue(world.sphere.faceCount)
        val rng = Random(seed xor 0x9E6C63D0676A9A0FUL.toLong())
        for (start in world.sphere.indices) {
            if (visited[start] || !isLand(start)) continue

            // The whole landmass is walked even when it turns out too big to be an
            // island, so every one of its tiles is marked visited. After the walk,
            // the island is items[0 until tail] in visit order.
            queue.clear()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                world.sphere.forEachNeighbor(i) { n ->
                    if (!visited[n] && isLand(n)) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }

            val size = queue.tail
            if (size > ISLAND_VOLCANO_MAX_SIZE) continue
            // "Island" means surrounded by the real ocean, not just a small
            // patch of land inside a landlocked lake deep within a continent.
            var touchesOcean = false
            for (k in 0 until size) {
                if (world.sphere.anyNeighbor(queue.items[k]) { world[it] == Biome.OCEAN }) {
                    touchesOcean = true
                    break
                }
            }
            if (!touchesOcean) continue
            if (rng.nextFloat() >= ISLAND_VOLCANO_CHANCE) continue
            // First highest tile in visit order.
            var peak = queue.items[0]
            for (k in 1 until size) if (elevation[queue.items[k]] > elevation[peak]) peak = queue.items[k]
            world[peak] = Biome.VOLCANO
        }
    }

    private fun bfsDistanceToOcean(world: SphereWorld): IntArray {
        val distance = IntArray(world.sphere.faceCount) { -1 }
        val queue = FaceQueue(world.sphere.faceCount)
        for (i in world.sphere.indices) {
            if (world[i] == Biome.OCEAN) {
                distance[i] = 0
                queue.add(i)
            }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            world.sphere.forEachNeighbor(i) { n ->
                if (distance[n] == -1) {
                    distance[n] = distance[i] + 1
                    queue.add(n)
                }
            }
        }
        return distance
    }

    /**
     * Rolls each of a tile's (post-hydrology) biome's possible resources
     * independently at `density` odds, rather than one roll picking at most
     * one resource per tile - a tile can end up with several (e.g. a forest
     * tile with both wood and game), and biomes with more resource options
     * naturally read as richer without needing a separate density knob per
     * biome.
     */
    private fun assignResources(world: SphereWorld, density: Float) {
        val rng = Random(seed xor 0xC2B2AE3D27D4EB4FUL.toLong())
        for (i in world.sphere.indices) {
            val options = BIOME_RESOURCES[world[i]] ?: continue
            // Each roll goes straight into the tile's packed resources, so no
            // list is built per tile (hundreds of thousands at 5x the tile count).
            for (option in options) {
                if (rng.nextFloat() < density) world.addResource(i, option)
            }
        }
    }

    /**
     * A lake is a connected region of low-lying tiles that never touches the
     * ocean - not just a single tile lower than all its neighbors. That
     * single-tile test used to miss wide, gently-sloped basins entirely (no
     * individual tile in a nearly-flat depression is strictly lower than
     * every neighbor), leaving them for carveRivers to walk across and paint
     * as a broad river-textured blob instead of the lake they visually are.
     * Flood-filling the whole enclosed low band fixes that regardless of how
     * flat or wide the basin is.
     */
    private fun carveLakes(world: SphereWorld, elevation: FloatArray, seaLevel: Float) {
        val lakeBand = seaLevel + 0.06f
        val visited = BooleanArray(world.sphere.faceCount)

        fun isLakeCandidate(i: Int) = elevation[i] in seaLevel..lakeBand && world[i] != Biome.ARCTIC

        val queue = FaceQueue(world.sphere.faceCount)
        for (start in world.sphere.indices) {
            if (visited[start] || !isLakeCandidate(start)) continue

            queue.clear()
            queue.add(start)
            visited[start] = true
            var enclosed = true

            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                world.sphere.forEachNeighbor(i) { n ->
                    if (elevation[n] < seaLevel) enclosed = false // drains to the ocean - not a lake
                    if (!visited[n] && isLakeCandidate(n)) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }

            if (enclosed) for (k in 0 until queue.tail) world[queue.items[k]] = Biome.LAKE
        }
    }

    private fun carveRivers(world: SphereWorld, elevation: FloatArray, seaLevel: Float) {
        val rng = Random(seed)
        val sourceCount = world.sphere.faceCount / 6
        repeat(sourceCount) {
            var current = rng.nextInt(world.sphere.faceCount)
            if (elevation[current] < 0.65f) return@repeat

            var steps = 0
            while (steps < 200) {
                val e = elevation[current]
                if (e < seaLevel || world[current] == Biome.OCEAN || world[current] == Biome.LAKE) break
                if (world[current] == Biome.ARCTIC) break // rivers don't flow across permanent ice
                if (world[current] != Biome.MOUNTAIN && world[current] != Biome.VOLCANO) world[current] = Biome.RIVER

                val next = world.sphere.minNeighborBy(current) { elevation[it] }
                if (next == -1) break
                if (elevation[next] >= e) break
                current = next
                steps++
            }
        }

        depoolRivers(world)
    }

    /**
     * Many separate river walks can dead-end in the same inland depression
     * (a locally flat area with no path further downhill that still doesn't
     * qualify as a near-sea-level lake), painting it as a wide RIVER blob
     * instead of the pool it visually is. A real single-tile-wide river path
     * mostly has at most two RIVER neighbors (three at a rare confluence); a
     * pool's interior tiles touch many more. Reclassify any connected group
     * of RIVER tiles that's mostly high-degree like that as LAKE instead.
     */
    private fun depoolRivers(world: SphereWorld) {
        val visited = BooleanArray(world.sphere.faceCount)
        fun riverDegree(i: Int) = world.sphere.countNeighbors(i) { world[it] == Biome.RIVER }

        val queue = FaceQueue(world.sphere.faceCount)
        for (start in world.sphere.indices) {
            if (visited[start] || world[start] != Biome.RIVER) continue

            queue.clear()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                world.sphere.forEachNeighbor(i) { n ->
                    if (!visited[n] && world[n] == Biome.RIVER) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }

            val size = queue.tail
            var pooled = 0
            for (k in 0 until size) if (riverDegree(queue.items[k]) >= 4) pooled++
            val pooledFraction = pooled.toFloat() / size
            if (size >= 6 && pooledFraction > 0.15f) {
                for (k in 0 until size) world[queue.items[k]] = Biome.LAKE
            }
        }
    }
}
