package dev.joncbender.openworld

import dev.joncbender.openworld.geo.Face
import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.abs
import kotlin.random.Random

/** The generated world: a geodesic sphere where every face has one biome and an optional resource. */
class SphereWorld(val faces: List<Face>, private val biomes: Array<Biome>) {
    private val resources = arrayOfNulls<Resource>(biomes.size)

    operator fun get(faceIndex: Int): Biome = biomes[faceIndex]
    operator fun set(faceIndex: Int, biome: Biome) {
        biomes[faceIndex] = biome
    }

    fun resourceAt(faceIndex: Int): Resource? = resources[faceIndex]
    fun setResource(faceIndex: Int, resource: Resource?) {
        resources[faceIndex] = resource
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
        const val DEFAULT_RESOURCE_DENSITY = 0.12f
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

        val seaLevel = 0.42f
        val mountainLevel = 0.80f

        for ((i, face) in faces.withIndex()) {
            val p = face.center
            val e = elevation[i]
            val m = moistureNoise.fbm(p.x * moistureScale, p.y * moistureScale, p.z * moistureScale, octaves = 4)
            val latitude = abs(p.y)

            biomes[i] = when {
                e < seaLevel -> Biome.OCEAN
                e >= mountainLevel -> Biome.MOUNTAIN
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
        carveLakes(world, elevation, seaLevel)
        carveRivers(world, elevation, seaLevel)
        assignResources(world, resourceDensity)
        return world
    }

    /** Rolls a resource for a fraction of tiles, drawn from whatever their (post-hydrology) biome allows. */
    private fun assignResources(world: SphereWorld, density: Float) {
        val rng = Random(seed xor 0xC2B2AE3D27D4EB4FUL.toLong())
        for (i in world.faces.indices) {
            if (rng.nextFloat() >= density) continue
            val options = BIOME_RESOURCES[world[i]] ?: continue
            if (options.isEmpty()) continue
            world.setResource(i, options[rng.nextInt(options.size)])
        }
    }

    private fun carveLakes(world: SphereWorld, elevation: FloatArray, seaLevel: Float) {
        val lakeBand = seaLevel + 0.06f
        for (i in world.faces.indices) {
            val e = elevation[i]
            if (e < seaLevel || e > lakeBand) continue
            if (world[i] == Biome.ARCTIC) continue // permanent ice, not liquid water
            val isBasin = world.faces[i].neighbors.all { elevation[it] >= e }
            if (isBasin) world[i] = Biome.LAKE
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
    }
}
