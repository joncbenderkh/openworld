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
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The bottom panel for the selected tile: biome, coordinates and up to four resources drawn with the in-game glyphs.
 * Docked and non-blocking - the globe underneath stays draggable - and updated in place on every tap.
 */
class TilePanel(
    context: Context,
    private val ink: InkTheme,
    onClose: () -> Unit,
    private val onFound: () -> Unit,
    private val onUpgrade: () -> Unit,
    private val onAllocate: (Int, Resource, Int) -> Unit,
    private val onAutomatic: (Int) -> Unit,
    private val onCraft: (Int, Product, Boolean) -> Unit,
    private val onBuild: (Int, Building) -> Unit,
    private val onDemolish: (Int, Building) -> Unit,
) {
    private val eyebrow = ink.label("Selected tile")
    private val biome = ink.title("")
    private val coordinates = ink.meta("")
    private val resourcesLabel = ink.label("")
    private val resourceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val settlementBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Apart from the settlement box: its numbers change on most ticks, and a rebuild there would drop a press on Grow.
    private val economyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Where the people work: built again only when the weights change, so a tick's new numbers cannot drop a press on a stepper.
    private val allocationBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // What the settlement makes: like the assignments, built again only when a recipe's switch or tier gate changes.
    private val craftingBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // What the settlement has built, or could: the same shape again.
    private val buildingsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
        addView(allocationBox)
        addView(craftingBox)
        addView(buildingsBox)
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
        showAllocation(info)
        showCrafting(info)
        showBuildings(info)
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
                settlementBox.addView(ink.meta(status.happiness.line()))
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

    private var allocationInfo: TileInfo? = null
    private var allocationTile = -1
    private var allocationOpen = false
    private var allocationKey = ""
    private var allocationScroll: ScrollView? = null
    private val shareText = HashMap<Resource, TextView>()

    /** Who works what: a button that opens a stepper per resource, each showing its people and what they bring in. */
    private fun showAllocation(info: TileInfo) {
        allocationInfo = info
        val shares = info.economy?.shares.orEmpty()
        if (info.faceIndex != allocationTile) {
            allocationTile = info.faceIndex
            allocationOpen = false
            allocationKey = ""
        }
        // The saved weights, not just those of resources that can be worked now: a weight on an exhausted resource still needs the reset.
        val key = "$allocationOpen:${info.settlement?.allocation.orEmpty()}:" + shares.joinToString { "${it.resource}${it.weight}${it.explicit}" }
        if (key != allocationKey) {
            allocationKey = key
            val scrolled = allocationScroll?.scrollY ?: 0
            allocationBox.removeAllViews()
            shareText.clear()
            allocationScroll = null
            val anySet = info.settlement?.allocation.orEmpty().isNotEmpty()
            if (shares.isNotEmpty() || anySet) buildAllocation(info.faceIndex, shares, anySet, scrolled)
        }
        shares.forEach { shareText[it.resource]?.text = shareLabel(it, info.economy!!) }
    }

    private fun buildAllocation(tile: Int, shares: List<WorkShare>, anySet: Boolean, scrolled: Int) {
        val context = view.context
        val below = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ink.dp(12f) }
        if (shares.isEmpty()) {
            // Every deposit is spent, but the saved weights are still there (they would come back with new land): they can be cleared.
            allocationBox.addView(ink.meta("Saved assignments have nothing to work now"), below)
            allocationBox.addView(ink.textButton("Split evenly again") { onAutomatic(tile) })
            return
        }
        allocationBox.addView(
            ink.secondaryButton(if (allocationOpen) "Hide assignments" else "Assign people") {
                allocationOpen = !allocationOpen
                // One list at a time: two together would not fit a short screen, and the panel does not scroll.
                if (allocationOpen) { craftingOpen = false; buildingsOpen = false }
                allocationInfo?.let(::refreshLists)
            },
            below,
        )
        if (!allocationOpen) return
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        shares.forEach { share ->
            val name = share.resource.displayName()
            val label = ink.bodyText("").apply { gravity = Gravity.CENTER_VERTICAL }
            shareText[share.resource] = label
            rows.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ink.dp(InkPalette.TOUCH_DP)
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        ink.roundButton("−", "Fewer people on $name") { if (share.weight > 0) onAllocate(tile, share.resource, share.weight - 1) }
                            .apply { isEnabled = share.weight > 0; alpha = if (isEnabled) 1f else DIMMED },
                    )
                    addView(
                        ink.roundButton("+", "More people on $name") { if (share.weight < Gathering.MAX_WEIGHT) onAllocate(tile, share.resource, share.weight + 1) }
                            .apply { isEnabled = share.weight < Gathering.MAX_WEIGHT; alpha = if (isEnabled) 1f else DIMMED },
                        LinearLayout.LayoutParams(ink.dp(InkPalette.TOUCH_DP), ink.dp(InkPalette.TOUCH_DP)).apply { marginStart = ink.dp(8f) },
                    )
                },
            )
        }
        val scroll = ScrollView(context).apply { addView(rows) }
        allocationScroll = scroll
        allocationBox.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight(shares.size)))
        scroll.post { scroll.scrollTo(0, scrolled) }
        if (anySet) {
            allocationBox.addView(ink.textButton("Split evenly again") { onAutomatic(tile) })
        } else {
            allocationBox.addView(ink.meta("Split evenly over the deposits"))
        }
    }

    /**
     * The height of a list of [count] rows. The panel does not grow past a few rows: more are scrolled, and the half row shows
     * there are. A short screen shows fewer: the list never takes more than a quarter of it. Larger type makes the rest of the
     * panel taller, so the quarter shrinks with the font scale (at 2x the panel alone fills three quarters of a tall phone).
     */
    private fun listHeight(count: Int): Int {
        val context = view.context
        val row = ink.dp(InkPalette.TOUCH_DP)
        val fontScale = maxOf(1f, context.resources.configuration.fontScale)
        val room = (context.resources.displayMetrics.heightPixels * MAX_LIST_SHARE / fontScale).toInt().coerceAtLeast(row)
        val shown = if (count > VISIBLE_ROWS) VISIBLE_ROWS + 0.5f else count.toFloat()
        return minOf((row * shown).toInt(), room)
    }

    private fun refreshLists(info: TileInfo) {
        showAllocation(info)
        showCrafting(info)
        showBuildings(info)
    }

    private var buildingsInfo: TileInfo? = null
    private var buildingsTile = -1
    private var buildingsOpen = false
    private var buildingsKey = ""
    private var buildingsScroll: ScrollView? = null
    private val buildingText = HashMap<Building, TextView>()
    private val buildButton = HashMap<Building, View>()

    // The building whose Demolish was pressed once: a second press takes it down, as nothing is refunded.
    private var demolishing: Building? = null

    /** What the settlement has built and could build: a button that opens a row per building, with Build or Demolish. */
    private fun showBuildings(info: TileInfo) {
        buildingsInfo = info
        if (info.faceIndex != buildingsTile) {
            buildingsTile = info.faceIndex
            buildingsOpen = false
            demolishing = null
            buildingsKey = ""
        }
        // Stock changes on most ticks, so whether a building is affordable is not in the key: the row's text follows it in place instead.
        val key = "$buildingsOpen:$demolishing:" + info.buildings.joinToString { "${it.building}${it.built}${it.idle}${if (it.now == BuildResult.NO_STOCK) BuildResult.OK else it.now}" }
        if (key != buildingsKey) {
            buildingsKey = key
            val scrolled = buildingsScroll?.scrollY ?: 0
            buildingsBox.removeAllViews()
            buildingText.clear()
            buildButton.clear()
            buildingsScroll = null
            if (info.buildings.isNotEmpty()) buildBuildings(info.faceIndex, info.buildings, scrolled)
        }
        info.buildings.forEach {
            buildingText[it.building]?.text = buildingLabel(it)
            buildButton[it.building]?.let { button -> styleBuild(button, it) }
        }
    }

    private fun buildBuildings(tile: Int, buildings: List<BuildingStatus>, scrolled: Int) {
        val context = view.context
        val below = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ink.dp(12f) }
        buildingsBox.addView(
            ink.secondaryButton(if (buildingsOpen) "Hide buildings" else "Buildings") {
                buildingsOpen = !buildingsOpen
                demolishing = null
                if (buildingsOpen) { allocationOpen = false; craftingOpen = false }
                buildingsInfo?.let(::refreshLists)
            },
            below,
        )
        if (!buildingsOpen) return
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        buildings.forEach { status ->
            val name = status.building.name.toDisplayName()
            val label = ink.bodyText("").apply { gravity = Gravity.CENTER_VERTICAL }
            buildingText[status.building] = label
            val button = when {
                status.built && demolishing == status.building -> ink.textButton("Sure?") {
                    demolishing = null
                    onDemolish(tile, status.building)
                }
                status.built -> ink.textButton("Demolish") {
                    demolishing = status.building
                    buildingsInfo?.let(::refreshLists)
                }
                else -> ink.textButton("Build") { onBuild(tile, status.building) }.apply {
                    contentDescription = "Build $name"
                    buildButton[status.building] = this
                    styleBuild(this, status)
                }
            }
            rows.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ink.dp(InkPalette.TOUCH_DP)
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ink.dp(InkPalette.TOUCH_DP)).apply { marginStart = ink.dp(8f) })
                },
            )
        }
        val scroll = ScrollView(context).apply { addView(rows) }
        buildingsScroll = scroll
        buildingsBox.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight(buildings.size)))
        scroll.post { scroll.scrollTo(0, scrolled) }
    }

    // Build is live only when it would work now; stock changes on most ticks, so this is set in place, not by a rebuild.
    private fun styleBuild(button: View, status: BuildingStatus) {
        button.isEnabled = status.now == BuildResult.OK
        button.alpha = if (button.isEnabled) 1f else DIMMED
    }

    private fun buildingLabel(status: BuildingStatus): String {
        val spec = Buildings.spec(status.building)
        val head = "${status.building.name.toDisplayName()} · ${Buildings.effect(status.building)}"
        val state = when {
            status.built && status.idle -> "idle: needs ${Buildings.upkeepText(status.building)}"
            status.built -> "built · upkeep ${Buildings.upkeepText(status.building)}"
            status.now == BuildResult.TIER -> "needs a ${spec.minTier.name.lowercase()}"
            status.now == BuildResult.FULL -> "no free slot"
            status.now == BuildResult.NO_SITE -> "needs ${spec.boosts.joinToString(" or ") { it.name.toDisplayName().lowercase() }} on its land"
            status.now == BuildResult.NO_STOCK -> "needs ${Buildings.itemsText(spec.cost)} · upkeep ${Buildings.upkeepText(status.building)}"
            else -> "costs ${Buildings.itemsText(spec.cost)} · upkeep ${Buildings.upkeepText(status.building)}"
        }
        return "$head · $state"
    }

    private var craftingInfo: TileInfo? = null
    private var craftingTile = -1
    private var craftingOpen = false
    private var craftingKey = ""
    private val craftText = HashMap<Product, TextView>()
    private var craftingScroll: ScrollView? = null

    /** What the settlement can make: a button that opens a row per recipe, each with its state and a switch. */
    private fun showCrafting(info: TileInfo) {
        craftingInfo = info
        if (info.faceIndex != craftingTile) {
            craftingTile = info.faceIndex
            craftingOpen = false
            craftingKey = ""
        }
        val key = "$craftingOpen:" + info.crafting.joinToString { "${it.recipe.output}${it.enabled}${it.locked}" }
        if (key != craftingKey) {
            craftingKey = key
            val scrolled = craftingScroll?.scrollY ?: 0
            craftingBox.removeAllViews()
            craftText.clear()
            craftingScroll = null
            if (info.crafting.isNotEmpty()) buildCrafting(info.faceIndex, info.crafting, scrolled)
        }
        info.crafting.forEach { craftText[it.recipe.output]?.text = craftLabel(it) }
    }

    private fun buildCrafting(tile: Int, recipes: List<CraftStatus>, scrolled: Int) {
        val context = view.context
        val below = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ink.dp(12f) }
        craftingBox.addView(
            ink.secondaryButton(if (craftingOpen) "Hide crafting" else "Crafting") {
                craftingOpen = !craftingOpen
                if (craftingOpen) { allocationOpen = false; buildingsOpen = false }
                craftingInfo?.let(::refreshLists)
            },
            below,
        )
        if (!craftingOpen) return
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recipes.forEach { status ->
            val name = status.recipe.output.displayName()
            val label = ink.bodyText("").apply { gravity = Gravity.CENTER_VERTICAL }
            craftText[status.recipe.output] = label
            val toggle = ink.switch(status.enabled && !status.locked) { on -> onCraft(tile, status.recipe.output, on) }.apply {
                contentDescription = "Make $name"
                isEnabled = !status.locked
                alpha = if (status.locked) DIMMED else 1f
            }
            rows.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ink.dp(InkPalette.TOUCH_DP)
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ink.dp(8f) })
                },
            )
        }
        val scroll = ScrollView(context).apply { addView(rows) }
        craftingScroll = scroll
        craftingBox.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight(recipes.size)))
        scroll.post { scroll.scrollTo(0, scrolled) }
        craftingBox.addView(ink.meta("Tools, clothing and jewelry count once ${HappinessModel.Tuning.STOCKED} are stocked"))
    }

    private fun craftLabel(status: CraftStatus): String {
        val recipe = status.recipe
        val needs = recipe.inputs.entries.joinToString(" + ") { (item, units) -> "$units ${item.displayName().lowercase()}" }
        val state = when {
            status.locked -> "needs a ${recipe.minTier.name.lowercase()}"
            status.idleBuilding -> "${recipe.requires.name.toDisplayName().lowercase()} idle: upkeep unpaid"
            status.unbuilt -> "needs a ${recipe.requires.name.toDisplayName().lowercase()}"
            !status.enabled -> "off"
            status.done -> "enough made"
            !status.ready -> "waiting for stock"
            status.perTick <= 0f -> "stopped by unrest"
            else -> "+" + String.format(Locale.ROOT, "%.1f", status.perTick) + " per tick"
        }
        return "${recipe.output.displayName()} · $needs · $state"
    }

    private fun shareLabel(share: WorkShare, economy: Economy): String {
        val name = share.resource.displayName()
        val people = share.people.roundToInt()
        val income = String.format(Locale.ROOT, "%.1f", economy.income[share.resource] ?: 0f)
        val who = when {
            share.full -> return "$name · store full"
            share.people <= 0f -> return "$name · nobody"
            people == 0 -> "under 1 person"
            people == 1 -> "1 person"
            else -> "$people people"
        }
        return "$name · $who · +$income"
    }

    private fun statusKey(status: SettlementStatus) =
        "${status.settlement.tier}:${status.settlement.population.toInt()}:${status.trend}:${status.upgrade}:${status.happiness.mood}:${status.happiness.grievances}:${status.needs.map { Triple(it.supported, it.met, it.byGoods) }}"

    private fun trendText(trend: Trend) = when (trend) {
        Trend.GROWING -> "growing"
        Trend.STEADY -> "steady"
        Trend.FULL -> "full"
        Trend.DECLINING -> "declining"
    }

    private fun needText(need: NeedStatus): String {
        val name = need.need.name.toDisplayName()
        return when {
            need.byGoods -> "$name: kept by clothing"
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
        const val VISIBLE_ROWS = 4
        const val MAX_LIST_SHARE = 0.25f
        const val DIMMED = 0.4f

        fun String.toDisplayName(): String =
            lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
