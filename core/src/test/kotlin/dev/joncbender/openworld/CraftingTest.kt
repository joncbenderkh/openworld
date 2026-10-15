package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CraftingTest {
    private val prefs = FakePreferences()
    private val inventory = Inventory(prefs)
    private val crafter = Crafter(inventory)

    private val village = Settlement(20, SettlementTier.VILLAGE, 20f)
    private val homestead = Settlement(21, SettlementTier.HOMESTEAD, 5f)

    private fun tick(settlement: Settlement, output: Float = 1f, times: Int = 1) =
        repeat(times) { crafter.tick(listOf(settlement)) { output } }

    @Test
    fun `a batch takes its inputs and makes its output together`() {
        inventory.add(Resource.FURS, 10)
        tick(homestead, times = 4)
        assertEquals(1, inventory.count(Product.CLOTHING))
        assertEquals(8, inventory.count(Resource.FURS))
    }

    @Test
    fun `nothing is made without every input`() {
        inventory.add(Resource.ORE, 5)
        tick(village, times = 10)
        assertEquals(0, inventory.count(Product.METAL))
        assertEquals(5, inventory.count(Resource.ORE))
    }

    @Test
    fun `a recipe above the settlement's tier does not run`() {
        inventory.add(Resource.ORE, 4)
        inventory.add(Resource.WOOD, 2)
        tick(homestead, times = 10)
        assertEquals(0, inventory.count(Product.METAL))
        assertTrue(Crafting.locked(homestead, Recipes.making(Product.METAL)))
    }

    @Test
    fun `a switched-off recipe does not run`() {
        inventory.add(Resource.FURS, 10)
        tick(homestead.copy(recipesOff = setOf(Product.CLOTHING)), times = 10)
        assertEquals(0, inventory.count(Product.CLOTHING))
    }

    @Test
    fun `unrest stops crafting and fewer people make it slower`() {
        inventory.add(Resource.FURS, 40)
        tick(homestead, output = 0f, times = 10)
        assertEquals(0, inventory.count(Product.CLOTHING))
        val full = Crafting.batches(homestead, Recipes.making(Product.FLOUR), 1f)
        assertEquals(full / 2, Crafting.batches(homestead.copy(population = 1f), Recipes.making(Product.FLOUR), 1f), 1e-6f)
    }

    @Test
    fun `work does not pile up while a recipe is blocked`() {
        tick(homestead, times = 30)
        inventory.add(Resource.FURS, 20)
        tick(homestead)
        assertEquals(1, inventory.count(Product.CLOTHING), "one waiting batch, not thirty")
    }

    @Test
    fun `a full store for the output stops the recipe`() {
        val capacity = Gathering.storage(SettlementTier.HOMESTEAD)
        inventory.add(Product.CLOTHING, capacity)
        inventory.add(Resource.FURS, 10)
        tick(homestead, times = 10)
        assertEquals(capacity, inventory.count(Product.CLOTHING))
        assertEquals(10, inventory.count(Resource.FURS))
    }

    @Test
    fun `a chain runs from grain to flour to bread`() {
        inventory.add(Resource.GRAIN, 4)
        tick(homestead, times = 12)
        assertTrue(inventory.count(Product.BREAD) >= 1)
    }

    @Test
    fun `status lists each recipe with its state`() {
        inventory.add(Resource.GRAIN, 2)
        val status = crafter.status(homestead.copy(recipesOff = setOf(Product.CLOTHING)), listOf(homestead), 1f)
        assertEquals(Recipes.ALL.size, status.size)
        val flour = status.first { it.recipe.output == Product.FLOUR }
        assertTrue(flour.enabled && flour.ready && !flour.locked)
        assertFalse(status.first { it.recipe.output == Product.CLOTHING }.enabled)
        assertTrue(status.first { it.recipe.output == Product.JEWELRY }.locked)
    }

    @Test
    fun `switched-off recipes are saved per settlement and unknown names are ignored`() {
        val sphere = GeodesicSphere.generate(8)
        val world = CompactWorld(SphereWorld(sphere, Array(sphere.faceCount) { Biome.PLAINS }), sphere.faceCount, IntArray(sphere.faceCount) { it }, IntArray(0))
        val book = SettlementBook(prefs)
        book.found(world, 20)
        book.craft(20, Product.METAL, on = false)
        assertEquals(setOf(Product.METAL), SettlementBook(prefs).at(20)?.recipesOff)
        book.craft(20, Product.METAL, on = true)
        assertEquals(emptySet(), SettlementBook(prefs).at(20)?.recipesOff)
        prefs.putString("recipes_off", "0/20:NOPE|FLOUR")
        assertEquals(setOf(Product.FLOUR), SettlementBook(prefs).at(20)?.recipesOff)
    }

    @Test
    fun `the panel line names what is being made and stays quiet when nothing is`() {
        assertEquals(null, Crafting.line(crafter.status(homestead, listOf(homestead), 1f)))
        inventory.add(Resource.GRAIN, 4)
        inventory.add(Resource.FURS, 4)
        val line = Crafting.line(crafter.status(homestead, listOf(homestead), 1f))
        assertTrue(line != null && line.startsWith("Making: ") && "Flour" in line && "Clothing" in line, line)
    }

    @Test
    fun `a craft reaches the store whole, with no flush between taking and giving`() {
        inventory.add(Resource.FURS, 10)
        tick(homestead, times = 12)
        assertTrue(inventory.count(Product.CLOTHING) >= 2)
        for (saved in prefs.flushed) {
            val furs = saved["inventory_FURS"] as? Int ?: 0
            val clothing = saved["inventory_CLOTHING"] as? Int ?: 0
            assertEquals(10, furs + 2 * clothing, "a flush saw $furs furs and $clothing clothing")
        }
    }

    @Test
    fun `a settlement in unrest is not shown as making anything`() {
        inventory.add(Resource.FURS, 4)
        val status = crafter.status(homestead, listOf(homestead), 0f)
        assertEquals(null, Crafting.line(status))
    }
}
