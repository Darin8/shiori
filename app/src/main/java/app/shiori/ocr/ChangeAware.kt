package app.shiori.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Thresholds for change-aware OCR passes (checked by ChangeAwareFramesTest). The 3D background moves and the
 * capture is re-encoded every tick, so unchanged text still shows a little noise.
 */
object ChangeTuning {
    /** Frame is reduced this much before fingerprinting (box average). */
    const val REDUCE = 4

    /** Region gate: thumbnail is this many columns wide (rows follow the region aspect). */
    const val REGION_COLS = 32
    const val REGION_MAX_ROWS = 48

    /** Region gate: mean absolute luma difference (0..255) between ticks above which the region counts as changed. */
    const val REGION_MAD = 0.4f

    /** Line signature grid. */
    const val SIG_COLS = 32
    const val SIG_ROWS = 4

    /** Neighbouring cells must differ by more than this (luma 0..255) to count as an edge. */
    const val SIG_DEAD_ZONE = 6

    /** Two signatures match when at most this fraction of their cell pairs disagree. */
    const val SIG_MAX_DIFF = 0.06f

    /** A new box matches a cached or pending one when their IoU is at least this. */
    const val MATCH_IOU = 0.7f

    /** A previous-layer line stays for a not yet settled box when this much of the smaller box overlaps. */
    const val CARRY_OVERLAP = 0.5f

    const val CACHE_SIZE = 64

    /** A box that keeps changing (moving background) is read anyway after this many holds, so text never starves. */
    const val MAX_DEFER = 2

    /** Looser signature limit for a cached box that was re-read with the same text (its signature is just noisy). */
    const val NOISY_MAX_DIFF = 0.14f
}

/** Luma of the frame at reduced size. Box coordinates are in full-frame pixels. */
class LumaFrame(val srcWidth: Int, val srcHeight: Int, val width: Int, val height: Int, val luma: ByteArray) {
    private val sx = width.toFloat() / srcWidth
    private val sy = height.toFloat() / srcHeight

    /** Average luma of [box] resampled to [cols] x [rows]. Fractional cell edges make a sub-pixel move change little. */
    fun thumb(box: Box, cols: Int, rows: Int): ByteArray {
        val x1 = (box.left * sx).coerceIn(0f, width - 1f)
        val x2 = (box.right * sx).coerceIn(x1 + 1f, width.toFloat())
        val y1 = (box.top * sy).coerceIn(0f, height - 1f)
        val y2 = (box.bottom * sy).coerceIn(y1 + 1f, height.toFloat())
        val out = ByteArray(cols * rows)
        for (r in 0 until rows) {
            val ya = y1 + (y2 - y1) * r / rows
            val yb = y1 + (y2 - y1) * (r + 1) / rows
            for (c in 0 until cols) {
                val xa = x1 + (x2 - x1) * c / cols
                val xb = x1 + (x2 - x1) * (c + 1) / cols
                var sum = 0f
                var wsum = 0f
                for (y in ya.toInt() until min(height, kotlin.math.ceil(yb).toInt())) {
                    val wy = min(yb, y + 1f) - max(ya, y.toFloat())
                    if (wy <= 0f) continue
                    for (x in xa.toInt() until min(width, kotlin.math.ceil(xb).toInt())) {
                        val w = wy * (min(xb, x + 1f) - max(xa, x.toFloat()))
                        if (w <= 0f) continue
                        sum += w * (luma[y * width + x].toInt() and 0xFF)
                        wsum += w
                    }
                }
                out[r * cols + c] = if (wsum > 0f) (sum / wsum + 0.5f).toInt().toByte() else 0
            }
        }
        return out
    }

    companion object {
        /** Pure-JVM reduction of ARGB pixels: [reduce] x [reduce] box average of luma. */
        fun fromArgb(w: Int, h: Int, px: IntArray, reduce: Int = ChangeTuning.REDUCE): LumaFrame {
            val rw = max(1, w / reduce)
            val rh = max(1, h / reduce)
            val out = ByteArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                var sum = 0
                var n = 0
                for (dy in 0 until reduce) for (dx in 0 until reduce) {
                    val srcX = x * reduce + dx
                    val srcY = y * reduce + dy
                    if (srcX >= w || srcY >= h) continue
                    sum += lumaOf(px[srcY * w + srcX]); n++
                }
                out[y * rw + x] = (sum / max(1, n)).toByte()
            }
            return LumaFrame(w, h, rw, rh, out)
        }

        fun lumaOf(argb: Int): Int =
            (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29) shr 8
    }
}

