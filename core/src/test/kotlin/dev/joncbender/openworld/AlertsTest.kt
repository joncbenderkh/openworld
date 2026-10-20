package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlertsTest {
    private fun status(
        tile: Int,
        mood: Mood = Mood.CONTENT,
        grievances: List<Grievance> = emptyList(),
        upgrade: UpgradeResult = UpgradeResult.TOO_SMALL,
    ) = SettlementStatus(
        Settlement(tile, SettlementTier.HOMESTEAD, 10f), emptyList(), Trend.STEADY, upgrade,
        Happiness(0.5f, mood, grievances, 1f),
    )

    @Test
    fun `a settled world with nothing wrong has no alerts`() {
        assertEquals(emptyList(), Alerts.of(listOf(status(1), status(2))))
        assertNull(Alerts.line(emptyList()))
    }

    @Test
    fun `each condition raises its own alert`() {
        val alerts = Alerts.of(
            listOf(
                status(1, grievances = listOf(Grievance.NO_FOOD)),
                status(2, mood = Mood.UNREST),
                status(3, upgrade = UpgradeResult.OK),
            ),
        )
        assertEquals(
            listOf(Alert(AlertKind.STARVING, listOf(1)), Alert(AlertKind.UNREST, listOf(2)), Alert(AlertKind.TIER_READY, listOf(3))),
            alerts,
        )
    }

    @Test
    fun `settlements with the same trouble share one alert in settlement order`() {
        val alerts = Alerts.of(listOf(status(7, mood = Mood.UNREST), status(4), status(9, mood = Mood.UNREST)))
        assertEquals(listOf(Alert(AlertKind.UNREST, listOf(7, 9))), alerts)
        assertEquals("2 in unrest", alerts.single().label())
    }

    @Test
    fun `being short of food is not starving`() {
        assertEquals(emptyList(), Alerts.of(listOf(status(1, grievances = listOf(Grievance.SHORT_FOOD)))))
    }

    @Test
    fun `the overlay line lists every alert`() {
        val line = Alerts.line(listOf(Alert(AlertKind.STARVING, listOf(1, 2)), Alert(AlertKind.TIER_READY, listOf(3))))
        assertEquals("Alerts: 2 starving   1 ready to grow", line)
    }
}
