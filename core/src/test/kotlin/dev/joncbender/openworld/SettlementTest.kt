package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementTest {
    // A small all-plains world, one tile of water, no open ocean.
    private fun world(frequency: Int = 8, water: Int? = null): CompactWorld {
        val sphere = GeodesicSphere.generate(frequency)
        val w = SphereWorld(sphere, Array(sphere.faceCount) { if (it == water) Biome.LAKE else Biome.PLAINS })
        return CompactWorld(w, sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
    }

    @Test
    fun `radius zero claims only the tile and each ring adds its neighbors`() {
        val world = world()
        assertEquals(1, SettlementRules.claim(world, 20, 0).size)
        assertEquals(7, SettlementRules.claim(world, 20, 1).size)
        assertEquals(19, SettlementRules.claim(world, 20, 2).size)
    }

    @Test
    fun `a homestead cannot go on water`() {
        val world = world(water = 20)
        assertEquals(FoundResult.NOT_LAND, SettlementBook(FakePreferences()).found(world, 20))
    }

    @Test
    fun `a tile inside another claim is refused and one outside is not`() {
        val world = world()
        val book = SettlementBook(FakePreferences())
        assertEquals(FoundResult.OK, book.found(world, 20))
        val inside = SettlementRules.claim(world, 20, 1).first { it != 20 }
        assertEquals(FoundResult.CLAIMED, book.found(world, inside))
        val outside = (0 until world.fineCount).first { it !in SettlementRules.claim(world, 20, 2) }
        assertEquals(FoundResult.OK, book.found(world, outside))
        assertEquals(2, book.all.size)
    }

    @Test
    fun `a tile whose claim would touch another claim is refused`() {
        val world = world()
        val book = SettlementBook(FakePreferences())
        book.found(world, 20)
        val ring2 = SettlementRules.claim(world, 20, 2).toSet() - SettlementRules.claim(world, 20, 1).toSet()
        assertEquals(FoundResult.CLAIMED, book.found(world, ring2.first()))
    }

    @Test
    fun `an open ocean tile is refused`() {
        val sphere = GeodesicSphere.generate(8)
        val w = SphereWorld(sphere, Array(sphere.faceCount) { Biome.OCEAN })
        val world = CompactWorld(w, 10, IntArray(10), IntArray(sphere.faceCount - 10))
        assertEquals(FoundResult.OPEN_OCEAN, SettlementRules.canFound(world, 12, emptyList()))
    }

    @Test
    fun `settlements survive a reload and clear removes them`() {
        val prefs = FakePreferences()
        val world = world()
        SettlementBook(prefs).found(world, 20)
        val reloaded = SettlementBook(prefs)
        assertEquals(Settlement(20, SettlementTier.HOMESTEAD), reloaded.at(20))
        reloaded.clear()
        assertNull(SettlementBook(prefs).at(20))
    }

    @Test
    fun `tiers grow in claim radius and end at city`() {
        assertTrue(SettlementTier.entries.zipWithNext().all { (a, b) -> a.claimRadius < b.claimRadius })
        assertNull(SettlementTier.CITY.next)
    }

    @Test
    fun `a damaged save loses its impossible tiles`() {
        val prefs = FakePreferences().apply { putString("settlements", "-1:HOMESTEAD,20:HOMESTEAD,999999:CITY,x:TOWN") }
        val book = SettlementBook(prefs)
        assertEquals(listOf(20, 999999), book.all.map { it.tile })
        book.retainWithin(world().fineCount)
        assertEquals(listOf(20), book.all.map { it.tile })
        assertEquals(listOf(20), SettlementBook(prefs).all.map { it.tile })
    }
}
