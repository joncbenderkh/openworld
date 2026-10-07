package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearPatchStreamerTest {

    private val built = ArrayList<Int>()
    private val released = ArrayList<Int>()
    private var clock = 0L
    private val buildCost = 1_000_000L // each build "takes" 1 ms on the fake clock

    private fun streamer(patches: Int = 10, max: Int = 4) = NearPatchStreamer(
        patchCount = patches,
        maxResident = max,
        buildPatch = { built.add(it); clock += buildCost },
        releasePatch = { released.add(it) },
        nanoTime = { clock },
    )

    private fun priorities(vararg pairs: Pair<Int, Float>) = FloatArray(10).also { for ((p, v) in pairs) it[p] = v }

    @Test
    fun `builds the patch closest to the view first`() {
        val s = streamer()
        s.update(1, intArrayOf(2, 5, 7), 3, priorities(2 to 0.1f, 5 to 0.9f, 7 to 0.5f), budgetNanos = buildCost) // room for exactly one
        assertEquals(listOf(5), built)
    }

    @Test
    fun `builds as many as fit in the budget, best first`() {
        val s = streamer(max = 10)
        val n = s.update(1, intArrayOf(2, 5, 7, 8), 4, priorities(2 to 0.1f, 5 to 0.9f, 7 to 0.5f, 8 to 0.3f), budgetNanos = 2 * buildCost + 1)
        assertEquals(3, n)
        assertEquals(listOf(5, 7, 8), built)
    }

    @Test
    fun `always builds at least one even with no budget`() {
        val s = streamer()
        assertEquals(1, s.update(1, intArrayOf(3, 4), 2, priorities(3 to 0.2f, 4 to 0.1f), budgetNanos = 0))
        assertEquals(listOf(3), built)
    }

    @Test
    fun `already resident patches are not rebuilt and nothing is built when all are ready`() {
        val s = streamer()
        s.update(1, intArrayOf(1, 2), 2, priorities(1 to 1f, 2 to 0.5f), budgetNanos = 10 * buildCost)
        built.clear()
        assertEquals(0, s.update(2, intArrayOf(1, 2), 2, priorities(1 to 1f, 2 to 0.5f), budgetNanos = 10 * buildCost))
        assertTrue(built.isEmpty())
        assertEquals(2, s.residentCount)
    }

    @Test
    fun `over the cap, the patch unseen longest is evicted`() {
        val s = streamer(max = 3)
        s.update(1, intArrayOf(0, 1, 2), 3, priorities(0 to 3f, 1 to 2f, 2 to 1f), budgetNanos = 10 * buildCost)
        s.update(2, intArrayOf(1, 2), 2, priorities(), budgetNanos = 10 * buildCost) // patch 0 not seen on frame 2
        s.update(3, intArrayOf(5), 1, priorities(5 to 1f), budgetNanos = 10 * buildCost) // builds a 4th
        assertEquals(listOf(0), released, "patch 0 was unseen longest")
        assertFalse(s.isResident(0))
        assertEquals(3, s.residentCount)
    }

    @Test
    fun `patches in view this frame are never evicted even over the cap`() {
        val s = streamer(max = 2)
        s.update(1, intArrayOf(0, 1, 2, 3), 4, priorities(0 to 4f, 1 to 3f, 2 to 2f, 3 to 1f), budgetNanos = 10 * buildCost)
        assertTrue(released.isEmpty(), "all four are in view")
        assertEquals(4, s.residentCount)
        // Once only one is in view, the overflow drains down to the cap.
        s.update(2, intArrayOf(0), 1, priorities(), budgetNanos = 10 * buildCost)
        assertEquals(2, s.residentCount)
        assertTrue(s.isResident(0))
    }

    @Test
    fun `releaseAll empties the cache and patches can be rebuilt afterwards`() {
        val s = streamer()
        s.update(1, intArrayOf(1, 2), 2, priorities(1 to 1f, 2 to 0.5f), budgetNanos = 10 * buildCost)
        s.releaseAll()
        assertEquals(0, s.residentCount)
        assertEquals(setOf(1, 2), released.toSet())
        built.clear()
        s.update(5, intArrayOf(1), 1, priorities(1 to 1f), budgetNanos = 10 * buildCost)
        assertEquals(listOf(1), built)
    }

    @Test
    fun `nothing visible builds nothing`() {
        val s = streamer()
        assertEquals(0, s.update(1, IntArray(0), 0, priorities(), budgetNanos = 10 * buildCost))
        assertTrue(built.isEmpty())
    }
}
