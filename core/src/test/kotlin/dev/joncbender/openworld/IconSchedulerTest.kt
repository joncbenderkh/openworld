package dev.joncbender.openworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconSchedulerTest {
    private val patches = 8

    /** Stands in for the icon layer: a patch is built once tried, and only a patch with resources holds a mesh. */
    private class Layer(val patches: Int, val withIcons: Set<Int>, val terrain: Set<Int>) {
        val done = BooleanArray(patches)
        val resident = BooleanArray(patches)
        val order = ArrayList<Int>()

        fun build(patch: Int) {
            done[patch] = true
            order += patch
            if (patch in withIcons) resident[patch] = true
        }

        fun release(patch: Int) {
            done[patch] = false
            resident[patch] = false
        }
    }

    private fun IconScheduler.frame(layer: Layer, visible: IntArray, priority: FloatArray, frame: Int) {
        see(visible, visible.size, frame)
        build(visible, visible.size, priority, { it in layer.terrain }, { layer.done[it] }) { layer.build(it) }
        releaseStale(frame, layer.patches, { layer.resident[it] }) { layer.release(it) }
    }

    private fun priorities(vararg pairs: Pair<Int, Float>) = FloatArray(patches).also { p -> pairs.forEach { (k, v) -> p[k] = v } }

    @Test
    fun `icons switch on below the enter distance and off above the exit distance`() {
        val s = IconScheduler(patches)
        assertFalse(s.update(1.27f), "between the two, from off")
        assertTrue(s.update(1.24f))
        assertTrue(s.update(1.29f), "between the two, from on")
        assertFalse(s.update(1.31f))
        assertFalse(s.update(1.27f), "back in the band stays off")
    }

    @Test
    fun `deactivating switches the icons off until the camera comes close again`() {
        val s = IconScheduler(patches)
        s.update(1.1f)
        s.deactivate()
        assertFalse(s.active)
        assertFalse(s.update(1.27f))
        assertTrue(s.update(1.1f))
    }

    @Test
    fun `two patches are built a frame, the closest to the view's middle first`() {
        val s = IconScheduler(patches)
        val layer = Layer(patches, withIcons = (0 until patches).toSet(), terrain = (0 until patches).toSet())
        val visible = intArrayOf(0, 1, 2, 3)
        val priority = priorities(0 to 0.2f, 1 to 0.9f, 2 to 0.5f, 3 to 0.7f)
        s.frame(layer, visible, priority, 1)
        assertEquals(listOf(1, 3), layer.order)
        s.frame(layer, visible, priority, 2)
        assertEquals(listOf(1, 3, 2, 0), layer.order)
        s.frame(layer, visible, priority, 3)
        assertEquals(4, layer.order.size, "nothing left to build")
    }

    @Test
    fun `a patch whose terrain is not resident, or that is out of view, is not built`() {
        val s = IconScheduler(patches)
        val layer = Layer(patches, withIcons = (0 until patches).toSet(), terrain = setOf(2, 3, 4))
        val visible = intArrayOf(1, 2, 3)
        s.frame(layer, visible, priorities(1 to 1f, 2 to 0.3f, 3 to 0.2f, 4 to 0.9f), 1)
        assertEquals(listOf(2, 3), layer.order)
    }

    @Test
    fun `a patch with no resources never takes a later patch's slot`() {
        val s = IconScheduler(patches)
        // Patches 0 and 1 are empty and nearest; 2 and 3 follow.
        val layer = Layer(patches, withIcons = setOf(2, 3), terrain = (0 until patches).toSet())
        val visible = intArrayOf(0, 1, 2, 3)
        val priority = priorities(0 to 0.9f, 1 to 0.8f, 2 to 0.5f, 3 to 0.4f)
        s.frame(layer, visible, priority, 1)
        s.frame(layer, visible, priority, 2)
        assertEquals(listOf(0, 1, 2, 3), layer.order)
        assertTrue(layer.resident[2] && layer.resident[3])
        s.frame(layer, visible, priority, 3)
        assertEquals(4, layer.order.size, "the empty ones are not retried")
    }

    @Test
    fun `a patch keeps its icons for 90 frames after leaving the view, then is released`() {
        val s = IconScheduler(patches)
        val layer = Layer(patches, withIcons = setOf(0), terrain = setOf(0))
        val priority = priorities(0 to 1f)
        s.frame(layer, intArrayOf(0), priority, 10)
        assertTrue(layer.resident[0])
        s.frame(layer, IntArray(0), priority, 10 + IconScheduler.KEEP_FRAMES)
        assertTrue(layer.resident[0], "90 frames out of view is still kept")
        s.frame(layer, IntArray(0), priority, 10 + IconScheduler.KEEP_FRAMES + 1)
        assertFalse(layer.resident[0])
    }

    @Test
    fun `coming back into view restarts the keep time`() {
        val s = IconScheduler(patches)
        val layer = Layer(patches, withIcons = setOf(0), terrain = setOf(0))
        val priority = priorities(0 to 1f)
        s.frame(layer, intArrayOf(0), priority, 0)
        s.frame(layer, intArrayOf(0), priority, 80)
        s.frame(layer, IntArray(0), priority, 80 + IconScheduler.KEEP_FRAMES)
        assertTrue(layer.resident[0])
    }
}
