package dev.joncbender.openworld

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Where a tile's center is on the globe: latitude from the pole axis (+Y), longitude measured from the +Z axis
 * (the side facing the camera at the start), east positive - so a point to the right of the view is east, as the
 * compass has it.
 */
object TileCoordinates {
    fun latitudeDeg(y: Float): Float = Math.toDegrees(asin(y.coerceIn(-1f, 1f)).toDouble()).toFloat()

    fun longitudeDeg(x: Float, z: Float): Float = Math.toDegrees(atan2(x, z).toDouble()).toFloat()

    /** "46.52° N · 9.84° E". */
    fun format(latitudeDeg: Float, longitudeDeg: Float): String {
        val lat = String.format(java.util.Locale.ROOT, "%.2f° %s", abs(latitudeDeg), if (latitudeDeg < 0f) "S" else "N")
        val lon = String.format(java.util.Locale.ROOT, "%.2f° %s", abs(longitudeDeg), if (longitudeDeg < 0f) "W" else "E")
        return "$lat · $lon"
    }
}
