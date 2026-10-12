package dev.joncbender.openworld

import com.badlogic.gdx.Files
import com.badlogic.gdx.files.FileHandle
import java.io.File
import java.io.IOException
import java.nio.file.Files as NioFiles
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every check runs on two kinds of handle: an absolute one, and a local one like Android's
 * `Gdx.files.local`, which libGDX's `moveTo` treats differently (it copies rather than renames).
 */
class CacheFilesTest {

    private val directory: File = NioFiles.createTempDirectory("cache_files_test").toFile().also { it.deleteOnExit() }

    /**
     * A handle in the local-storage style: its path is relative, and [file] resolves it against
     * [directory], as the Android backend does for `Gdx.files.local` (the base class does not).
     */
    private class LocalHandle(private val directory: File, private val relative: String) :
        FileHandle(File(relative), Files.FileType.Local) {
        override fun file(): File = File(directory, relative)
        override fun sibling(name: String): FileHandle = LocalHandle(directory, File(File(relative).parentFile, name).path)
    }

    private val kinds: Map<String, (String) -> FileHandle> = mapOf(
        "absolute" to { name -> FileHandle(File(directory, name)) },
        "local" to { name -> LocalHandle(directory, name) },
    )

    private fun eachKind(check: (kind: String, handle: (String) -> FileHandle) -> Unit) {
        for ((kind, handle) in kinds) check(kind, handle)
    }

    private fun fileKey(file: File): Any? = NioFiles.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    @Test
    fun `a finished write replaces the file and leaves no temporary behind`() = eachKind { kind, handle ->
        val file = handle("a-$kind.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)

        assertTrue(CacheFiles.writeAtomically(file) { it.write(byteArrayOf(9, 9)) }, kind)

        assertEquals(listOf<Byte>(9, 9), file.readBytes().toList(), kind)
        assertFalse(File(directory, "a-$kind.bin.tmp").exists(), kind)
    }

    @Test
    fun `the temporary file itself becomes the target, so there is no copy a failure could cut short`() = eachKind { kind, handle ->
        val file = handle("rename-$kind.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)
        val oldKey = fileKey(File(directory, "rename-$kind.bin"))
        var temporaryKey: Any? = null

        CacheFiles.writeAtomically(file) {
            it.write(4)
            it.flush()
            temporaryKey = fileKey(File(directory, "rename-$kind.bin.tmp"))
        }

        val newKey = fileKey(File(directory, "rename-$kind.bin"))
        assertEquals(temporaryKey, newKey, "$kind: the target should be the renamed temporary file")
        assertTrue(oldKey != newKey, "$kind: the old file should be replaced, not overwritten in place")
    }

    @Test
    fun `a write that fails partway keeps the old file and removes the temporary`() = eachKind { kind, handle ->
        val file = handle("b-$kind.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)

        val written = CacheFiles.writeAtomically(file) {
            it.write(byteArrayOf(7, 7, 7, 7, 7))
            throw IOException("disk full")
        }

        assertFalse(written, kind)
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList(), kind)
        assertFalse(File(directory, "b-$kind.bin.tmp").exists(), kind)
    }

    @Test
    fun `a rename that fails keeps the old file and does not fall back to copying`() = eachKind { kind, handle ->
        val file = handle("c-$kind.bin")
        file.writeBytes(byteArrayOf(1, 2, 3), false)
        val oldKey = fileKey(File(directory, "c-$kind.bin"))
        // Renaming a file over a non-empty directory fails; stand in for the target a directory.
        val blocked = handle("blocked-$kind")
        File(directory, "blocked-$kind/inner").also { it.parentFile.mkdirs(); it.writeBytes(byteArrayOf(0)) }

        assertFalse(CacheFiles.writeAtomically(blocked) { it.write(5) }, kind)

        assertTrue(File(directory, "blocked-$kind").isDirectory, kind)
        assertFalse(File(directory, "blocked-$kind.tmp").exists(), kind)
        assertEquals(oldKey, fileKey(File(directory, "c-$kind.bin")), kind)
    }

    @Test
    fun `a first write creates the file, and a failing first write creates nothing`() = eachKind { kind, handle ->
        val created = handle("d-$kind.bin")
        assertTrue(CacheFiles.writeAtomically(created) { it.write(5) }, kind)
        assertTrue(created.exists(), kind)

        val never = handle("e-$kind.bin")
        assertFalse(CacheFiles.writeAtomically(never) { throw IOException("no") }, kind)
        assertFalse(never.exists(), kind)
        assertFalse(File(directory, "e-$kind.bin.tmp").exists(), kind)
    }

    @Test
    fun `the temporary file sits beside the target`() = eachKind { kind, handle ->
        assertEquals("f-$kind.bin.tmp", CacheFiles.temporaryOf(handle("f-$kind.bin")).name(), kind)
    }

    @Test
    fun `room is judged on the nearest directory that exists`() {
        val deep = FileHandle(File(directory, "not/yet/created/world.bin"))
        assertTrue(CacheFiles.hasRoomFor(deep, 1L))
        assertFalse(CacheFiles.hasRoomFor(deep, Long.MAX_VALUE))
    }

    @Test
    fun `space about to be freed counts toward the room`() = eachKind { kind, handle ->
        val file = handle("g-$kind.bin")
        val usable = directory.usableSpace
        assertFalse(CacheFiles.hasRoomFor(file, usable + 1_000_000_000L), kind)
        assertTrue(CacheFiles.hasRoomFor(file, usable + 1_000_000_000L, reclaimable = 2_000_000_000L), kind)
    }
}
