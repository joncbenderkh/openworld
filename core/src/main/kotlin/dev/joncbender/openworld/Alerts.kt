package dev.joncbender.openworld

enum class AlertKind(val text: String) {
    STARVING("starving"),
    UNREST("in unrest"),
    TIER_READY("ready to grow"),
}

/** One thing worth the player's eye: [kind] holds for the settlements on [tiles]. */
data class Alert(val kind: AlertKind, val tiles: List<Int>) {
    /** "2 starving": the badge text. */
    fun label(): String = "${tiles.size} ${kind.text}"
}

/** The alerts the map shows as badges, derived from the settlements' standing; nothing is stored. */
object Alerts {
    /** One alert per kind that holds anywhere, in [AlertKind] order, its tiles in settlement order. */
    fun of(statuses: Collection<SettlementStatus>): List<Alert> =
        AlertKind.entries.mapNotNull { kind ->
            val tiles = statuses.filter { holds(kind, it) }.map { it.settlement.tile }
            if (tiles.isEmpty()) null else Alert(kind, tiles)
        }

    private fun holds(kind: AlertKind, status: SettlementStatus): Boolean = when (kind) {
        AlertKind.STARVING -> Grievance.NO_FOOD in status.happiness.grievances
        AlertKind.UNREST -> status.happiness.mood == Mood.UNREST
        AlertKind.TIER_READY -> status.upgrade == UpgradeResult.OK
    }

    /** One line for a text overlay, or null when there is nothing to report. */
    fun line(alerts: List<Alert>): String? =
        if (alerts.isEmpty()) null else "Alerts: " + alerts.joinToString("   ") { it.label() }
}
