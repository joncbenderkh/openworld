package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheFilesTest {

    private val directory: File = Files.createTempDirectory("cache_files_test").toFile().also { it.deleteOnExit() }

    private fun handle(name: String) = FileHandle(File(directory, name))

    @Test
    fun `a finished write replaces the file and leaves no temporary behind`() {
        val file = handle("a.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)

        assertTrue(CacheFiles.writeAtomically(file) { it.write(byteArrayOf(9, 9)) })

        assertEquals(listOf<Byte>(9, 9), file.readBytes().toList())
        assertFalse(File(directory, "a.bin.tmp").exists())
    }

    @Test
    fun `a write that fails partway keeps the old file and removes the temporary`() {
        val file = handle("b.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)

        val written = CacheFiles.writeAtomically(file) {
            it.write(byteArrayOf(7, 7, 7, 7, 7))
            throw java.io.IOException("disk full")
        }

        assertFalse(written)
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        assertFalse(File(directory, "b.bin.tmp").exists())
    }

    @Test
    fun `a first write creates the file, and a failing first write creates nothing`() {
        val created = handle("c.bin")
        assertTrue(CacheFiles.writeAtomically(created) { it.write(5) })
        assertTrue(created.exists())

        val never = handle("d.bin")
        assertFalse(CacheFiles.writeAtomically(never) { throw java.io.IOException("no") })
        assertFalse(never.exists())
        assertFalse(File(directory, "d.bin.tmp").exists())
    }

    @Test
    fun `room is judged on the nearest directory that exists`() {
        val deep = FileHandle(File(directory, "not/yet/created/world.bin"))
        assertTrue(CacheFiles.hasRoomFor(deep, 1L))
        assertFalse(CacheFiles.hasRoomFor(deep, Long.MAX_VALUE))
    }

    @Test
    fun `space about to be freed counts toward the room`() {
        val file = handle("e.bin")
        val usable = directory.usableSpace
        assertFalse(CacheFiles.hasRoomFor(file, usable + 1_000_000L))
        assertTrue(CacheFiles.hasRoomFor(file, usable + 1_000_000L, reclaimable = 2_000_000L))
    }
}
