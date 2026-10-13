package dev.joncbender.openworld

import kotlin.math.roundToInt

/**
 * Colors, sizes and type scale of the Android chrome, which is printed onto the map: ivory medallions, ink
 * rules, a serif face. Pure Kotlin (colors are ARGB ints, sizes are dp or sp numbers) so the contrast and
 * touch-target rules are unit tested without a device; `InkTheme` in the android module turns them into views.
 *
 * The two inks are the pen colors of the tile art ([InkArt]), so the chrome and the map read as one drawing;
 * the surfaces are the mockup's ivory.
 */
object InkPalette {
    /** Pen ink of the tile art: text, rules and the borders of every control. */
    const val INK = 0xFF332114.toInt()

    /** Water ink of the tile art: the one accent (selected state, primary action). */
    const val WATER_INK = 0xFF384C61.toInt()

    const val SURFACE = 0xFFFAF9F5.toInt()
    const val BACKDROP = 0xFFF5F4ED.toInt()
    const val SURFACE_WARM = 0xFFE8E6DC.toInt()
    const val BORDER_SOFT = 0xFFE5E3D8.toInt()

    /** Secondary text, descriptions and metadata: the ink faded toward the surface, kept above 4.5:1 contrast. */
    val TEXT_2 = mix(INK, SURFACE, 0.10f)
    val TEXT_MUTED = mix(INK, SURFACE, 0.18f)
    val TEXT_META = mix(INK, SURFACE, 0.30f)

    const val ACCENT = WATER_INK
    const val ACCENT_ON = SURFACE
    val ACCENT_ACTIVE = mix(WATER_INK, INK, 0.35f)

    const val DANGER = 0xFF8A3A30.toInt()

    /** Dims the map behind a sheet or dialog: the ink at 48%. */
    val SCRIM = (0x7A shl 24) or (INK and 0xFFFFFF)

    /** Smallest size of anything tappable. */
    const val TOUCH_DP = 48f

    /** The track of an on/off switch; the view around it is [TOUCH_DP] tall. */
    const val SWITCH_WIDTH_DP = 52f
    const val SWITCH_HEIGHT_DP = 32f

    /** The round buttons printed over the map. */
    const val MAP_BUTTON_DP = 52f

    /** A wax-seal compass point. */
    const val SEAL_DP = 34f

    /** Gap between the chrome and the screen edge. */
    const val EDGE_DP = 12f

    /** Width of the ink edge around controls. */
    const val LINE_DP = 1.5f

    const val RADIUS_SM_DP = 4f
    const val RADIUS_MD_DP = 8f
    const val RADIUS_LG_DP = 12f
    const val RADIUS_SHEET_DP = 16f

    const val TEXT_XS_SP = 11f
    const val TEXT_SM_SP = 12f
    const val TEXT_BASE_SP = 14f
    const val TEXT_MD_SP = 15f
    const val TEXT_LG_SP = 17f
    const val TEXT_XL_SP = 22f

    /** Moves [from] toward [to] by [amount] (0..1) in sRGB; the result is opaque. */
    internal fun mix(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 255
            val b = (to shr shift) and 255
            return (a + (b - a) * amount).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** WCAG contrast ratio of two opaque colors, 1 (identical) to 21 (black on white). */
    internal fun contrastRatio(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(shift: Int): Double {
                val c = ((color shr shift) and 255) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }
}
