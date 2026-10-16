package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals

class GameClockTest {
    private val tick = Buildings.Tuning.TICK_SECONDS

    @Test
    fun `one tick comes due per tick of real time at 1x`() {
        val clock = GameClock()
        assertEquals(0, clock.advance(tick / 2))
        assertEquals(1, clock.advance(tick / 2))
        assertEquals(1, clock.advance(tick))
    }

    @Test
    fun `3x runs three ticks in the time of one`() {
        val clock = GameClock().apply { speed = Speed.X3 }
        assertEquals(3, clock.advance(tick))
    }

    @Test
    fun `paused or held, no tick is due and no time is saved up`() {
        val clock = GameClock()
        clock.speed = Speed.PAUSED
        assertEquals(0, clock.advance(10 * tick))
        clock.speed = Speed.X1
        clock.hold("settings", true)
        assertEquals(0, clock.advance(10 * tick))
        clock.hold("settings", false)
        assertEquals(0, clock.advance(tick / 2))
    }

    @Test
    fun `a hold lasts until every reason has been released`() {
        val clock = GameClock()
        clock.hold("settings", true)
        clock.hold("other", true)
        clock.hold("settings", false)
        assertEquals(0, clock.advance(tick))
        clock.hold("other", false)
        assertEquals(1, clock.advance(tick))
    }

    @Test
    fun `a long frame is not made up for`() {
        val clock = GameClock()
        assertEquals(1, clock.advance(3600f))
        assertEquals(0, clock.advance(tick / 2))
    }

    @Test
    fun `a hold is held, a paused speed is not`() {
        val clock = GameClock()
        clock.speed = Speed.PAUSED
        assertEquals(false, clock.held)
        clock.hold("settings", true)
        assertEquals(true, clock.held)
    }
}
