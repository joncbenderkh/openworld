package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals

class StockpileTest {
    @Test
    fun `lines list the most plentiful first (ties in item order), with income only for gathered resources`() {
        val lines = Stockpile.lines(
            held = mapOf(Resource.GRAIN to 5, Product.TOOLS to 9, Resource.WOOD to 5),
            income = mapOf(Resource.GRAIN to 0.5f),
            capacity = 50,
        )
        assertEquals(listOf(Product.TOOLS, Resource.WOOD, Resource.GRAIN), lines.map { it.item })
        assertEquals(listOf(0f, 0f, 0.5f), lines.map { it.income })
        assertEquals(listOf("Tools 9 of 50", "Wood 5 of 50", "Grain 5 of 50  +0.5 per tick"), Stockpile.text(lines))
    }

    @Test
    fun `a resource being gathered shows before any is held`() {
        val lines = Stockpile.lines(emptyMap(), mapOf(Resource.FISH to 0.25f, Resource.WOOD to 0f), 50)
        assertEquals(listOf(Resource.FISH), lines.map { it.item })
        assertEquals(0, lines.single().count)
    }

    @Test
    fun `the stockpile's income is the sum of the settlements' incomes`() {
        val sphere = GeodesicSphere.generate(8)
        val world = SphereWorld(sphere, Array(sphere.faceCount) { Biome.PLAINS })
        val prefs = FakePreferences()
        val gatherer = Gatherer(Inventory(prefs), Deposits(prefs))
        val empty = CompactWorld(world, sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
        val first = Settlement(20, SettlementTier.HOMESTEAD, 10f)
        val tile = SettlementRules.claim(empty, 20, 1).first { it != 20 }
        world.setResources(tile, listOf(Resource.GRAIN))
        val far = (0 until sphere.faceCount).first { f -> f != 20 && SettlementRules.claim(empty, f, 1).none { it in SettlementRules.claim(empty, 20, 1) } }
        val farGrain = SettlementRules.claim(empty, far, 1).first { it != far }
        world.setResources(farGrain, listOf(Resource.GRAIN))
        val second = Settlement(far, SettlementTier.HOMESTEAD, 10f)
        val all = listOf(first, second)
        val sites = all.associate { s -> s.tile to SettlementSite.of(empty, s).let { SettlementSite(it.tiles, IntArray(Need.entries.size) { 1000 }, it.cold, it.deposits) } }
        val total = gatherer.income(all) { sites.getValue(it.tile) }
        val parts = all.map { s -> gatherer.economy(s, all) { sites.getValue(it.tile) }.income[Resource.GRAIN] ?: 0f }
        assertEquals(parts.sum(), total[Resource.GRAIN] ?: 0f, 1e-4f)
    }
}
