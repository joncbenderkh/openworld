package dev.joncbender.openworld

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Everything the settlements hold, as an inked bottom sheet: a glyph, the name, how much of the room is used and what
 * gathering brings in each tick. The map stays live behind it, so the numbers follow the game while it is open.
 */
class StockpileSheet(
    private val context: Context,
    private val ink: InkTheme,
    private val game: OpenWorldGame,
) {
    private val glyphPx = ink.dp(GLYPH_DP)
    private val glyphs = HashMap<Item, Bitmap>()
    private var dialog: Dialog? = null
    private var rows: LinearLayout? = null
    private var lines: List<StockLine> = emptyList()

    fun show() {
        if (dialog != null) return
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        this.rows = rows
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var sheet: Dialog
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ink.title("Stockpile"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ink.closeButton("Close stockpile") { sheet.dismiss() })
            },
        )
        content.addView(ink.rule(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ink.dp(1f)).apply { topMargin = ink.dp(8f) })
        content.addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight()))
        sheet = ink.sheet(content)
        dialog = sheet
        sheet.setOnDismissListener {
            dialog = null
            this.rows = null
            lines = emptyList()
            game.globeScreen.watchStockpile(false)
        }
        render()
        // The world is not held: the numbers move while it is open, and the globe reports them each tick.
        game.globeScreen.watchStockpile(true)
        sheet.show()
    }

    /** The newest list, from the game (any thread); ignored when the sheet is closed. */
    fun update(newLines: List<StockLine>) {
        if (rows == null) return
        lines = newLines
        render()
    }

    private fun render() {
        val rows = rows ?: return
        rows.removeAllViews()
        if (lines.isEmpty()) {
            rows.addView(ink.bodyText("Nothing stored yet").apply { minHeight = ink.dp(InkPalette.TOUCH_DP); gravity = Gravity.CENTER_VERTICAL })
            return
        }
        lines.forEach { rows.addView(row(it)) }
    }

    private fun row(line: StockLine): LinearLayout {
        val disc = FrameLayout(context).apply {
            background = ink.disc(InkPalette.BACKDROP, InkPalette.TEXT_2)
            addView(ImageView(context).apply { setImageBitmap(glyph(line.item)) }, FrameLayout.LayoutParams(glyphPx, glyphPx, Gravity.CENTER))
        }
        val numbers = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(ink.bodyText("${line.count} of ${line.capacity}").apply { gravity = Gravity.END })
            if (line.income > 0f) addView(ink.meta(line.incomeText()).apply { gravity = Gravity.END })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ink.dp(InkPalette.TOUCH_DP)
            contentDescription = Stockpile.text(listOf(line)).single()
            addView(disc, LinearLayout.LayoutParams(ink.dp(DISC_DP), ink.dp(DISC_DP)))
            addView(
                ink.bodyText(line.item.displayName()).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ink.dp(12f) },
            )
            addView(numbers)
        }
    }

    private fun glyph(item: Item): Bitmap = glyphs.getOrPut(item) {
        val rgba = ResourceGlyphs.rgba(item, glyphPx)
        val argb = IntArray(rgba.size) { Integer.rotateRight(rgba[it], 8) }
        Bitmap.createBitmap(argb, glyphPx, glyphPx, Bitmap.Config.ARGB_8888)
    }

    // Like the panel's lists: a few rows, the half row showing there are more, never over half of a short screen.
    private fun listHeight(): Int {
        val row = ink.dp(InkPalette.TOUCH_DP)
        val fontScale = maxOf(1f, context.resources.configuration.fontScale)
        val room = (context.resources.displayMetrics.heightPixels * MAX_LIST_SHARE / fontScale).toInt().coerceAtLeast(row)
        return minOf((row * (VISIBLE_ROWS + 0.5f)).toInt(), room)
    }

    private companion object {
        const val DISC_DP = 40f
        const val GLYPH_DP = 32f
        const val VISIBLE_ROWS = 6
        const val MAX_LIST_SHARE = 0.5f
    }
}
