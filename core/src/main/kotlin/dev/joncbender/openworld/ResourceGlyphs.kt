package dev.joncbender.openworld

/** The item glyphs for UI outside the globe (the Android tile panel), painted by the same art as the in-game icons. */
object ResourceGlyphs {
    /** [item]'s glyph as [size] x [size] straight-alpha RGBA8888 (0xRRGGBBAA), transparent around it. */
    fun rgba(item: Item, size: Int): IntArray =
        IntArray(size * size).also { ResourceIconArt.paint(item, size, it) }
}
