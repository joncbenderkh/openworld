package dev.joncbender.openworld

import dev.joncbender.openworld.geo.Face
import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.math.abs
import kotlin.random.Random

/** The generated world: a geodesic sphere where every face has one biome. */
class SphereWorld(val faces: List<Face>, private val biomes: Array<Biome>) {
    operator fun get(faceIndex: Int): Biome = biomes[faceIndex]
    operator fun set(faceIndex: Int, biome: Biome) {
        biomes[faceIndex] = biome
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

    fun generate(frequency: Int): SphereWorld {
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
                m > 0.75f && e < seaLevel + 0.08f -> Biome.SWAMP
                latitude < 0.35f && m < 0.30f -> Biome.DESERT
                latitude < 0.55f && m < 0.50f -> Biome.SAVANNAH
                m > 0.55f -> Biome.FOREST
                else -> Biome.PLAINS
            }
        }

        val world = SphereWorld(faces, biomes)
        carveLakes(world, elevation, seaLevel)
        carveRivers(world, elevation, seaLevel)
        return world
    }

    private fun carveLakes(world: SphereWorld, elevation: FloatArray, seaLevel: Float) {
        val lakeBand = seaLevel + 0.06f
        for (i in world.faces.indices) {
            val e = elevation[i]
            if (e < seaLevel || e > lakeBand) continue
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
                if (world[current] != Biome.MOUNTAIN) world[current] = Biome.RIVER

                val next = world.faces[current].neighbors.minByOrNull { elevation[it] } ?: break
                if (elevation[next] >= e) break
                current = next
                steps++
            }
        }
    }
}