/** Mean absolute difference of two equally sized thumbnails; [Float.MAX_VALUE] when the sizes differ. */
fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
    if (a.size != b.size || a.isEmpty()) return Float.MAX_VALUE
    var sum = 0L
    for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
    return sum.toFloat() / a.size
}

/**
 * Edge signature of a line box: resampled to SIG_COLS x SIG_ROWS, each horizontal neighbour pair becomes -1, 0 or +1.
 * Brightness shifts and mild noise leave it alone; different glyphs do not.
 */
class LineSig(val cells: ByteArray) {
    /** Fraction of cell pairs that differ. */
    fun diff(o: LineSig): Float {
        if (cells.size != o.cells.size) return 1f
        var n = 0
        for (i in cells.indices) if (cells[i] != o.cells[i]) n++
        return n.toFloat() / cells.size
    }

    fun matches(o: LineSig): Boolean = diff(o) <= ChangeTuning.SIG_MAX_DIFF

    companion object {
        fun of(frame: LumaFrame, box: Box): LineSig {
            val cols = ChangeTuning.SIG_COLS
            val rows = ChangeTuning.SIG_ROWS
            val t = frame.thumb(box, cols, rows)
            val out = ByteArray((cols - 1) * rows)
            for (r in 0 until rows) for (c in 0 until cols - 1) {
                val d = (t[r * cols + c + 1].toInt() and 0xFF) - (t[r * cols + c].toInt() and 0xFF)
                out[r * (cols - 1) + c] = when {
                    d > ChangeTuning.SIG_DEAD_ZONE -> 1
                    d < -ChangeTuning.SIG_DEAD_ZONE -> -1
                    else -> 0
                }
            }
            return LineSig(out)
        }
    }
}

fun iou(a: Box, b: Box): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0f || ih <= 0f) return 0f
    val inter = iw * ih
    val union = a.width * a.height + b.width * b.height - inter
    return if (union <= 0f) 0f else inter / union
}

/** Intersection area over the smaller box's area. */
fun overlapOfSmaller(a: Box, b: Box): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0f || ih <= 0f) return 0f
    val small = min(a.width * a.height, b.width * b.height)
    return if (small <= 0f) 0f else iw * ih / small
}

