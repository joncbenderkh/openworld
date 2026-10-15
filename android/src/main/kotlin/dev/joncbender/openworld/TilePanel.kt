package dev.joncbender.openworld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bottom panel for the selected tile: biome, coordinates and up to four resources drawn with the in-game glyphs.
 * Docked and non-blocking - the globe underneath stays draggable - and updated in place on every tap.
 */
class TilePanel(context: Context, private val ink: InkTheme, onClose: () -> Unit, private val onFound: () -> Unit, private val onUpgrade: () -> Unit) {
    private val eyebrow = ink.label("Selected tile")
    private val biome = ink.title("")
    private val coordinates = ink.meta("")
    private val resourcesLabel = ink.label("")
    private val resourceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val settlementBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Apart from the settlement box: its numbers change on most ticks, and a rebuild there would drop a press on Grow.
    private val economyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val glyphPx = ink.dp(GLYPH_DP)
    private val glyphs = HashMap<Resource, Bitmap>()

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ink.sheetBackground()
        elevation = ink.dp(8f).toFloat()
        visibility = View.GONE
        val side = ink.dp(20f)
        setPadding(side, ink.dp(16f), side, ink.dp(24f))

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(eyebrow)
            addView(biome)
            addView(coordinates)
        }
        val close = ink.closeButton("Close tile details", onClose)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            },
        )
        addView(
            resourcesLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ink.dp(16f); bottomMargin = ink.dp(4f) },
        )
        addView(resourceList)
        addView(
            settlementBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ink.dp(16f) },
        )
        addView(economyBox)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
    }

    fun show(info: TileInfo) {
        eyebrow.text = if (info.isOpenOcean) "OPEN OCEAN" else "SELECTED TILE"
        biome.text = info.biome.displayName()
        coordinates.text = TileCoordinates.format(info.latitudeDeg, info.longitudeDeg)
        resourcesLabel.text = "RESOURCES · ${info.resources.size}"
        resourceList.removeAllViews()
        if (info.resources.isEmpty()) {
            resourceList.addView(
                ink.bodyText("None here").apply { minHeight = ink.dp(InkPalette.TOUCH_DP); gravity = Gravity.CENTER_VERTICAL },
            )
        }
        info.resources.chunked(COLUMNS).forEach { pair ->
            resourceList.addView(
                LinearLayout(view.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEach { addView(entry(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                    // An odd last row keeps its single entry in the left column.
                    repeat(COLUMNS - pair.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
                },
            )
        }
        showSettlement(info)
        showEconomy(info.economy)
        view.visibility = View.VISIBLE
    }

    // What the settlement box shows now; a tick that changes nothing visible leaves its views (and a press on them) alone.
    private var settlementKey = ""

    /** The settlement on the tile - people, needs, growth - or the means to found one (or the reason it cannot be). */
    private fun showSettlement(info: TileInfo) {
        val status = info.status
        val key = if (status == null) "found:${info.canFound}" else statusKey(status)
        if (key == settlementKey) return
        settlementKey = key
        settlementBox.removeAllViews()
        when {
            status != null -> {
                val settlement = status.settlement
                settlementBox.addView(ink.label("SETTLEMENT"))
                settlementBox.addView(ink.title(settlement.tier.name.toDisplayName()))
                settlementBox.addView(ink.bodyText("People  ${settlement.population.toInt()} of ${settlement.tier.populationCap}  ·  ${trendText(status.trend)}"))
                status.needs.forEach { settlementBox.addView(ink.meta(needText(it))) }
                settlementBox.addView(ink.meta("Claims tiles within ${settlement.tier.claimRadius} of this one"))
                when (status.upgrade) {
                    UpgradeResult.OK -> settlementBox.addView(
                        ink.primaryButton("Grow into a ${settlement.tier.next!!.name.lowercase()}", onUpgrade),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ink.dp(12f) },
                    )
                    UpgradeResult.TOO_SMALL -> settlementBox.addView(ink.meta("Grows at ${(settlement.tier.populationCap * PopulationModel.UPGRADE_SHARE).toInt()} people"))
                    UpgradeResult.BLOCKED -> settlementBox.addView(ink.meta("Another settlement's land is in the way"))
                    UpgradeResult.MAX_TIER -> Unit
                }
            }
            info.canFound == FoundResult.OK -> settlementBox.addView(ink.primaryButton("Found a homestead", onFound))
            info.canFound == FoundResult.CLAIMED -> settlementBox.addView(ink.meta("Next to a settlement's land"))
            else -> Unit
        }
    }

    private var economyKey = ""

    /** What the settlement gathers each tick and keeps in store. */
    private fun showEconomy(economy: Economy?) {
        val lines = economy?.let { listOfNotNull(it.incomeLine(), it.stockLine(), it.lockedLine()) }.orEmpty()
        val key = lines.joinToString("\n")
        if (key == economyKey) return
        economyKey = key
        economyBox.removeAllViews()
        lines.forEach { economyBox.addView(ink.meta(it)) }
    }

    private fun statusKey(status: SettlementStatus) =
        "${status.settlement.tier}:${status.settlement.population.toInt()}:${status.trend}:${status.upgrade}:${status.needs.map { it.supported to it.met }}"

    private fun trendText(trend: Trend) = when (trend) {
        Trend.GROWING -> "growing"
        Trend.STEADY -> "steady"
        Trend.FULL -> "full"
        Trend.DECLINING -> "declining"
    }

    private fun needText(need: NeedStatus): String {
        val name = need.need.name.toDisplayName()
        return when {
            need.supported == 0 -> "$name: none on its land"
            need.met -> "$name: enough for ${need.supported}"
            else -> "$name: only enough for ${need.supported}"
        }
    }

    fun hide() {
        view.visibility = View.GONE
    }

    /** One resource: its glyph on a disc, then its name; at least 48dp tall. */
    private fun entry(resource: Resource): View {
        val context = view.context
        val disc = FrameLayout(context).apply {
            background = ink.disc(InkPalette.BACKDROP, InkPalette.TEXT_2)
            addView(
                ImageView(context).apply { setImageBitmap(glyph(resource)) },
                FrameLayout.LayoutParams(glyphPx, glyphPx, Gravity.CENTER),
            )
        }
        val name = ink.text(resource.displayName(), InkPalette.TEXT_MD_SP, face = Typeface.create(ink.bodyFace, Typeface.BOLD))
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ink.dp(InkPalette.TOUCH_DP)
            addView(disc, LinearLayout.LayoutParams(ink.dp(DISC_DP), ink.dp(DISC_DP)))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ink.dp(12f) })
        }
    }

    private fun glyph(resource: Resource): Bitmap = glyphs.getOrPut(resource) {
        val rgba = ResourceGlyphs.rgba(resource, glyphPx)
        val argb = IntArray(rgba.size) { Integer.rotateRight(rgba[it], 8) }
        Bitmap.createBitmap(argb, glyphPx, glyphPx, Bitmap.Config.ARGB_8888)
    }

    private fun Biome.displayName() = name.toDisplayName()
    private fun Resource.displayName() = name.toDisplayName()

    private companion object {
        const val COLUMNS = 2
        const val DISC_DP = 40f
        const val GLYPH_DP = 32f

        fun String.toDisplayName(): String =
            lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
