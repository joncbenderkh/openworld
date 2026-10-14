package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingWorldTest {
    @Test
    fun nothingIsPendingUntilOneIsSaved() {
        assertNull(PendingWorld.load(FakePreferences()))
    }

    @Test
    fun aSavedWorldComesBackWithItsSeedAndDensity() {
        val preferences = FakePreferences()
        PendingWorld(-4_611_686_018_427_387_904L, 0.28f).save(preferences)

        val loaded = PendingWorld.load(preferences)

        assertEquals(-4_611_686_018_427_387_904L, loaded?.seed)
        assertEquals(0.28f, loaded?.density)
    }

    @Test
    fun clearingForgetsIt() {
        val preferences = FakePreferences()
        PendingWorld(7L, 0.12f).save(preferences)

        PendingWorld.clear(preferences)

        assertNull(PendingWorld.load(preferences))
    }
}
