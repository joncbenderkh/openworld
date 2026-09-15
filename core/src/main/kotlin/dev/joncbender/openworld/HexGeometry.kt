package dev.joncbender.openworld

import com.badlogic.gdx.math.Vector2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flat-top hexagons in an "odd-q" vertical offset layout: columns wrap
 * around the world's east-west axis, rows do not (rows are the poles).
 */
class HexGeometry(val size: Float) {

    val width: Float = size * 2f
    val horizontalSpacing: Float = size * 1.5f
    val verticalSpacing: Float = size * sqrt(3f)

    fun center(col: Int, row: Int, out: Vector2 = Vector2()): Vector2 {
        val x = horizontalSpacing * col
        val y = verticalSpacing * (row + 0.5f * (col and 1))
        return out.set(x, y)
    }

    fun corners(centerX: Float, centerY: Float): FloatArray {
        val pts = FloatArray(12)
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i).toDouble())
            pts[i * 2] = centerX + size * cos(angle).toFloat()
            pts[i * 2 + 1] = centerY + size * sin(angle).toFloat()
        }
        return pts
    }

    companion object {
        /** Neighbor (col, row) deltas for flat-top odd-q offset coordinates. */
        val EVEN_COL_NEIGHBORS = arrayOf(
            1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, 0 to 1
        )
        val ODD_COL_NEIGHBORS = arrayOf(
            1 to 1, 1 to 0, 0 to -1, -1 to 0, -1 to 1, 0 to 1
        )
    }
}

/** A hex address on a world that wraps in the column direction only. */
data class HexCoord(val col: Int, val row: Int) {
    fun wrapped(width: Int): HexCoord = HexCoord(((col % width) + width) % width, row)

    fun neighbors(width: Int, height: Int): List<HexCoord> {
        val deltas = if (col and 1 == 0) HexGeometry.EVEN_COL_NEIGHBORS else HexGeometry.ODD_COL_NEIGHBORS
        return deltas.mapNotNull { (dc, dr) ->
            val r = row + dr
            if (r < 0 || r >= height) null else HexCoord(col + dc, r).wrapped(width)
        }
    }
}
