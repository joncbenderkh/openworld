package dev.joncbender.openworld

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.badlogic.gdx.files.FileHandle

class GenerationProgressTest {

    @Test
    fun `finishing every step in order ends at exactly one`() {
        val progress = GenerationProgress()
        var last = 0f
        for (step in GenerationProgress.Step.entries) {
            progress.done(step)
            assertTrue(progress.fraction > last, "$step moved it forward")
            last = progress.fraction
        }
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `a step reported late cannot move the share backwards`() {
        val progress = GenerationProgress()
        progress.done(GenerationProgress.Step.RIVERS)
        val before = progress.fraction
        progress.done(GenerationProgress.Step.SPHERE)
        assertEquals(before, progress.fraction)
    }

    @Test
    fun `the estimate waits for a steady guess, then follows the pace`() {
        var now = 0L
        val progress = GenerationProgress(clock = { now })
        assertNull(progress.etaMillis())
        now = 10_000_000_000L
        progress.done(GenerationProgress.Step.SPHERE)
        assertNull(progress.etaMillis())
        progress.done(GenerationProgress.Step.RIVERS)
        val total = GenerationProgress.Step.entries.sumOf { it.weight }.toFloat()
        val share = (GenerationProgress.Step.entries.takeWhile { it != GenerationProgress.Step.RIVERS }.sumOf { it.weight } +
            GenerationProgress.Step.RIVERS.weight) / total
        assertEquals((10_000 * (1 - share) / share).toLong(), progress.etaMillis())
    }

    @Test
    fun `the label of the next step is reported, and null after the last`() {
        val labels = mutableListOf<String?>()
        val progress = GenerationProgress(onChange = { _, _, next -> labels += next })
        for (step in GenerationProgress.Step.entries) progress.done(step)
        assertEquals(GenerationProgress.Step.ELEVATION.label, labels.first())
        assertNull(labels.last())
    }

    @Test
    fun `cancelling stops at the next step and reports nothing for it`() {
        var reports = 0
        val progress = GenerationProgress(onChange = { _, _, _ -> reports++ })
        progress.done(GenerationProgress.Step.SPHERE)
        progress.cancel()
        assertFailsWith<GenerationCancelled> { progress.done(GenerationProgress.Step.ELEVATION) }
        assertFailsWith<GenerationCancelled> { progress.checkpoint() }
        assertEquals(1, reports)
    }

    @Test
    fun `a cancelled generation throws and a finished one reaches one`() {
        val cancelled = GenerationProgress().also { it.cancel() }
        assertFailsWith<GenerationCancelled> { WorldGenerator(1L).generate(frequency = 10, progress = cancelled) }

        val finished = GenerationProgress()
        WorldGenerator(1L).generate(frequency = 10, progress = finished)
        assertTrue(finished.fraction > 0.5f)
    }

    @Test
    fun `a world cancelled while it is written leaves the old file and no temporary`() {
        val world = WorldGenerator(1L).generate(frequency = 10, corners = false)
        val directory = Files.createTempDirectory("cancel_save").toFile().also { it.deleteOnExit() }
        val file = FileHandle(java.io.File(directory, "world.bin"))
        file.writeString("old", false)
        val progress = GenerationProgress().also { it.cancel() }
        assertFailsWith<GenerationCancelled> {
            CompactWorldCache.save(file, world, frequency = 10, seed = 1L, resourceDensity = 0.12f, progress = progress)
        }
        assertEquals("old", file.readString())
        assertFalse(CacheFiles.temporaryOf(file).exists())
    }

    @Test
    fun `a staged file replaces the real one by rename and leaves nothing behind`() {
        val directory = Files.createTempDirectory("staged").toFile().also { it.deleteOnExit() }
        val file = FileHandle(java.io.File(directory, "world.bin"))
        val staged = CacheFiles.stagedOf(file)
        file.writeString("old", false)
        staged.writeString("new", false)
        assertTrue(CacheFiles.replace(file, staged))
        assertEquals("new", file.readString())
        assertFalse(staged.exists())
        assertNotNull(file.file().length())
    }
}
