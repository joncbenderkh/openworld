package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryTest {

    @Test
    fun `every resource starts at zero`() {
        val inventory = Inventory(FakePreferences())
        for (resource in Resource.entries) {
            assertEquals(0, inventory.count(resource))
        }
        assertTrue(inventory.heldResources().isEmpty())
    }

    @Test
    fun `add increases the count`() {
        val inventory = Inventory(FakePreferences())
        inventory.add(Resource.WOOD)
        inventory.add(Resource.WOOD, 4)
        assertEquals(5, inventory.count(Resource.WOOD))
        assertEquals(mapOf(Resource.WOOD to 5), inventory.heldResources())
    }

    @Test
    fun `remove succeeds and decreases the count when there's enough`() {
        val inventory = Inventory(FakePreferences())
        inventory.add(Resource.ORE, 3)
        assertTrue(inventory.remove(Resource.ORE, 2))
        assertEquals(1, inventory.count(Resource.ORE))
    }

    @Test
    fun `remove fails and leaves the count unchanged when there's not enough`() {
        val inventory = Inventory(FakePreferences())
        inventory.add(Resource.GOLD, 1)
        assertFalse(inventory.remove(Resource.GOLD, 2))
        assertEquals(1, inventory.count(Resource.GOLD))
    }

    @Test
    fun `clear resets every count to zero`() {
        val inventory = Inventory(FakePreferences())
        inventory.add(Resource.FISH, 3)
        inventory.add(Resource.STONE, 7)
        inventory.clear()
        for (resource in Resource.entries) {
            assertEquals(0, inventory.count(resource))
        }
        assertTrue(inventory.heldResources().isEmpty())
    }

    @Test
    fun `counts persist across separate Inventory instances backed by the same store`() {
        // Simulates an app restart: a fresh Inventory reading the same
        // underlying preferences should pick up right where the last one left off.
        val preferences = FakePreferences()
        Inventory(preferences).add(Resource.PEARLS, 6)

        val restarted = Inventory(preferences)
        assertEquals(6, restarted.count(Resource.PEARLS))
    }

    @Test
    fun `clear removes persisted values too, not just the in-memory count`() {
        val preferences = FakePreferences()
        Inventory(preferences).apply {
            add(Resource.FURS, 2)
            clear()
        }

        val restarted = Inventory(preferences)
        assertEquals(0, restarted.count(Resource.FURS))
    }
}
