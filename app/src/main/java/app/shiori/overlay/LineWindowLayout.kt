package app.shiori.overlay

import app.shiori.ocr.Box as FrameBox
import kotlin.math.ceil
import kotlin.math.floor

/** A window rectangle in screen pixels. */
data class WinRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Screen rectangle of the touchable window for one OCR line: the line box grown by [slopFrame] frame pixels,
 * mapped to the screen, rounded outwards and clamped to the [screenW] x [screenH] display. Null when nothing is left.
 */
fun lineWindowRect(box: FrameBox, map: FrameMap, slopFrame: Float, screenW: Int, screenH: Int): WinRect? {
    val l = floor(map.toViewX(box.left - slopFrame)).toInt().coerceIn(0, screenW)
    val t = floor(map.toViewY(box.top - slopFrame)).toInt().coerceIn(0, screenH)
    val r = ceil(map.toViewX(box.right + slopFrame)).toInt().coerceIn(0, screenW)
    val b = ceil(map.toViewY(box.bottom + slopFrame)).toInt().coerceIn(0, screenH)
    return if (r > l && b > t) WinRect(l, t, r, b) else null
}
