package app.shiori.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import app.shiori.ocr.OcrLine
import app.shiori.ui.Sumi
import app.shiori.ocr.toRectF
import app.shiori.ocr.Box as FrameBox

/**
 * The invisible tap layer over the running game, built so that touches outside recognised text always reach the
 * window below (game, launcher, recents):
 *  - one full-screen NOT_TOUCHABLE window that only draws the highlight;
 *  - one small touchable window per OCR line (line box + slop) that hit-tests taps inside it;
 *  - the popup in its own window, touchable only within its bounds; a tap elsewhere arrives as ACTION_OUTSIDE.
 * All windows are accessibility overlays and never focusable, so the gamepad keeps going to the game.
 * Line windows are reused across refreshes; only changed ones are laid out again. Coordinates are frame pixels.
 */
class TapLayerOverlay(
    private val context: Context,
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
    private val log: (String, Throwable) -> Unit = { m, t -> Log.w("Shiori", m, t) },
) {
    private var highlightView: HighlightView? = null
    private val lineWindows = ArrayList<LineWindow>()
    private var popupHost: PopupHost? = null
    private var lastLines: List<OcrLine> = emptyList()
    private var frameW = 0
    private var frameH = 0

    private class LineWindow(val view: LineView, var rect: WinRect, val lp: WindowManager.LayoutParams)

    private inner class HighlightView : View(context) {
        var highlight: List<RectF> = emptyList()
        /** Popup leader line in screen pixels (null when no popup). */
        var leader: Leader? = null
        private val dp = context.resources.displayMetrics.density
        override fun onDraw(c: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val d = display()
            val m = FrameMap(frameW, frameH, d.width(), d.height(), loc[0].toFloat(), loc[1].toFloat())
            if (highlight.isNotEmpty()) {
                val boxes = highlight.map { m.toView(FrameBox(it.left, it.top, it.right, it.bottom)) }
                val union = RectF(
                    boxes.minOf { it.left }, boxes.minOf { it.top },
                    boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
                )
                drawWordHighlight(c, union, dp)
            }
            leader?.let { drawLeader(c, Leader(it.x1 - loc[0], it.y1 - loc[1], it.x2 - loc[0], it.y2 - loc[1]), dp) }
        }
    }

    private inner class LineView : View(context) {
        var index = 0
        var line: OcrLine? = null
        var onTap: ((Hit) -> Unit)? = null
        var onOutside: (() -> Unit)? = null

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val l = line ?: return true
                    val m = screenMap()
                    val hit = hitTest(listOf(l), m.toFrameX(e.rawX), m.toFrameY(e.rawY))
                    if (hit != null) onTap?.invoke(Hit(index, hit.charIndex)) else onOutside?.invoke()
                    return true
                }
            }
            return false
        }
    }

    private class PopupHost(context: Context, var onOutside: () -> Unit) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    // The accessibility service can be unbound (BadTokenException, IllegalStateException) or a view already gone
    // (IllegalArgumentException) while we are mid-update; none of that may crash the service.
    private fun add(v: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(v, lp); true
    } catch (x: Exception) {
        if (!isWindowFailure(x)) throw x
        log("addView failed", x); false
    }

    private fun updateLayout(v: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, lp) } catch (x: Exception) {
            if (!isWindowFailure(x)) throw x
            log("updateViewLayout failed", x)
        }
    }

    private fun remove(v: View) {
        try { wm.removeView(v) } catch (x: Exception) {
            if (!isWindowFailure(x)) throw x
            log("removeView failed", x)
        }
    }

    private fun display() = wm.maximumWindowMetrics.bounds

    /** Frame to screen pixels (window origin 0,0, used for window rectangles). */
    private fun screenMap(): FrameMap {
        val d = display()
        return FrameMap(frameW, frameH, d.width(), d.height())
    }

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Swaps the line windows; an empty list removes every window (touches go to whatever is underneath). */
    fun update(frameW: Int, frameH: Int, lines: List<OcrLine>, onOutside: () -> Unit, onTap: (Hit) -> Unit) {
        if (lines.isEmpty()) {
            dismiss()
            return
        }
        this.frameW = frameW
        this.frameH = frameH
        lastLines = lines
        if (highlightView == null) {
            val v = HighlightView()
            val lp = params(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            ).apply { x = 0; y = 0 }
            if (!add(v, lp)) return
            highlightView = v
        }
        val d = display()
        val map = screenMap()
        val rects = lines.map { lineWindowRect(it.box, map, SLOP_FRAME_PX, d.width(), d.height()) }
        // Lines without a usable window (fully off screen) are skipped; indices stay those of [lines].
        val wanted = rects.mapIndexedNotNull { i, r -> r?.let { i to it } }
        while (lineWindows.size > wanted.size) removeLast()
        wanted.forEachIndexed { slot, (lineIndex, rect) ->
            val existing = lineWindows.getOrNull(slot)
            if (existing == null) {
                val v = LineView()
                val lp = params(
                    rect.width, rect.height,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                ).apply { x = rect.left; y = rect.top }
                bind(v, lineIndex, lines[lineIndex], onOutside, onTap)
                if (add(v, lp)) lineWindows.add(LineWindow(v, rect, lp))
            } else {
                bind(existing.view, lineIndex, lines[lineIndex], onOutside, onTap)
                if (existing.rect != rect) {
                    existing.rect = rect
                    existing.lp.x = rect.left
                    existing.lp.y = rect.top
                    existing.lp.width = rect.width
                    existing.lp.height = rect.height
                    updateLayout(existing.view, existing.lp)
                }
            }
        }
        highlightView?.invalidate()
    }

    private fun bind(v: LineView, index: Int, line: OcrLine, onOutside: () -> Unit, onTap: (Hit) -> Unit) {
        v.index = index
        v.line = line
        v.onTap = onTap
        v.onOutside = onOutside
    }

    private fun removeLast() {
        val w = lineWindows.removeAt(lineWindows.size - 1)
        remove(w.view)
    }

    /** Highlights chars [hit.charIndex, hit.charIndex + length) of the hit line. */
    fun highlight(hit: Hit, length: Int) {
        val line = lastLines.getOrNull(hit.lineIndex) ?: return
        val end = (hit.charIndex + length).coerceAtMost(line.chars.size)
        highlightView?.highlight = (hit.charIndex until end).map { line.chars[it].box.toRectF() }
        highlightView?.invalidate()
    }

    /**
     * Shows [view] in its own window next to [near] (frame pixels): below, above, right or left, clamped on screen.
     * A touch anywhere outside the window calls [onOutside].
     */
    fun showPopup(view: View, near: RectF, onOutside: () -> Unit) {
        if (highlightView == null) return
        removePopupWindow()
        val d = display()
        val sw = d.width()
        val sh = d.height()
        val a = screenMap().toView(FrameBox(near.left, near.top, near.right, near.bottom))
        val word = Box(a.left, a.top, a.right, a.bottom)
        val host = PopupHost(context, onOutside)
        host.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST),
        )
        val gap = (LEADER_GAP_DP * context.resources.displayMetrics.density).toInt()
        val text = textBlock()
        val limiter = view as? HeightLimited
        fun place(w: Int, h: Int): PopupPlacement =
            if (text == null) PopupPlacement(placePopup(sw, sh, word, w, h, gap = gap), null)
            else placePopupClear(sw, sh, word, text, w, h, gap = gap)
        var placement = place(view.measuredWidth, view.measuredHeight)
        if (placement.maxHeight != null && limiter != null) {
            // Too tall for the free band: the glossary scrolls inside it rather than covering the text.
            limiter.limitHeight(placement.maxHeight)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST),
            )
            placement = place(view.measuredWidth, view.measuredHeight)
        }
        val pos = placement.pos
        val lp = params(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply { x = pos.left; y = pos.top }
        if (!add(host, lp)) { host.removeAllViews(); return }
        popupHost = host
        setLeader(word, pos, view.measuredWidth, view.measuredHeight)
        // The card changes height when stepping between entries; keep it on screen.
        view.addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot && popupHost === host) {
                val p = place(v.width, v.height).pos
                if (p.left != lp.x || p.top != lp.y) {
                    lp.x = p.left
                    lp.y = p.top
                    updateLayout(host, lp)
                    setLeader(word, p, v.width, v.height)
                }
            }
        }
    }

    /** Bounding union of every OCR line in screen pixels, or null when there are none. */
    private fun textBlock(): Box? {
        if (lastLines.isEmpty()) return null
        val m = screenMap()
        val boxes = lastLines.map { m.toView(FrameBox(it.box.left, it.box.top, it.box.right, it.box.bottom)) }
        return Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    /** The card sits inside the popup window, inset by the shadow padding; the leader runs from the card edge. */
    private fun setLeader(word: Box, pos: PopupPos, w: Int, h: Int) {
        val d = context.resources.displayMetrics.density
        val pad = EntryPopup.SHADOW_PAD_DP * d
        val card = Box(pos.left + pad, pos.top + pad, pos.left + w - pad, pos.top + h - pad)
        highlightView?.leader = leaderLine(card, word, 14f * d)
        highlightView?.invalidate()
    }

    private fun removePopupWindow() {
        val h = popupHost ?: return
        popupHost = null
        remove(h)
        h.removeAllViews()
    }

    /** Removes the popup and the highlight; the tap windows stay. */
    fun closePopup() {
        removePopupWindow()
        highlightView?.highlight = emptyList()
        highlightView?.leader = null
        highlightView?.invalidate()
    }

    /** Removes every window this overlay owns. Idempotent. */
    fun dismiss() {
        removePopupWindow()
        while (lineWindows.isNotEmpty()) removeLast()
        highlightView?.let { remove(it) }
        highlightView = null
        lastLines = emptyList()
    }

    private fun isWindowFailure(x: Exception) =
        x is WindowManager.BadTokenException || x is IllegalStateException || x is IllegalArgumentException

    private companion object {
        const val SLOP_FRAME_PX = 12f
        /** Popup window edge to word; with the 8dp shadow padding the card sits 17dp away. */
        const val LEADER_GAP_DP = 9f
    }
}

