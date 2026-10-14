package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingWorldsTest {
    private val preferences = FakePreferences()
    private val worlds = PendingWorlds(preferences)

    @Test
    fun `a refused replacement is owed until it is made`() {
        worlds.refused(newSeed = 2, density = 0.28f, keepInventory = false)
        assertEquals(2L, worlds.pending?.seed)
        worlds.accepted(newSeed = 2, keepInventory = false)
        assertNull(worlds.pending)
        assertNull(PendingWorld.load(preferences))
    }

    @Test
    fun `a refused retry keeps what is owed for the next retry`() {
        worlds.refused(2, 0.28f, keepInventory = false)
        val first = worlds.retry(shownSeed = 2, currentSeed = 1)
        worlds.refused(first.seed, first.density!!, first.keepInventory)
        val second = worlds.retry(shownSeed = 2, currentSeed = 1)
        assertEquals(2L, second.seed)
        assertEquals(0.28f, second.density)
        assertFalse(second.keepInventory)
    }

    @Test
    fun `retrying the current world never clears the inventory, however often`() {
        repeat(3) {
            val plan = worlds.retry(shownSeed = 1, currentSeed = 1)
            assertEquals(1L, plan.seed)
            assertNull(plan.density)
            assertTrue(plan.keepInventory)
            worlds.refused(plan.seed, 0.12f, plan.keepInventory)
        }
        assertNull(worlds.pending)
    }

    @Test
    fun `an error for the current world does not retry a different pending world`() {
        worlds.refused(456, 0.28f, keepInventory = false)
        val plan = worlds.retry(shownSeed = 123, currentSeed = 123)
        assertEquals(123L, plan.seed)
        assertTrue(plan.keepInventory)
    }

    @Test
    fun `recovering the current world leaves a different refused world owed`() {
        worlds.refused(456, 0.28f, keepInventory = false)
        worlds.accepted(newSeed = 123, keepInventory = true)
        assertEquals(456L, worlds.pending?.seed)
    }

    @Test
    fun `a pending world with the current seed keeps the inventory and is paid off`() {
        worlds.refused(123, 0.28f, keepInventory = false)
        val plan = worlds.retry(shownSeed = 123, currentSeed = 123)
        assertTrue(plan.keepInventory)
        worlds.accepted(plan.seed, plan.keepInventory)
        assertNull(worlds.pending)
    }

    @Test
    fun `a refusal survives a restart`() {
        worlds.refused(9, 0.12f, keepInventory = false)
        assertEquals(9L, PendingWorlds(preferences).pending?.seed)
    }
}
