package app.shiori.overlay

import app.shiori.ocr.Box
import app.shiori.ocr.OcrLine

/** Pref value: boxes as "l,t,r,b" joined by ';'. A legacy single "l,t,r,b" parses as one box. */
fun parseRegions(s: String?): List<Box> =
    s?.split(';')?.mapNotNull { part ->
        part.split(',').mapNotNull { it.trim().toFloatOrNull() }
            .takeIf { it.size == 4 }?.let { Box(it[0], it[1], it[2], it[3]) }
    } ?: emptyList()

fun serializeRegions(regions: List<Box>): String =
    regions.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

/** Index of the topmost (last drawn) region containing the point, or null. */
fun regionAt(regions: List<Box>, x: Float, y: Float): Int? =
    regions.indices.lastOrNull { regions[it].contains(x, y) }

/** Empty list keeps every line; otherwise a line is kept when its box centre is inside any region. */
fun linesInRegions(lines: List<OcrLine>, regions: List<Box>): List<OcrLine> =
    if (regions.isEmpty()) lines
    else lines.filter { l -> regions.any { it.contains(l.box.centerX, l.box.centerY) } }

/** "Whole screen" or "N regions". */
fun regionLabel(count: Int): String = when (count) {
    0 -> "Whole screen"
    1 -> "1 region"
    else -> "$count regions"
}

/** Edge bits for [RegionHit.edges]; 0 means "move the whole box". */
const val EDGE_L = 1
const val EDGE_T = 2
const val EDGE_R = 4
const val EDGE_B = 8

/** What a touch at a point grabs: the region [index] and which [edges] it resizes (0 = move). */
data class RegionHit(val index: Int, val edges: Int)

/**
 * Handles of the selected box win (corners and edges, within [slop] frame pixels, capped so a small box
 * keeps a body to drag), then the topmost box body. Null on empty space (draw a new box).
 */
fun hitTestRegions(regions: List<Box>, selected: Int?, x: Float, y: Float, slop: Float): RegionHit? {
    selected?.takeIf { it in regions.indices }?.let { i ->
        val b = regions[i]
        val s = minOf(slop, minOf(b.width, b.height) / 3f)
        val inY = y >= b.top - s && y <= b.bottom + s
        val inX = x >= b.left - s && x <= b.right + s
        var m = 0
        if (inY && Math.abs(x - b.left) <= s) m = m or EDGE_L
        if (inY && Math.abs(x - b.right) <= s) m = m or EDGE_R
        if (inX && Math.abs(y - b.top) <= s) m = m or EDGE_T
        if (inX && Math.abs(y - b.bottom) <= s) m = m or EDGE_B
        if (m != 0) return RegionHit(i, m)
    }
    return regionAt(regions, x, y)?.let { RegionHit(it, 0) }
}

/** Moves [b] by (dx, dy), keeping it inside the frame (size unchanged). */
fun moveBox(b: Box, dx: Float, dy: Float, frameW: Float, frameH: Float): Box {
    val w = minOf(b.width, frameW)
    val h = minOf(b.height, frameH)
    val l = (b.left + dx).coerceIn(0f, frameW - w)
    val t = (b.top + dy).coerceIn(0f, frameH - h)
    return Box(l, t, l + w, t + h)
}

/** Drags the [edges] of [b] to (x, y), clamped to the frame and never smaller than [minSize]. */
fun resizeBox(b: Box, edges: Int, x: Float, y: Float, frameW: Float, frameH: Float, minSize: Float = 8f): Box {
    var l = b.left; var t = b.top; var r = b.right; var bt = b.bottom
    if (edges and EDGE_L != 0) l = x.coerceIn(0f, r - minSize)
    if (edges and EDGE_R != 0) r = x.coerceIn(l + minSize, frameW)
    if (edges and EDGE_T != 0) t = y.coerceIn(0f, bt - minSize)
    if (edges and EDGE_B != 0) bt = y.coerceIn(t + minSize, frameH)
    return Box(l, t, r, bt)
}

/** Selection after removing [index]: nothing selected. Returns the remaining list. */
fun removeRegion(regions: List<Box>, index: Int?): List<Box> =
    if (index == null || index !in regions.indices) regions else regions.filterIndexed { i, _ -> i != index }