private const val ACCENT_R = 0x8D
private const val ACCENT_G = 0xB8
private const val ACCENT_B = 0xFF

private fun accentPaint(style: Paint.Style, alpha: Int, width: Float = 0f) = Paint().apply {
    color = Color.argb(alpha, ACCENT_R, ACCENT_G, ACCENT_B); this.style = style; strokeWidth = width; isAntiAlias = true
}

/**
 * The signature highlight: [word] (view pixels, the matched word's union box) gets a soft accent fill (~14%), a 2dp
 * accent stroke inside its edge and a faint two-ring outer glow. Static, no animation. Padding and the +2dp right
 * shift are tuned on device (detector char boxes sit left of the glyphs).
 */
internal fun drawWordHighlight(c: Canvas, word: RectF, dp: Float) {
    val rect = RectF(word)
    rect.inset(-3 * dp, -2 * dp)
    rect.offset(2 * dp, 0f)
    for ((grow, alpha) in listOf(5f to 14, 2.5f to 30)) {
        val g = RectF(rect).apply { inset(-grow * dp, -grow * dp) }
        c.drawRoundRect(g, (6 + grow) * dp, (6 + grow) * dp, accentPaint(Paint.Style.STROKE, alpha, 2.5f * dp))
    }
    c.drawRoundRect(rect, 6 * dp, 6 * dp, accentPaint(Paint.Style.FILL, 36))
    rect.inset(dp, dp)
    c.drawRoundRect(rect, 5 * dp, 5 * dp, accentPaint(Paint.Style.STROKE, 255, 2 * dp))
}

/** Leader from the card to the word: accent line ending in a small filled dot (same +2dp shift as the highlight). */
internal fun drawLeader(c: Canvas, l: Leader, dp: Float) {
    val dx = 2 * dp
    c.drawLine(l.x1 + dx, l.y1, l.x2 + dx, l.y2, accentPaint(Paint.Style.STROKE, 255, 2 * dp))
    c.drawCircle(l.x2 + dx, l.y2, 3.5f * dp, accentPaint(Paint.Style.FILL, 255))
}
