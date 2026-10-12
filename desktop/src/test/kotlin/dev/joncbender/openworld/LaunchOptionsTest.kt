package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LaunchOptionsTest {

    private val noProperties: (String) -> String? = { null }

    @Test
    fun `nothing set keeps the phone's value`() {
        assertEquals(GameOptions.DEFAULT_MAX_NEAR_PATCHES, LaunchOptions.parse(emptyArray(), noProperties).maxNearPatches)
    }

    @Test
    fun `a flag sets the cap`() {
        assertEquals(1024, LaunchOptions.parse(arrayOf("--max-near-patches=1024"), noProperties).maxNearPatches)
    }

    @Test
    fun `a system property sets the cap`() {
        val properties = { key: String -> if (key == LaunchOptions.MAX_NEAR_PATCHES_PROPERTY) "512" else null }
        assertEquals(512, LaunchOptions.parse(emptyArray(), properties).maxNearPatches)
    }

    @Test
    fun `the flag wins over the property, and the last flag wins`() {
        val properties = { _: String -> "512" }
        assertEquals(64, LaunchOptions.parse(arrayOf("--max-near-patches=32", "--max-near-patches=64"), properties).maxNearPatches)
    }

    @Test
    fun `junk is rejected rather than ignored`() {
        assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--max-near-patches=lots"), noProperties) }
        assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--max-near-patches=0"), noProperties) }
        assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--bogus"), noProperties) }
        assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(emptyArray()) { "-3" } }
    }
}
