package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitRankTest {

    @Test
    fun `rank counts the members below an index, across word boundaries`() {
        val members = (0 until 300).filter { it % 7 == 0 || it == 63 || it == 64 || it == 299 }.toSet()
        val set = BitRank(300) { it in members }
        var below = 0
        for (i in 0 until 300) {
            assertEquals(i in members, i in set, "membership of $i")
            assertEquals(below, set.rank(i), "rank of $i")
            if (i in members) below++
        }
        assertEquals(members.size, set.count)
    }

    @Test
    fun `empty and full sets`() {
        assertEquals(0, BitRank(130) { false }.count)
        val full = BitRank(130) { true }
        assertEquals(130, full.count)
        assertEquals(129, full.rank(129))
        assertTrue(0 in full)
        assertFalse(BitRank(0) { true }.count > 0)
    }
}
