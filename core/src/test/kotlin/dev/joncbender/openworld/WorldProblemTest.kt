package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldProblemTest {
    @Test
    fun onlyARefusedNewWorldKeepsTheCurrentOne() {
        assertEquals(listOf(WorldProblem.NO_ROOM_FOR_NEW), WorldProblem.entries.filter { it.worldKept })
    }

    @Test
    fun everyProblemSaysSomething() {
        assertTrue(WorldProblem.entries.all { it.message.isNotBlank() })
    }

    @Test
    fun onlyAnUnreadableWorldIsNotAStorageProblem() {
        assertEquals(listOf(WorldProblem.UNREADABLE), WorldProblem.entries.filterNot { it.needsStorage })
    }
}
