package dev.joncbender.openworld

import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParallelTest {

    @Test
    fun `every index is visited exactly once`() {
        val count = 100_003
        val visits = AtomicIntegerArray(count)
        Parallel.forEachIndex(count) { visits.incrementAndGet(it) }
        for (i in 0 until count) assertEquals(1, visits.get(i), "index $i")
    }

    @Test
    fun `small and empty ranges run inline`() {
        var calls = 0
        Parallel.forRanges(0) { from, to -> calls++; assertEquals(from, to) }
        Parallel.forRanges(10) { from, to -> calls++; assertEquals(0 to 10, from to to) }
        assertEquals(2, calls)
    }

    @Test
    fun `worker exceptions propagate to the caller`() {
        assertFailsWith<IllegalStateException> {
            Parallel.forEachIndex(100_000) { if (it == 77_777) error("boom") }
        }
    }
}
