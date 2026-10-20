package dev.joncbender.openworld

import java.util.Locale

/** One item of the shared stockpile: how many are held, how many fit, and what gathering brings in each tick (crafted goods have none). */
data class StockLine(val item: Item, val count: Int, val capacity: Int, val income: Float) {
    fun incomeText(): String = if (income > 0f) "+" + String.format(Locale.ROOT, "%.1f", income) + " per tick" else ""
}

/** The stockpile as a list, for the stockpile screen and the desktop overlay. */
object Stockpile {
    /** Every item held or gathered now, the most plentiful first (ties in item order). */
    fun lines(held: Map<Item, Int>, income: Map<Resource, Float>, capacity: Int): List<StockLine> =
        (held.keys + income.keys.filter { (income[it] ?: 0f) > 0f })
            .sortedWith(compareByDescending<Item> { held[it] ?: 0 }.thenBy { Items.slot(it) })
            .map { StockLine(it, held[it] ?: 0, capacity, (it as? Resource)?.let(income::get) ?: 0f) }

    /** One text line per item, "Grain 12 of 50  +0.5 per tick". */
    fun text(lines: List<StockLine>): List<String> =
        lines.map { "${it.item.displayName()} ${it.count} of ${it.capacity}" + if (it.income > 0f) "  " + it.incomeText() else "" }
}
