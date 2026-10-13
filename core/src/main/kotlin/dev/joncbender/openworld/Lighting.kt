package dev.joncbender.openworld

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The globe's one light: fixed in view space (upper left, toward the viewer), so it does not turn with
 * the globe. On a unit sphere a position is its own normal, so the shader needs no normal attribute.
 * [shade] mirrors the vertex shader in `GlobeScreen`, which builds its source from these constants.
 */
object Lighting {
    const val AMBIENT = 0.62f
    const val DIFFUSE = 1f - AMBIENT

    // Unit vector toward the light, in view space (x right, y up, z toward the viewer).
    const val LIGHT_X = -0.45f
    const val LIGHT_Y = 0.45f
    val LIGHT_Z: Float = sqrt(1f - LIGHT_X * LIGHT_X - LIGHT_Y * LIGHT_Y)

    /** Brightness multiplier (AMBIENT..1) for a surface with unit normal ([nx], [ny], [nz]) facing a light toward ([lx], [ly], [lz]). */
    fun shade(nx: Float, ny: Float, nz: Float, lx: Float, ly: Float, lz: Float): Float =
        AMBIENT + DIFFUSE * max(nx * lx + ny * ly + nz * lz, 0f)
}
