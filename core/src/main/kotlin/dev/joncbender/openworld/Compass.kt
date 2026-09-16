package dev.joncbender.openworld

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import kotlin.math.sqrt

/**
 * N/S/E/W labels ringed around the globe at a fixed screen position and
 * radius - never anchored to the pole's actual 3D position on the sphere's
 * surface, which can rotate to the far side (hidden from the camera) or,
 * once zoomed in past the globe's edge, off-screen entirely. Instead, the
 * ring's rotation is derived algebraically: the north pole's local (0,1,0)
 * point, rotated by the same quaternion as the globe, projected onto the
 * screen plane (its depth/Z is discarded) gives a well-defined on-screen
 * "which way is north" angle for any twist or roll - and since the ring's
 * radius is a fixed fraction of the viewport rather than tied to the globe's
 * current zoom, the labels always stay on screen.
 *
 * The one case with no well-defined angle is looking straight down the polar
 * axis (the projected point has near-zero length) - rather than let the ring
 * spin erratically there, the last stable angle is simply held.
 */
class Compass {

    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val font = BitmapFont().apply { data.setScale(1.4f) }
    private val camera = OrthographicCamera()
    private val layout = GlyphLayout()

    private val rotatedNorth = Vector3()
    private var azimuthDeg = 0f
    private var radius = 0f

    fun resize(width: Int, height: Int) {
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        radius = minOf(width, height) * RADIUS_FRACTION
    }

    fun render(rotation: Quaternion) {
        azimuthDeg = nextAzimuthDeg(rotation, azimuthDeg, rotatedNorth)

        camera.update()
        val cx = camera.viewportWidth / 2f
        val cy = camera.viewportHeight / 2f

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = BADGE_COLOR
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            shapes.circle(x, y, BADGE_RADIUS, 20)
        }
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            font.color = label.color
            layout.setText(font, label.text)
            font.draw(batch, layout, x - layout.width / 2f, y + layout.height / 2f)
        }
        batch.end()

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
    }

    private fun badgePosition(offsetDeg: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val angleRad = (azimuthDeg + offsetDeg) * MathUtils.degreesToRadians
        return (cx + MathUtils.sin(angleRad) * radius) to (cy + MathUtils.cos(angleRad) * radius)
    }

    fun dispose() {
        batch.dispose()
        shapes.dispose()
        font.dispose()
    }

    private data class Label(val text: String, val offsetDeg: Float, val color: Color)

    companion object {
        private val NORTH_LOCAL = Vector3(0f, 1f, 0f)

        /**
         * The on-screen compass bearing (0 = up/north, 90 = right/east,
         * clockwise) implied by rotating the sphere's local north pole by
         * [rotation] and discarding its depth. Pure and GL-independent, so
         * it's tested directly rather than through the rendering pipeline.
         * Falls back to [previousAzimuthDeg] when the pole points too nearly
         * straight at or away from the camera for the angle to be
         * meaningful, instead of letting it swing erratically. [scratch] is
         * a caller-owned Vector3 reused to avoid allocating one per frame.
         */
        fun nextAzimuthDeg(rotation: Quaternion, previousAzimuthDeg: Float, scratch: Vector3 = Vector3()): Float {
            scratch.set(NORTH_LOCAL).mul(rotation)
            val projectedLength = sqrt(scratch.x * scratch.x + scratch.y * scratch.y)
            return if (projectedLength > MIN_PROJECTED_LENGTH) {
                MathUtils.atan2(scratch.x, scratch.y) * MathUtils.radiansToDegrees
            } else {
                previousAzimuthDeg
            }
        }

        // A fixed fraction of the viewport, not the globe's current apparent
        // radius - the globe can be zoomed in well past the screen's edge,
        // and the ring still needs to stay fully visible.
        private const val RADIUS_FRACTION = 0.42f
        private const val BADGE_RADIUS = 26f
        private const val MIN_PROJECTED_LENGTH = 0.05f
        private val BADGE_COLOR = Color(0f, 0f, 0f, 0.45f)
        private val NORTH_COLOR = Color(0.92f, 0.66f, 0.30f, 1f)
        private val OTHER_COLOR = Color(1f, 1f, 1f, 0.9f)

        private val LABELS = listOf(
            Label("N", 0f, NORTH_COLOR),
            Label("E", 90f, OTHER_COLOR),
            Label("S", 180f, OTHER_COLOR),
            Label("W", 270f, OTHER_COLOR),
        )
    }
}
