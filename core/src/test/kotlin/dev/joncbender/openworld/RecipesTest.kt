package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipesTest {
    @Test
    fun `every product has exactly one recipe`() {
        assertEquals(Product.entries.toSet(), Recipes.ALL.map { it.output }.toSet())
        assertEquals(Product.entries.size, Recipes.ALL.size)
    }

    @Test
    fun `every recipe asks for real, positive amounts`() {
        for (recipe in Recipes.ALL) {
            assertTrue(recipe.inputs.isNotEmpty(), "${recipe.output} has inputs")
            assertTrue(recipe.inputs.values.all { it > 0 } && recipe.amount > 0 && recipe.ticks > 0, "${recipe.output}")
            assertTrue(recipe.inputs.keys.all { it in Items.all }, "${recipe.output} inputs exist")
        }
    }

    @Test
    fun `no recipe makes more than it takes`() {
        for (recipe in Recipes.ALL) assertTrue(recipe.inputs.values.sum() >= recipe.amount, "${recipe.output} creates units from nothing")
    }

    @Test
    fun `recipes cannot loop back on their own products`() {
        // Following inputs that are themselves products must never come back to the product we started from.
        fun reaches(from: Product, target: Product, seen: Set<Product> = emptySet()): Boolean =
            Recipes.making(from).inputs.keys.filterIsInstance<Product>().any { it == target || (it !in seen && reaches(it, target, seen + it)) }
        for (product in Product.entries) assertTrue(!reaches(product, product), "$product is made from itself")
    }

    @Test
    fun `every product has an icon cell`() {
        assertTrue(Items.all.size <= ResourceIcons.COLS * ResourceIcons.ROWS)
    }
}
