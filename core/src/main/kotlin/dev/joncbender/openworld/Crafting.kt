package dev.joncbender.openworld

/** How one recipe stands in a settlement: [perTick] batches' worth of output it would make now, and whether it is [enabled] and [ready] (inputs stocked, room for the output). */
data class CraftStatus(val recipe: Recipe, val enabled: Boolean, val locked: Boolean, val ready: Boolean, val perTick: Float)

/**
 * Crafting. Pure like [Gathering]: a recipe a settlement may work (its tier is high enough and the player has not
 * switched it off) runs at a rate set by its people and their mood, and takes its inputs from the shared stockpile.
 */
object Crafting {
    object Tuning {
        /** People a settlement needs to run a recipe at full rate; fewer run it proportionally slower. */
        const val PEOPLE_PER_RECIPE = 2f
    }

    /** One line for a panel of what is being made now (at most a few kinds), or null when nothing is. */
    fun line(statuses: List<CraftStatus>): String? {
        val running = statuses.filter { it.enabled && !it.locked && it.ready && it.perTick > 0f }
        if (running.isEmpty()) return null
        val shown = running.take(SHOWN).joinToString(", ") { "${it.recipe.output.displayName()} +${String.format(java.util.Locale.ROOT, "%.1f", it.perTick)}" }
        return "Making: " + shown + if (running.size > SHOWN) " and ${running.size - SHOWN} more" else ""
    }

    private const val SHOWN = 3

    fun locked(settlement: Settlement, recipe: Recipe): Boolean = recipe.minTier > settlement.tier

    fun enabled(settlement: Settlement, recipe: Recipe): Boolean = !locked(settlement, recipe) && recipe.output !in settlement.recipesOff

    /** Batches per tick: one per [Recipe.ticks] at full people and [output] (the settlement's happiness factor, 0 in unrest). */
    fun batches(settlement: Settlement, recipe: Recipe, output: Float): Float =
        if (!enabled(settlement, recipe)) 0f else output * minOf(1f, settlement.population / Tuning.PEOPLE_PER_RECIPE) / recipe.ticks
}

/**
 * Crafts for every settlement into the [inventory] on each tick. A settlement's work on a recipe accrues in [carry] until
 * it makes a whole batch, which needs every input in stock and room for the output (storage is shared, as in gathering).
 * Work does not pile up while a recipe is blocked: at most one batch waits. Not saved, like [Gatherer]'s carry.
 */
class Crafter(private val inventory: Inventory) {
    private val carry = HashMap<Int, Float>()

    private fun key(settlement: Settlement, recipe: Recipe) = settlement.tile * Product.entries.size + recipe.output.ordinal

    private fun ready(recipe: Recipe, capacity: Int): Boolean =
        recipe.inputs.all { (item, units) -> inventory.count(item) >= units } && inventory.count(recipe.output) + recipe.amount <= capacity

    fun tick(settlements: Collection<Settlement>, outputOf: (Settlement) -> Float) {
        val capacity = Gathering.capacity(settlements)
        for (settlement in settlements) {
            val output = outputOf(settlement)
            for (recipe in Recipes.ALL) {
                val rate = Crafting.batches(settlement, recipe, output)
                if (rate <= 0f) continue
                val key = key(settlement, recipe)
                var work = minOf(1f + rate, (carry[key] ?: 0f) + rate)
                while (work >= WHOLE && ready(recipe, capacity)) {
                    for ((item, units) in recipe.inputs) inventory.take(item, units)
                    inventory.addUpTo(recipe.output, recipe.amount, capacity)
                    work -= 1f
                }
                carry[key] = minOf(work, 1f)
            }
        }
        inventory.flush()
    }

    /** Every recipe's standing in [settlement] for the panel; [output] is its happiness factor. */
    fun status(settlement: Settlement, settlements: Collection<Settlement>, output: Float): List<CraftStatus> {
        val capacity = Gathering.capacity(settlements)
        return Recipes.ALL.map { recipe ->
            CraftStatus(recipe, recipe.output !in settlement.recipesOff, Crafting.locked(settlement, recipe), ready(recipe, capacity), Crafting.batches(settlement, recipe, output) * recipe.amount)
        }
    }

    fun clear() = carry.clear()

    private companion object {
        // Thirds add up to a hair under one; a batch must not wait a tick for that.
        const val WHOLE = 1f - 1e-4f
    }
}
