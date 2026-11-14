package app.shiori.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Headword with ruby-style furigana above each annotated segment. Wraps onto further lines when the width it is given
 * is too small (never clips); only an unbreakable segment wider than the width shrinks the text.
 */
class RubyView(context: Context) : View(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var segments: List<Pair<String, String?>> = emptyList()
    private var baseSize = 30f
    private var scale = 1f
    private var hasRuby = false
    private var lines: List<List<Pair<String, String?>>> = emptyList()

    fun setTypeface(tf: android.graphics.Typeface) {
        textPaint.typeface = tf
        rubyPaint.typeface = tf
        requestLayout()
        invalidate()
    }

    fun set(segments: List<Pair<String, String?>>, textSizePx: Float, color: Int, rubyColor: Int) {
        // Plain-text runs may break between any two characters; annotated segments stay whole.
        this.segments = segments.flatMap { s ->
            if (s.second == null && s.first.length > 1) s.first.map { it.toString() to null } else listOf(s)
        }
        baseSize = textSizePx
        hasRuby = segments.any { it.second != null }
        textPaint.color = color
        rubyPaint.color = rubyColor
        requestLayout()
        invalidate()
    }

    private fun apply(s: Float) {
        textPaint.textSize = baseSize * s
        rubyPaint.textSize = baseSize * s * 0.5f
    }

    private fun segWidth(seg: Pair<String, String?>): Float =
        maxOf(textPaint.measureText(seg.first), seg.second?.let { rubyPaint.measureText(it) } ?: 0f)

    private fun rubyBand(line: List<Pair<String, String?>>): Float {
        if (line.none { it.second != null }) return 0f
        val rm = rubyPaint.fontMetrics
        return rm.descent - rm.ascent - 0.06f * textPaint.textSize
    }

    private fun lineHeight(line: List<Pair<String, String?>>): Float {
        val tm = textPaint.fontMetrics
        return rubyBand(line) + tm.descent - tm.ascent - 0.1f * textPaint.textSize
    }

    private fun wrap(avail: Float): List<List<Pair<String, String?>>> {
        val out = ArrayList<List<Pair<String, String?>>>()
        var cur = ArrayList<Pair<String, String?>>()
        var x = 0f
        for (seg in segments) {
            val w = segWidth(seg)
            if (cur.isNotEmpty() && x + w > avail) { out.add(cur); cur = ArrayList(); x = 0f }
            cur.add(seg)
            x += w
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    override fun onMeasure(w: Int, h: Int) {
        val widthMode = MeasureSpec.getMode(w)
        val avail = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE.toFloat() else MeasureSpec.getSize(w).toFloat()
        apply(1f)
        val widest = segments.maxOfOrNull { segWidth(it) } ?: 0f
        scale = if (widest > avail && widest > 0f) avail / widest else 1f
        apply(scale)
        lines = wrap(avail)
        val widestLine = lines.maxOfOrNull { l -> l.sumOf { segWidth(it).toDouble() }.toFloat() } ?: 0f
        setMeasuredDimension(
            if (widthMode == MeasureSpec.EXACTLY) avail.toInt() else minOf(widestLine.toInt() + 1, avail.toInt()),
            (lines.sumOf { lineHeight(it).toDouble() }.toFloat()).toInt() + 1,
        )
    }

    override fun onDraw(c: Canvas) {
        apply(scale)
        val tm = textPaint.fontMetrics
        val rm = rubyPaint.fontMetrics
        var top = 0f
        lines.forEach { line ->
            val textBase = top + rubyBand(line) - tm.ascent
            var x = 0f
            for (seg in line) {
                val sw = segWidth(seg)
                val tw = textPaint.measureText(seg.first)
                c.drawText(seg.first, x + (sw - tw) / 2f, textBase, textPaint)
                seg.second?.let {
                    val rw = rubyPaint.measureText(it)
                    c.drawText(it, x + (sw - rw) / 2f, top - rm.ascent, rubyPaint)
                }
                x += sw
            }
            top += lineHeight(line)
        }
    }
}
