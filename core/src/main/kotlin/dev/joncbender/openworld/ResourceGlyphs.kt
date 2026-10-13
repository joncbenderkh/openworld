package dev.joncbender.openworld

/** The resource glyphs for UI outside the globe (the Android tile panel), painted by the same art as the in-game icons. */
object ResourceGlyphs {
    /** [resource]'s glyph as [size] x [size] straight-alpha RGBA8888 (0xRRGGBBAA), transparent around it. */
    fun rgba(resource: Resource, size: Int): IntArray =
        IntArray(size * size).also { ResourceIconArt.paint(resource, size, it) }
}
