package dev.joncbender.openworld

import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3

object Orientation {
    /**
     * [rotation] turned the shortest way so the point (x, y, z) of the globe faces the camera (which looks down from +Z), keeping
     * as much of the player's twist as that allows. [rotation] itself is not changed.
     */
    fun facing(rotation: Quaternion, x: Float, y: Float, z: Float): Quaternion {
        val view = rotation.transform(Vector3(x, y, z)).nor()
        val axis = view.cpy().crs(Vector3.Z)
        val sin = axis.len()
        // Exactly behind, the cross product has no direction: any half turn will do.
        val turn = if (sin < 1e-6f) {
            if (view.z > 0f) Quaternion() else Quaternion(Vector3.Y, 180f)
        } else {
            Quaternion(axis.scl(1f / sin), Math.toDegrees(Math.atan2(sin.toDouble(), view.z.toDouble())).toFloat())
        }
        return Quaternion(turn).mul(rotation)
    }
}
