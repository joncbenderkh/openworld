package dev.joncbender.openworld

/** Why a settlement is less happy than it could be; the panel lists these so the player knows what to fix. */
enum class Grievance(val text: String) {
    NO_FOOD("no food"),
    NO_SHELTER("no shelter"),
    NO_WARMTH("no warmth"),
    SHORT_FOOD("short of food"),
    SHORT_SHELTER("short of shelter"),
    SHORT_WARMTH("short of warmth"),
    CROWDED("overcrowded"),
    SAME_FARE("little variety"),
    FEW_LUXURIES("few luxuries"),
    NO_LUXURIES("no luxuries"),
}

enum class Mood(val text: String) { UNREST("unrest"), UNHAPPY("unhappy"), CONTENT("content"), HAPPY("happy") }

/** How happy a settlement is: [value] 0..1, the [mood] it falls in, the [grievances] holding it back and the [output] factor it implies. */
data class Happiness(val value: Float, val mood: Mood, val grievances: List<Grievance>, val output: Float) {
    /** One line for a panel: the mood, then what holds it back. */
    fun line(): String = "Mood: ${mood.text}" + if (grievances.isEmpty()) "" else "  ·  " + grievances.joinToString(", ") { it.text }
}

/**
 * Happiness. Pure like [PopulationModel]: it depends on the settlement, its site and which resources the shared
 * stockpile holds. Every number is in [Tuning] so the balance can change without touching the logic.
 *
 * Value = [Tuning.BASE] + worst-met need * [Tuning.NEEDS] + variety * [Tuning.VARIETY] + luxuries * [Tuning.LUXURY]
 * - crowding * [Tuning.CROWDING]. Output (gathering, and growth when positive) falls linearly from full at [Tuning.CONTENT]
 * to none at [Tuning.UNREST]; below [Tuning.UNREST] output stops and people leave.
 *
 * Distance from trade partners is not modelled yet (there are no trade routes).
 */
object HappinessModel {
    object Tuning {
        const val BASE = 0.2f
        const val NEEDS = 0.5f
        const val VARIETY = 0.15f
        const val LUXURY = 0.15f
        const val CROWDING = 0.15f

        /** Kinds of need-meeting resource on the land for full variety. */
        const val VARIETY_TARGET = 3

        /** Kinds of luxury in the stockpile for full luxury. */
        const val LUXURY_TARGET = 2

        /** Population share of the cap above which crowding starts, and where it is complete. */
        const val CROWDING_FROM = 0.8f

        const val UNREST = 0.25f
        const val CONTENT = 0.5f
        /** Only the label: output is already full from [CONTENT]. */
        const val HAPPY = 0.8f

        /** Share of the people who leave in a tick of unrest. */
        const val LEAVE_RATE = 0.1f

        /** Units of a luxury the stockpile must hold for it to count, so one unit coming and going does not flicker the mood. */
        const val STOCKED = 10

        val LUXURIES = setOf(Resource.SPICES, Resource.PEARLS, Resource.GEMS, Resource.GOLD)
    }

    /** The resources [counts] holds enough of to count toward happiness. */
    fun stocked(counts: Map<Resource, Int>): Set<Resource> = counts.filterValues { it >= Tuning.STOCKED }.keys

    fun of(settlement: Settlement, site: SettlementSite, held: Set<Resource>): Happiness {
        val grievances = ArrayList<Grievance>()
        val wanted = PopulationModel.needs(settlement.tier, site.cold)
        val met = wanted.map { PopulationModel.served(settlement, site, it) }
        wanted.forEachIndexed { i, need ->
            when {
                site.supply(need) == 0 -> grievances += none(need)
                met[i] < 1f -> grievances += short(need)
            }
        }
        val kinds = site.deposits.map { it.resource }.filter { r -> Need.entries.any { r in it.sources } }.distinct().size
        val variety = minOf(1f, kinds.toFloat() / Tuning.VARIETY_TARGET)
        if (variety < 1f) grievances += Grievance.SAME_FARE
        val luxuries = held.count { it in Tuning.LUXURIES }
        val luxury = minOf(1f, luxuries.toFloat() / Tuning.LUXURY_TARGET)
        if (luxury < 1f) grievances += if (luxuries == 0) Grievance.NO_LUXURIES else Grievance.FEW_LUXURIES
        val share = settlement.population / settlement.tier.populationCap
        val crowding = ((share - Tuning.CROWDING_FROM) / (1f - Tuning.CROWDING_FROM)).coerceIn(0f, 1f)
        if (crowding > 0f) grievances += Grievance.CROWDED
        val value = (Tuning.BASE + (met.minOrNull() ?: 1f) * Tuning.NEEDS + variety * Tuning.VARIETY + luxury * Tuning.LUXURY - crowding * Tuning.CROWDING).coerceIn(0f, 1f)
        val mood = when {
            value < Tuning.UNREST -> Mood.UNREST
            value < Tuning.CONTENT -> Mood.UNHAPPY
            value < Tuning.HAPPY -> Mood.CONTENT
            else -> Mood.HAPPY
        }
        val output = ((value - Tuning.UNREST) / (Tuning.CONTENT - Tuning.UNREST)).coerceIn(0f, 1f)
        return Happiness(value, mood, grievances, output)
    }

    private fun none(need: Need) = when (need) {
        Need.FOOD -> Grievance.NO_FOOD
        Need.SHELTER -> Grievance.NO_SHELTER
        Need.WARMTH -> Grievance.NO_WARMTH
    }

    private fun short(need: Need) = when (need) {
        Need.FOOD -> Grievance.SHORT_FOOD
        Need.SHELTER -> Grievance.SHORT_SHELTER
        Need.WARMTH -> Grievance.SHORT_WARMTH
    }
}
