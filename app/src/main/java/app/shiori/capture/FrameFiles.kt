package app.shiori.capture

import java.io.File

/** The latest captured frame, kept in memory only, with the package it came from. */
class CapturedFrame(val bitmap: android.graphics.Bitmap, val pkg: String?)

/** Saved hotkey frames in one directory: newest lookup and pruning. Blocking I/O, so call off the main thread. */
object FrameFiles {
    const val KEEP = 50

    private fun pngs(dir: File): List<File> = dir.listFiles { f -> f.extension == "png" }?.toList() ?: emptyList()

    fun newest(dir: File): File? = pngs(dir).maxByOrNull { it.lastModified() }

    fun any(dir: File): Boolean = pngs(dir).isNotEmpty()

    /** Deletes all but the newest [keep] PNGs. Returns how many were deleted. */
    fun prune(dir: File, keep: Int = KEEP): Int {
        val newestFirst = compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }
        return pngs(dir).sortedWith(newestFirst).drop(keep).count { it.delete() }
    }
}

/** The region button works with a frame in memory or a saved one ([onDisk] null: not listed yet). */
fun frameAvailable(inMemory: Boolean, onDisk: Boolean?): Boolean = inMemory || onDisk == true