private fun Box.shifted(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

fun OcrLine.shifted(dx: Float, dy: Float): OcrLine =
    if (dx == 0f && dy == 0f) this
    else OcrLine(text, box.shifted(dx, dy), chars.map { OcrChar(it.text, it.box.shifted(dx, dy)) }, vertical)

/** A detected text line box, before recognition. */
data class LineDet(val box: Box, val vertical: Boolean)

/** What a pass did, for the cost log. */
data class PassStats(val detectMs: Long, val recogMs: Long, val reused: Int, val recognized: Int)

/**
 * Per-box cache and settle state for change-aware passes (signatures and recognition are injected).
 * - A box matching a cached one (IoU and signature) reuses its line, shifted to the new position.
 * - Any other box is recognised only once it matched itself on the previous pass (the typewriter stopped).
 *   Until then the previous layer's lines stay over that area; brand-new boxes show nothing.
 * - [settle] false (hotkey) recognises every miss at once and ignores pending state.
 * [reset] drops everything (Stop, game change).
 */
class LineCache(private val capacity: Int = ChangeTuning.CACHE_SIZE) {
    private class Entry(var box: Box, var sig: LineSig, var line: OcrLine?, var noisy: Boolean = false)
    private class Pending(val box: Box, val sig: LineSig, val misses: Int)

    class Outcome(val lines: List<OcrLine>, val reused: Int, val recognized: Int, val deferred: Int)

    private val entries = ArrayList<Entry>() // most recently used last
    private var pending: List<Pending> = emptyList()
    private var lastLines: List<OcrLine> = emptyList()
    @Volatile private var generation = 0

    /** Some boxes were seen changing and wait for one more equal tick. */
    @get:Synchronized
    val hasPending: Boolean get() = pending.isNotEmpty()

    @Synchronized
    fun reset() {
        generation++
        entries.clear(); pending = emptyList(); lastLines = emptyList()
    }

    /**
     * [recognize] gets the boxes to read and returns one result per box (null: no text). Results are cached.
     * A [reset] while recognising discards this pass's cache updates.
     */
    suspend fun process(
        dets: List<LineDet>,
        sigOf: (LineDet) -> LineSig,
        settle: Boolean,
        recognize: suspend (List<LineDet>) -> List<OcrLine?>,
    ): Outcome {
        val gen = generation
        val sigs = dets.map(sigOf)
        val hit = arrayOfNulls<Entry>(dets.size)
        val hitLine = arrayOfNulls<OcrLine>(dets.size)
        val used = HashSet<Entry>()
        val snapshot: List<Entry>
        val pend: List<Pending>
        val prev: List<OcrLine>
        synchronized(this) { snapshot = ArrayList(entries); pend = pending; prev = lastLines }
        for (i in dets.indices) {
            var best: Entry? = null
            var bestDiff = Float.MAX_VALUE
            for (e in snapshot) {
                if (e in used || iou(e.box, dets[i].box) < ChangeTuning.MATCH_IOU) continue
                val d = e.sig.diff(sigs[i])
                val limit = if (e.noisy) ChangeTuning.NOISY_MAX_DIFF else ChangeTuning.SIG_MAX_DIFF
                if (d <= limit && d < bestDiff) { best = e; bestDiff = d }
            }
            if (best != null) {
                used += best
                hit[i] = best
                hitLine[i] = best.line?.shifted(dets[i].box.left - best.box.left, dets[i].box.top - best.box.top)
            }
        }
        val usedPending = HashSet<Pending>()
        val readIdx = ArrayList<Int>()
        val deferIdx = ArrayList<Int>()
        val deferMisses = HashMap<Int, Int>()
        for (i in dets.indices) {
            if (hit[i] != null) continue
            if (!settle) { readIdx += i; continue }
            val near = pend.filter { it !in usedPending && iou(it.box, dets[i].box) >= ChangeTuning.MATCH_IOU }
            val stable = near.firstOrNull { it.sig.matches(sigs[i]) }
            if (stable != null) { usedPending += stable; readIdx += i; continue }
            // Same place as a held-back box but still changing: read it after MAX_DEFER holds.
            val worn = near.maxByOrNull { it.misses }
            if (worn != null && worn.misses >= ChangeTuning.MAX_DEFER) { usedPending += worn; readIdx += i; continue }
            if (worn != null) usedPending += worn
            deferIdx += i
            deferMisses[i] = (worn?.misses ?: -1) + 1
        }
        val read = if (readIdx.isEmpty()) emptyList() else recognize(readIdx.map { dets[it] })
        val readLine = HashMap<Int, OcrLine?>()
        readIdx.forEachIndexed { k, i -> readLine[i] = read.getOrNull(k) }

        // A re-read that finds the text the cache already holds for that place: the cached signature is merely noisy.
        val staleOf = HashMap<Int, Entry>()
        for (i in readLine.keys) {
            val t = readLine[i]?.text ?: continue
            val e = snapshot.firstOrNull {
                it !in used && it.line?.text == t && iou(it.box, dets[i].box) >= ChangeTuning.MATCH_IOU
            } ?: continue
            used += e
            staleOf[i] = e
        }

        val raw = ArrayList<OcrLine>()
        for (i in dets.indices) {
            when {
                hit[i] != null -> hitLine[i]?.let { raw += it }
                i in readLine -> readLine[i]?.let { raw += it }
                else -> for (l in prev) {
                    if (overlapOfSmaller(l.box, dets[i].box) >= ChangeTuning.CARRY_OVERLAP &&
                        raw.none { overlapOfSmaller(it.box, l.box) >= ChangeTuning.CARRY_OVERLAP }
                    ) raw += l
                }
            }
        }
        val out = dedupe(raw)
        synchronized(this) {
            if (gen == generation) {
                for (i in dets.indices) {
                    val h = hit[i]
                    if (h != null) {
                        h.box = dets[i].box; h.sig = sigs[i]
                        entries.remove(h); entries.add(h)
                    } else if (i in staleOf) {
                        val e = staleOf.getValue(i)
                        e.box = dets[i].box; e.sig = sigs[i]; e.line = readLine[i]; e.noisy = true
                        entries.remove(e); entries.add(e)
                    } else if (i in readLine) {
                        entries.add(Entry(dets[i].box, sigs[i], readLine[i]))
                    }
                }
                while (entries.size > capacity) entries.removeAt(0)
                pending = deferIdx.map { Pending(dets[it].box, sigs[it], deferMisses.getValue(it)) }
                lastLines = out
            }
        }
        return Outcome(out, hit.count { it != null }, readIdx.size, deferIdx.size)
    }
}

/** Drops a line sitting (almost) on an earlier one with the same text; the detector can report furigana twice. */
internal fun dedupe(lines: List<OcrLine>): List<OcrLine> {
    val out = ArrayList<OcrLine>(lines.size)
    for (l in lines) if (out.none { iou(it.box, l.box) >= 0.8f && it.text == l.text }) out += l
    return out
}
