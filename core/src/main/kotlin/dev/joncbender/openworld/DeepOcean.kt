package dev.joncbender.openworld

/**
 * Finds the open ocean: ocean tiles with no land nearby. About 60-70% of the world is ocean, and
 * nearly all of it is far from any coast, where every tile looks like every other. Those tiles
 * don't need a mesh each - a single coarse ocean underlay can show through where they would be -
 * so [tilesToDraw] names the ones that do: everything except deep ocean.
 */
object DeepOcean {

    /**
     * For every face, whether it needs drawing individually: false only for an OCEAN face whose
     * neighbors out to [rings] tiles are all OCEAN as well. The margin keeps the coastline, and the
     * shallows beside it, at full detail.
     *
     * Erodes the ocean one ring per pass with two boolean arrays, so it needs ~2 bytes per tile
     * rather than a distance map.
     */
    fun tilesToDraw(world: SphereWorld, rings: Int): BooleanArray {
        require(rings >= 0) { "rings must not be negative" }
        val sphere = world.sphere
        val count = sphere.faceCount
        var deep = BooleanArray(count)
        Parallel.forEachIndex(count) { deep[it] = world[it] == Biome.OCEAN }
        repeat(rings) {
            val current = deep
            val next = BooleanArray(count)
            Parallel.forEachIndex(count) { i -> next[i] = current[i] && !sphere.anyNeighbor(i) { n -> !current[n] } }
            deep = next
        }
        // Reuse the array: draw everything that is not deep.
        Parallel.forEachIndex(count) { deep[it] = !deep[it] }
        return deep
    }
}
