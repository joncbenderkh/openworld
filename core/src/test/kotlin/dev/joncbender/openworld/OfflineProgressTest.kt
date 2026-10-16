package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineProgressTest {
    private val tickMillis = (Buildings.Tuning.TICK_SECONDS * 1000).toLong()
    private var clock = 1_000_000L
    private val prefs = FakePreferences()
    private val offline = OfflineProgress(prefs) { clock }

    @Test
    fun `time away is worth whole ticks`() {
        offline.stamp(7L)
        clock += 10 * tickMillis + tickMillis / 2
        assertEquals(10, offline.pending(7L))
    }

    @Test
    fun `it is capped at four hours`() {
        offline.stamp(7L)
        clock += 30L * 24 * 3600 * 1000
        assertEquals(OfflineProgress.Tuning.CAP_TICKS, offline.pending(7L))
        assertEquals(4 * 3600 / 2, OfflineProgress.Tuning.CAP_TICKS)
    }

    @Test
    fun `no stamp, another world or a clock gone backwards is worth nothing`() {
        assertEquals(0, offline.pending(7L))
        offline.stamp(7L)
        clock += 100 * tickMillis
        assertEquals(0, offline.pending(8L))
        clock -= 200 * tickMillis
        assertEquals(0, offline.pending(7L))
    }

    @Test
    fun `the stamp survives a restart and clears with the world`() {
        offline.stamp(7L)
        clock += 5 * tickMillis
        assertEquals(5, OfflineProgress(prefs) { clock }.pending(7L))
        offline.clear()
        assertEquals(0, OfflineProgress(prefs) { clock }.pending(7L))
    }

    @Test
    fun `ticks still owed at a stamp are kept and added to the time since`() {
        offline.stamp(7L, owed = 100)
        clock += 5 * tickMillis
        assertEquals(105, OfflineProgress(prefs) { clock }.pending(7L))
        offline.stamp(7L, owed = OfflineProgress.Tuning.CAP_TICKS)
        clock += 50 * tickMillis
        assertEquals(OfflineProgress.Tuning.CAP_TICKS, offline.pending(7L))
        offline.stamp(7L)
        assertEquals(0, offline.pending(7L))
    }
}
