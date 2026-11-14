package app.shiori.ocr

import android.content.Context
import android.graphics.Bitmap

/** One recognised character with its box in frame pixel coordinates. */
data class OcrChar(val text: String, val box: Box)

/**
 * One recognised line. [chars] always has per-character boxes in frame pixel coordinates.
 * Invariant: `text == chars.joinToString("") { it.text }`, so a charIndex into [chars] also indexes [text].
 */
data class OcrLine(val text: String, val box: Box, val chars: List<OcrChar>, val vertical: Boolean)

/** How a pass reads: [live] passes settle changing text, [regions] (empty: whole screen) limit what is read. */
class ReadContext(val live: Boolean, val regions: List<Box>)

fun interface OcrEngine {
    suspend fun read(frame: Bitmap): List<OcrLine>

    /** Pass entry used by the session; engines without a per-box cache just read everything. */
    suspend fun read(frame: Bitmap, ctx: ReadContext): List<OcrLine> = read(frame)

    /** True while the engine waits for changing text to settle: the next tick must not be skipped. */
    val pending: Boolean get() = false

    /** Timing and cache counts of the latest [read] with a context, or null. */
    val lastStats: PassStats? get() = null

    /** Drops cached lines (Stop, game change). */
    fun reset() {}
}

/**
 * Engine used for every capture. MeikiOCR: its character boxes sit on the glyphs. ML Kit's symbol boxes are only
 * spread across each recognised word, so on proportional game fonts they drift (up to ~30 px, mostly to the right).
 */
fun defaultOcrEngine(context: Context): OcrEngine = MeikiOcrEngine(context)

/** Levenshtein distance divided by expected.length. Empty expected: 0.0 if actual is empty, else 1.0. */
fun charErrorRate(expected: String, actual: String): Double {
    if (expected.isEmpty()) return if (actual.isEmpty()) 0.0 else 1.0
    var prev = IntArray(actual.length + 1) { it }
    var cur = IntArray(actual.length + 1)
    for (i in 1..expected.length) {
        cur[0] = i
        for (j in 1..actual.length) {
            val sub = prev[j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1
            cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[actual.length].toDouble() / expected.length
}

/** For engines that only give a line box: spread the box evenly over the characters. */
internal fun spreadChars(text: String, box: Box, vertical: Boolean): List<OcrChar> {
    val cps = text.codePoints().toArray()
    if (cps.isEmpty()) return emptyList()
    val n = cps.size
    return cps.mapIndexed { i, cp ->
        val r = if (vertical) {
            val h = box.height / n
            Box(box.left, box.top + h * i, box.right, box.top + h * (i + 1))
        } else {
            val w = box.width / n
            Box(box.left + w * i, box.top, box.left + w * (i + 1), box.bottom)
        }
        OcrChar(String(Character.toChars(cp)), r)
    }
}
