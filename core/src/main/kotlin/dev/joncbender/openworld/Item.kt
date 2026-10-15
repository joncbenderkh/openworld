package dev.joncbender.openworld

/** Anything the stockpile can hold: a raw [Resource] gathered from a tile, or a [Product] crafted from other items. */
interface Item {
    val name: String
}

/** Goods made from other items (see [Recipes]); never found on a tile, so they take no part in world generation or its caches. */
enum class Product : Item {
    METAL, TOOLS, FLOUR, BREAD, PRESERVED_FISH,
    CLOTHING, BUILDING_MATERIALS, JEWELRY, FUEL, EXPLOSIVES,
}

fun Item.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase).replace('_', ' ')

/** Every [Item] in one dense numbering: the raw resources by ordinal, then the products. */
object Items {
    val all: List<Item> = Resource.entries + Product.entries

    fun slot(item: Item): Int = when (item) {
        is Resource -> item.ordinal
        is Product -> Resource.entries.size + item.ordinal
        else -> error("unknown item $item")
    }
}
