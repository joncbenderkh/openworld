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
    private val font = BitmapFont()
    private val camera = OrthographicCamera()
    private val layout = GlyphLayout()

    private val rotatedNorth = Vector3()
    private var azimuthDeg = 0f
    private var radius = 0f
    private var sealRadius = 0f
    private var edge = 0f
    private val color = Color()

    fun resize(width: Int, height: Int) {
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        radius = minOf(width, height) * RADIUS_FRACTION
        // A desktop reports a density under 1; the seals should not shrink below their nominal size there.
        val density = Gdx.graphics.density.coerceAtLeast(1f)
        edge = InkPalette.LINE_DP * density
        sealRadius = InkPalette.SEAL_DP * density / 2f
        font.data.setScale(InkPalette.TEXT_MD_SP * density / FONT_NATIVE_PX)
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
        // The seals sit on the dark backdrop, so an ivory edge keeps ink-colored wax from vanishing into it.
        shapes.color = argb(InkPalette.SURFACE)
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            shapes.circle(x, y, sealRadius * (1f + LOBE_FRACTION) + edge, SEAL_SEGMENTS)
        }
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            shapes.color = argb(label.wax)
            shapes.circle(x, y, sealRadius, SEAL_SEGMENTS)
            // Scalloped edge: small lobes of wax around the rim.
            for (i in 0 until LOBES) {
                val a = i * MathUtils.PI2 / LOBES
                shapes.circle(x + MathUtils.cos(a) * sealRadius, y + MathUtils.sin(a) * sealRadius, sealRadius * LOBE_FRACTION, 8)
            }
        }
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = argb(InkPalette.SURFACE, RIM_ALPHA)
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            shapes.circle(x, y, sealRadius * RIM_FRACTION, SEAL_SEGMENTS)
        }
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        for (label in LABELS) {
            val (x, y) = badgePosition(label.offsetDeg, cx, cy)
            font.color = argb(label.ink)
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

    private fun argb(value: Int, alpha: Float = 1f): Color {
        Color.argb8888ToColor(color, value)
        color.a = alpha
        return color
    }

    private data class Label(val text: String, val offsetDeg: Float, val wax: Int, val ink: Int)

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
        private const val MIN_PROJECTED_LENGTH = 0.05f

        // Proportions of the 34-unit wax seal in the mockup: 15 wide wax disc, a rim at 11.5, lobes on the edge.
        private const val RIM_FRACTION = 11.5f / 17f
        private const val RIM_ALPHA = 0.55f
        private const val LOBES = 16
        private const val LOBE_FRACTION = 0.1f
        private const val SEAL_SEGMENTS = 28
        private const val FONT_NATIVE_PX = 15f

        private val LABELS = listOf(
            Label("N", 0f, InkPalette.ACCENT, InkPalette.ACCENT_ON),
            Label("E", 90f, InkPalette.INK, InkPalette.SURFACE),
            Label("S", 180f, InkPalette.INK, InkPalette.SURFACE),
            Label("W", 270f, InkPalette.INK, InkPalette.SURFACE),
        )
    }
}
