package dev.joncbender.openworld

/**
 * How the camera's distance from the globe's center turns into behavior. The globe has radius 1, so
 * what matters near the surface is the camera's *height* above it, `distance - 1`: zooming scales
 * that, not the distance, or the last steps before the surface would be huge jumps.
 */
internal object CameraRig {
    /** Closest the camera gets: 4% of the radius above the surface, where a full-resolution tile is tens of pixels wide. */
    const val MIN_DISTANCE = 1.04f
    const val MAX_DISTANCE = 6f

    /** Below this distance the graticule is not drawn. */
    const val GRATICULE_HIDDEN_BELOW = 1.3f

    /** The distance after scaling the camera's height above the surface by [factor], within the limits. */
    fun zoomed(distance: Float, factor: Float): Float =
        (1f + (distance - 1f) * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)

    /**
     * Near clip plane: half the height, so the surface is never clipped and the depth buffer's
     * precision, which goes with the near plane, is spent where the surface is.
     */
    fun nearPlane(distance: Float): Float = ((distance - 1f) * 0.5f).coerceAtLeast(0.005f)

    /** Far clip plane: just past the globe's far side. */
    fun farPlane(distance: Float): Float = distance + 1.1f

    /** Fraction of the usual rotation per dragged pixel: the surface moves slower under the finger the closer the camera is. */
    fun dragScale(distance: Float): Float = ((distance - 1f) / DRAG_FULL_SPEED_HEIGHT).coerceIn(0f, 1f)

    private const val DRAG_FULL_SPEED_HEIGHT = 2f
}
