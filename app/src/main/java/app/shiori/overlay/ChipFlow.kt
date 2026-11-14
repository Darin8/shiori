package app.shiori.overlay

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Wrapping row of small views (tag chips). */
class ChipFlow(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w)
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { y += rowH + vGap; x = 0; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
            widest = maxOf(widest, x - hGap)
        }
        val width = if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) maxW else widest
        setMeasuredDimension(width, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        // Group children into rows first so each can be centred on its row's height.
        val rows = ArrayList<MutableList<View>>()
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (rows.isEmpty() || (x > 0 && x + c.measuredWidth > maxW)) { rows.add(ArrayList()); x = 0 }
            rows.last().add(c)
            x += c.measuredWidth + hGap
        }
        var y = 0
        for (row in rows) {
            val rowH = row.maxOf { it.measuredHeight }
            var cx = 0
            for (c in row) {
                val dy = (rowH - c.measuredHeight) / 2
                c.layout(cx, y + dy, cx + c.measuredWidth, y + dy + c.measuredHeight)
                cx += c.measuredWidth + hGap
            }
            y += rowH + vGap
        }
    }
}
