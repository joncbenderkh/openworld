package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** The generated world: a wrapping hex grid where every cell has one biome. */
class HexWorld(val width: Int, val height: Int) {
    private val biomes = Array(width * height) { Biome.OCEAN }

    private fun index(c: HexCoord): Int = c.row * width + (((c.col % width) + width) % width)

    operator fun get(c: HexCoord): Biome = biomes[index(c)]
    operator fun set(c: HexCoord, biome: Biome) {
        biomes[index(c)] = biome
    }
}

/**
 * Elevation/moisture fBm noise thresholded into biomes, plus a lightweight
 * hydrology pass (steepest-descent rivers, local-minimum lakes) layered on
 * top. This is a first pass tuned for visual variety, not hydrological
 * accuracy.
 */
class WorldGenerator(private val seed: Long) {

    private val elevationNoise = TileableNoise(seed)
    private val moistureNoise = TileableNoise(seed xor 0x9E3779B97F4A7C15UL.toLong())

    fun generate(width: Int, height: Int): HexWorld {
        val world = HexWorld(width, height)
        val elevation = Array(height) { FloatArray(width) }
        val moisture = Array(height) { FloatArray(width) }

        for (row in 0 until height) {
            for (col in 0 until width) {
                val fracX = col.toFloat() / width
                val latitude = abs(row.toFloat() / height - 0.5f) * 2f // 0 at equator, 1 at poles
                var e = elevationNoise.fbm(fracX, row.toFloat(), baseCyclesX = 4, octaves = 5)
                e = (e - latitude * 0.15f).coerceIn(0f, 1f) // slight polar cooling toward lower "landiness"
                elevation[row][col] = e
                moisture[row][col] = moistureNoise.fbm(fracX, row.toFloat(), baseCyclesX = 6, octaves = 4)
            }
        }

        val seaLevel = 0.42f
        val mountainLevel = 0.80f

        for (row in 0 until height) {
            for (col in 0 until width) {
                val e = elevation[row][col]
                val m = moisture[row][col]
                val latitude = abs(row.toFloat() / height - 0.5f) * 2f

                val biome = when {
                    e < seaLevel -> Biome.OCEAN
                    e >= mountainLevel -> Biome.MOUNTAIN
                    m > 0.75f && e < seaLevel + 0.08f -> Biome.SWAMP
                    latitude < 0.35f && m < 0.30f -> Biome.DESERT
                    latitude < 0.55f && m < 0.50f -> Biome.SAVANNAH
                    m > 0.55f -> Biome.FOREST
                    else -> Biome.PLAINS
                }
                world[HexCoord(col, row)] = biome
            }
        }

        carveLakes(world, elevation, width, height, seaLevel)
        carveRivers(world, elevation, width, height, seaLevel)

        return world
    }

    private fun carveLakes(world: HexWorld, elevation: Array<FloatArray>, width: Int, height: Int, seaLevel: Float) {
        val lakeBand = seaLevel + 0.06f
        for (row in 0 until height) {
            for (col in 0 until width) {
                val e = elevation[row][col]
                if (e < seaLevel || e > lakeBand) continue
                val coord = HexCoord(col, row)
                val isBasin = coord.neighbors(width, height).all { elevation[it.row][it.col] >= e }
                if (isBasin) world[coord] = Biome.LAKE
            }
        }
    }

    private fun carveRivers(world: HexWorld, elevation: Array<FloatArray>, width: Int, height: Int, seaLevel: Float) {
        val rng = Random(seed)
        val sourceCount = min(width, height)
        repeat(sourceCount) {
            var current = HexCoord(rng.nextInt(width), rng.nextInt(height))
            if (elevation[current.row][current.col] < 0.65f) return@repeat

            var steps = 0
            while (steps < width + height) {
                val e = elevation[current.row][current.col]
                if (e < seaLevel || world[current] == Biome.OCEAN || world[current] == Biome.LAKE) break

                if (world[current] != Biome.MOUNTAIN) world[current] = Biome.RIVER

                val next = current.neighbors(width, height)
                    .minByOrNull { elevation[it.row][it.col] }
                    ?: break
                if (elevation[next.row][next.col] >= e) break // no downhill neighbor: river ends
                current = next
                steps++
            }
        }
    }
}
