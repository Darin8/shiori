package app.shiori.overlay

import app.shiori.ocr.Box
import app.shiori.ocr.OcrLine

data class Hit(val lineIndex: Int, val charIndex: Int)

/**
 * Inside a char box: that char. Otherwise the nearest char whose box, grown by [slopPx], contains
 * the point (nearest by distance to the box). Otherwise null.
 */
fun hitTest(lines: List<OcrLine>, x: Float, y: Float, slopPx: Float = 12f): Hit? {
    var best: Hit? = null
    var bestDist = Float.MAX_VALUE
    lines.forEachIndexed { li, line ->
        line.chars.forEachIndexed { ci, c ->
            if (c.box.contains(x, y)) return Hit(li, ci)
            val d = c.box.distanceTo(x, y)
            val grown = x >= c.box.left - slopPx && x <= c.box.right + slopPx &&
                y >= c.box.top - slopPx && y <= c.box.bottom + slopPx
            if (grown && d < bestDist) {
                bestDist = d
                best = Hit(li, ci)
            }
        }
    }
    return best
}
