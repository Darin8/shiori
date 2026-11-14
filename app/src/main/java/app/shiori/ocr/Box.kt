package app.shiori.ocr

import android.graphics.RectF

/** Pure-JVM rectangle in frame pixel coordinates (testable without Android stubs). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Edges inclusive. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Distance from the point to the box; 0 when inside. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

fun Box.toRectF(): RectF = RectF(left, top, right, bottom)
fun RectF.toBox(): Box = Box(left, top, right, bottom)
