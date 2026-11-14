package app.shiori

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.shiori.capture.FrameFiles
import app.shiori.hotkey.HotkeyService
import app.shiori.ocr.Box
import app.shiori.overlay.RegionHit
import app.shiori.overlay.hitTestRegions
import app.shiori.overlay.moveBox
import app.shiori.overlay.parseRegions
import app.shiori.overlay.removeRegion
import app.shiori.overlay.resizeBox
import app.shiori.overlay.serializeRegions
import app.shiori.ui.Sumi
import java.io.File

/**
 * Draws OCR boxes over the latest captured frame; stored per package (see [serializeRegions]).
 * Touch a box to select it (accent highlight) and drag to move it; drag a corner or edge of the selected box to
 * resize; drag on empty space to draw a new one. Delete removes only the selected box, Clear removes all.
 */
class RegionActivity : Activity() {
    private companion object { const val HANDLE_RADIUS_DP = 22f } // 44dp touch target per handle

    private class DragView(context: Context, val bmp: Bitmap, initial: List<Box>) : View(context) {
        var regions: List<Box> = initial // frame pixels
        var selected: Int? = null
        var onSelection: (() -> Unit)? = null
        private var drawing: RectF? = null
        private var sx = 0f
        private var sy = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var hit: RegionHit? = null
        private var orig: Box? = null
        private val d = resources.displayMetrics.density
        private val fill = Paint().apply { color = Color.argb(30, 0xE9, 0xEC, 0xF2) }
        private val fillSel = Paint().apply { color = Color.argb(64, 0x8D, 0xB8, 0xFF) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Sumi.SECONDARY; style = Paint.Style.STROKE; strokeWidth = 2 * d
        }
        private val strokeSel = Paint(stroke).apply { color = Sumi.ACCENT; strokeWidth = 4 * d }
        private val strokeNew = Paint(stroke).apply { color = Sumi.ACCENT }
        private val handleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Sumi.INK }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Sumi.ACCENT; style = Paint.Style.STROKE; strokeWidth = 2 * d
        }

        init { setBackgroundColor(Sumi.INK) }

        private fun scale() = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)

        private fun viewRect(b: Box, k: Float) = RectF(b.left * k, b.top * k, b.right * k, b.bottom * k)

        private fun notifySelection() { onSelection?.invoke(); invalidate() }

        fun deleteSelected() {
            regions = removeRegion(regions, selected)
            selected = null
            notifySelection()
        }

        fun clearAll() {
            regions = emptyList(); selected = null; drawing = null; hit = null
            notifySelection()
        }

        override fun onDraw(c: Canvas) {
            val k = scale()
            c.drawBitmap(bmp, null, RectF(0f, 0f, bmp.width * k, bmp.height * k), null)
            regions.forEachIndexed { i, b ->
                val r = viewRect(b, k)
                val sel = i == selected
                c.drawRect(r, if (sel) fillSel else fill); c.drawRect(r, if (sel) strokeSel else stroke)
            }
            drawing?.let {
                val r = RectF(it.left * k, it.top * k, it.right * k, it.bottom * k)
                c.drawRect(r, fillSel); c.drawRect(r, strokeNew)
            }
            selected?.let {
                val r = viewRect(regions[it], k)
                // 14dp dots; the touch target is 44dp (HANDLE_RADIUS_DP).
                for (px in floatArrayOf(r.left, r.right)) for (py in floatArrayOf(r.top, r.bottom)) {
                    c.drawCircle(px, py, 7 * d, handleBg); c.drawCircle(px, py, 7 * d, handleRing)
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val k = scale()
            val fw = bmp.width.toFloat(); val fh = bmp.height.toFloat()
            val x = (e.x / k).coerceIn(0f, fw)
            val y = (e.y / k).coerceIn(0f, fh)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; sx = x; sy = y; moved = false
                    hit = hitTestRegions(regions, selected, x, y, HANDLE_RADIUS_DP * d / k)
                    orig = hit?.let { regions[it.index] }
                    // Touching a box selects it at once, so it highlights and Delete is armed.
                    hit?.let { if (selected != it.index) { selected = it.index; notifySelection() } }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 10 * d) moved = true
                    if (moved) {
                        val h = hit; val o = orig
                        if (h != null && o != null) {
                            val nb = if (h.edges == 0) moveBox(o, x - sx, y - sy, fw, fh)
                            else resizeBox(o, h.edges, x, y, fw, fh)
                            regions = regions.toMutableList().also { it[h.index] = nb }
                        } else drawing = RectF(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val box = drawing
                    drawing = null
                    if (hit == null) {
                        if (moved && box != null && box.width() > 8 && box.height() > 8) {
                            regions = regions + Box(box.left, box.top, box.right, box.bottom)
                            selected = regions.lastIndex
                        } else if (!moved) selected = null // tap on empty space deselects
                    }
                    hit = null; orig = null
                    notifySelection()
                }
                MotionEvent.ACTION_CANCEL -> {
                    drawing = null
                    val h = hit; val o = orig
                    if (h != null && o != null) regions = regions.toMutableList().also { it[h.index] = o }
                    hit = null; orig = null
                }
            }
            invalidate()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Sumi.INK))
        val prefs = getSharedPreferences(HotkeyService.PREFS, Context.MODE_PRIVATE)
        // The in-memory frame wins; otherwise the newest saved PNG of the last saved package.
        val mem = (application as ShioriApp).latestFrame
        val pkg = if (mem != null) mem.pkg else prefs.getString("last_frame_package", null)
        val bmp = if (mem != null) mem.bitmap else
            FrameFiles.newest(File(getExternalFilesDir(null), "frames"))?.let { BitmapFactory.decodeFile(it.path) }
        if (pkg == null || bmp == null) { finish(); return }
        val key = "region:$pkg"
        val drag = DragView(this, bmp, parseRegions(prefs.getString(key, null)))
        val dp = { v: Int -> Math.round(Sumi.dp(this, v.toFloat())) }

        val delete = Sumi.button(this, "Delete", Sumi.Style.OUTLINED, heightDp = 48) { drag.deleteSelected() }
        val clear = Sumi.button(this, "Clear", Sumi.Style.OUTLINED, heightDp = 48) { drag.clearAll() }
        val save = Sumi.button(this, "Save", Sumi.Style.FILLED, heightDp = 48) {
            if (drag.regions.isEmpty()) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, serializeRegions(drag.regions)).apply()
            finish()
        }
        // Delete is always on screen; dimmed until a box is selected.
        val refresh = {
            val on = drag.selected != null
            delete.alpha = if (on) 1f else 0.4f
            delete.isEnabled = on
            delete.setTextColor(if (on) Sumi.ACCENT else Sumi.TEXT)
            val any = drag.regions.isNotEmpty()
            clear.alpha = if (any) 1f else 0.4f
            clear.isEnabled = any
        }
        drag.onSelection = refresh
        refresh()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Sumi.PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun lp(first: Boolean) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { if (!first) leftMargin = dp(8) }
        bar.addView(delete, lp(true))
        bar.addView(clear, lp(false))
        bar.addView(save, lp(false))
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Sumi.INK)
            addView(drag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
