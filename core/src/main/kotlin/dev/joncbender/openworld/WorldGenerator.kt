package dev.joncbender.openworld

import dev.joncbender.openworld.geo.Face
import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.abs
import kotlin.random.Random

/** The generated world: a geodesic sphere where every face has one biome and zero or more resources. */
class SphereWorld(val faces: List<Face>, private val biomes: Array<Biome>) {
    private val resources = Array<List<Resource>>(biomes.size) { emptyList() }

    operator fun get(faceIndex: Int): Biome = biomes[faceIndex]
    operator fun set(faceIndex: Int, biome: Biome) {
        biomes[faceIndex] = biome
    }

    fun resourcesAt(faceIndex: Int): List<Resource> = resources[faceIndex]
    fun setResources(faceIndex: Int, value: List<Resource>) {
        resources[faceIndex] = value
    }
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
    }

    fun generate(frequency: Int, resourceDensity: Float = DEFAULT_RESOURCE_DENSITY): SphereWorld {
        val faces = GeodesicSphere.generate(frequency)
        val biomes = Array(faces.size) { Biome.OCEAN }
        val elevation = FloatArray(faces.size)

        val elevationScale = 2.2f
        val moistureScale = 3.1f

        for ((i, face) in faces.withIndex()) {
            val p = face.center
            var e = elevationNoise.fbm(p.x * elevationScale, p.y * elevationScale, p.z * elevationScale, octaves = 5)
            val latitude = abs(p.y) // sphere's Y axis is the polar axis: 0 at equator, 1 at poles
            e = (e - latitude * 0.15f).coerceIn(0f, 1f)
            elevation[i] = e
        }

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

        for ((i, face) in faces.withIndex()) {
            val p = face.center
            val e = elevation[i]
            val m = moistureNoise.fbm(p.x * moistureScale, p.y * moistureScale, p.z * moistureScale, octaves = 4)
            val latitude = abs(p.y)

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
            }
        }

        val world = SphereWorld(faces, biomes)
        reclassifyLandlockedOceans(world)
        carveLakes(world, elevation, seaLevel)
        carveRivers(world, elevation, seaLevel)
        assignResources(world, resourceDensity)
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
        val visited = BooleanArray(world.faces.size)
        val components = mutableListOf<List<Int>>()

        for (start in world.faces.indices) {
            if (visited[start] || world[start] != Biome.OCEAN) continue

            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component.add(i)
                for (n in world.faces[i].neighbors) {
                    if (!visited[n] && world[n] == Biome.OCEAN) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }
            components.add(component)
        }

        val largestSize = components.maxOfOrNull { it.size } ?: return
        for (component in components) {
            if (component.size >= largestSize * LANDLOCKED_OCEAN_THRESHOLD) continue
            // A landlocked pocket this close to a pole isn't a lake either -
            // it reads as permanent ice, same as land would there (matching
            // the "no lakes in arctic regions" rule applied elsewhere).
            component.forEach {
                world[it] = if (abs(world.faces[it].center.y) >= 0.88f) Biome.ARCTIC else Biome.LAKE
            }
        }
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
        for (i in world.faces.indices) {
            val options = BIOME_RESOURCES[world[i]] ?: continue
            val rolled = options.filter { rng.nextFloat() < density }
            if (rolled.isNotEmpty()) world.setResources(i, rolled)
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
        val visited = BooleanArray(world.faces.size)

        fun isLakeCandidate(i: Int) = elevation[i] in seaLevel..lakeBand && world[i] != Biome.ARCTIC

        for (start in world.faces.indices) {
            if (visited[start] || !isLakeCandidate(start)) continue

            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            var enclosed = true

            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component.add(i)
                for (n in world.faces[i].neighbors) {
                    if (elevation[n] < seaLevel) enclosed = false // drains to the ocean - not a lake
                    if (!visited[n] && isLakeCandidate(n)) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }

            if (enclosed) component.forEach { world[it] = Biome.LAKE }
        }
    }

    private fun carveRivers(world: SphereWorld, elevation: FloatArray, seaLevel: Float) {
        val rng = Random(seed)
        val sourceCount = world.faces.size / 6
        repeat(sourceCount) {
            var current = rng.nextInt(world.faces.size)
            if (elevation[current] < 0.65f) return@repeat

            var steps = 0
            while (steps < 200) {
                val e = elevation[current]
                if (e < seaLevel || world[current] == Biome.OCEAN || world[current] == Biome.LAKE) break
                if (world[current] == Biome.ARCTIC) break // rivers don't flow across permanent ice
                if (world[current] != Biome.MOUNTAIN) world[current] = Biome.RIVER

                val next = world.faces[current].neighbors.minByOrNull { elevation[it] } ?: break
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
        val visited = BooleanArray(world.faces.size)
        fun riverDegree(i: Int) = world.faces[i].neighbors.count { world[it] == Biome.RIVER }

        for (start in world.faces.indices) {
            if (visited[start] || world[start] != Biome.RIVER) continue

            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component.add(i)
                for (n in world.faces[i].neighbors) {
                    if (!visited[n] && world[n] == Biome.RIVER) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }

            val pooledFraction = component.count { riverDegree(it) >= 4 }.toFloat() / component.size
            if (component.size >= 6 && pooledFraction > 0.15f) {
                component.forEach { world[it] = Biome.LAKE }
            }
        }
    }
}
