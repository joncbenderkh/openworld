package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** What the world caches need from the file system: whole-file replacement and a look at free space. */
internal object CacheFiles {

    /** Where [file] is written before it replaces the real one; a crash can leave this behind. */
    fun temporaryOf(file: FileHandle): FileHandle = file.sibling(file.name() + ".tmp")

    /**
     * Writes [file] by way of a temporary sibling that is renamed over it once [write] has finished,
     * so a failure partway (a full disk, a killed process) never replaces a good file with a partial
     * one. Returns whether the file was replaced; on failure the temporary file is removed.
     *
     * The rename is done on the underlying [java.io.File] on purpose: [FileHandle.moveTo] renames only
     * absolute and external handles, and for a local one (what Android's `Gdx.files.local` gives) it
     * copies the temporary file onto the target, which a failure would leave truncated. If the rename
     * fails the old file is kept; there is no copying fallback.
     */
    fun writeAtomically(file: FileHandle, write: (OutputStream) -> Unit): Boolean {
        val temp = temporaryOf(file)
        return try {
            temp.write(false).use(write)
            if (!temp.file().renameTo(file.file())) throw IOException("could not rename ${temp.path()} over ${file.path()}")
            true
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (ignored: Exception) {
            }
            false
        }
    }

    /**
     * Whether the storage holding [file] has [needed] bytes free, counting [reclaimable] bytes that
     * are about to be deleted. The directory may not exist yet, so the nearest one that does is asked.
     * If the free space cannot be determined, there is assumed to be room.
     */
    fun hasRoomFor(file: FileHandle, needed: Long, reclaimable: Long = 0L): Boolean {
        var directory: File? = file.file().absoluteFile.parentFile
        while (directory != null && !directory.exists()) directory = directory.parentFile
        val usable = directory?.usableSpace ?: return true
        return usable + reclaimable >= needed
    }
}
