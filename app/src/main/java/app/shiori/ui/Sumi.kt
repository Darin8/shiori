package app.shiori.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.shiori.R

/** "Clean (Sumi glass)" design tokens and the few widgets built from them. */
object Sumi {
    val INK = Color.parseColor("#15171C")
    val PANEL = Color.parseColor("#1B1E25")
    val ROW = Color.parseColor("#22262F")
    val CONTROL = Color.parseColor("#252A34")
    val SEGMENT = Color.parseColor("#1F232B")
    val HAIR = Color.parseColor("#262B35")
    val OUTLINE = Color.parseColor("#343A46")
    val OUTLINE_STRONG = Color.parseColor("#3A414E")
    val TEXT = Color.parseColor("#E9ECF2")
    val SECONDARY = Color.parseColor("#9AA3B2")
    val META = Color.parseColor("#8A93A3")
    val FAINT = Color.parseColor("#6B7383")
    val ACCENT = Color.parseColor("#8DB8FF")
    val ON_ACCENT = Color.parseColor("#0E1420")
    val POSITIVE = Color.parseColor("#7FD1B9")
    val TRACK = Color.parseColor("#2C323D")

    fun font(ctx: Context, weight: Int = 400): Typeface =
        Typeface.create(ctx.resources.getFont(R.font.ibm_plex_sans_jp), weight, false)

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    /** [d] drawn [visibleDp] tall, centred in a [boxDp]-tall hit area (the hit area stays at least 40dp). */
    fun inset(ctx: Context, d: Drawable, boxDp: Float, visibleDp: Float): Drawable {
        val v = Math.max(0, Math.round(dp(ctx, (boxDp - visibleDp) / 2f)))
        return if (v == 0) d else android.graphics.drawable.InsetDrawable(d, 0, v, 0, v)
    }

    fun shape(fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    /** Surface that shows an accent ring while focused (gamepad D-pad) and a light ripple while pressed. */
    fun interactive(ctx: Context, fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): Drawable {
        val ring = Math.round(dp(ctx, 2f))
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, radiusPx, ACCENT, ring))
            addState(intArrayOf(), shape(fill, radiusPx, stroke, strokePx))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), states, null)
    }

    enum class Style { FILLED, OUTLINED, GHOST, CONTROL, ACCENT_OUTLINE }

    /** A text button; focusable, 44dp tall unless [heightDp] says otherwise. */
    fun button(
        ctx: Context, label: String, style: Style = Style.OUTLINED, heightDp: Int = 40, sp: Float = 14f,
        radiusDp: Float = 9f, padDp: Int = 14, visibleDp: Float = 34f, onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        typeface = font(ctx, if (style == Style.FILLED) 600 else 400)
        setTextColor(
            when (style) {
                Style.FILLED -> ON_ACCENT
                Style.GHOST -> SECONDARY
                Style.ACCENT_OUTLINE -> ACCENT
                else -> TEXT
            },
        )
        minHeight = Math.round(dp(ctx, heightDp.toFloat()))
        val sidePad = Math.round(dp(ctx, padDp.toFloat()))
        setPadding(sidePad, 0, sidePad, 0)
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        val r = dp(ctx, radiusDp)
        val one = Math.round(dp(ctx, 1f))
        background = when (style) {
            Style.FILLED -> interactive(ctx, ACCENT, r)
            Style.OUTLINED -> interactive(ctx, Color.TRANSPARENT, r, OUTLINE_STRONG, one)
            Style.GHOST -> interactive(ctx, Color.TRANSPARENT, r)
            Style.CONTROL -> interactive(ctx, CONTROL, r)
            Style.ACCENT_OUTLINE -> interactive(ctx, Color.TRANSPARENT, r, ACCENT, one)
        }
        if (visibleDp < heightDp) background = inset(ctx, background, heightDp.toFloat(), visibleDp)
        setPadding(sidePad, 0, sidePad, 0) // a drawable's own padding must not replace ours
        setOnClickListener { onClick() }
    }

    /** 40x24dp toggle: accent track when on, grey when off. */
    class Switch(context: Context) : View(context) {
        var onChange: ((Boolean) -> Unit)? = null
        var isOn = false
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            val d = resources.displayMetrics.density
            minimumWidth = (40 * d).toInt()
            minimumHeight = (40 * d).toInt()
            setOnClickListener { isOn = !isOn; onChange?.invoke(isOn) }
            background = android.graphics.drawable.InsetDrawable(
                interactive(context, Color.TRANSPARENT, 12 * d),
                (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(),
            )
        }

        override fun onMeasure(w: Int, h: Int) {
            val d = resources.displayMetrics.density
            setMeasuredDimension((40 * d).toInt(), (40 * d).toInt())
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val tw = 36 * d
            val th = 22 * d
            val l = (width - tw) / 2f
            val t = (height - th) / 2f
            paint.color = if (isOn) ACCENT else OUTLINE_STRONG
            c.drawRoundRect(RectF(l, t, l + tw, t + th), th / 2, th / 2, paint)
            paint.color = if (isOn) ON_ACCENT else SECONDARY
            val cx = if (isOn) l + tw - 3 * d - 8 * d else l + 3 * d + 8 * d
            c.drawCircle(cx, t + th / 2, 8 * d, paint)
        }
    }

    /** Equal-width choices in a pill; the selected one is accent-filled (or accent-outlined when [outlined]). */
    fun segmented(
        ctx: Context, labels: List<String>, selected: Int, outlined: Boolean = false, tagPrefix: String,
        widthDp: Int? = null, onPick: (Int) -> Unit,
    ): android.widget.LinearLayout = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        val gap = Math.round(dp(ctx, if (outlined) 6f else 4f))
        if (!outlined) {
            val p = Math.round(dp(ctx, 4f))
            setPadding(p, 0, p, 0)
            background = inset(ctx, shape(SEGMENT, dp(ctx, 11f)), 40f, 38f)
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val b = button(
                ctx, label,
                style = if (outlined) Style.OUTLINED else if (on) Style.FILLED else Style.GHOST,
                heightDp = 40, radiusDp = 9f, padDp = if (outlined) 0 else 14, sp = 14f, visibleDp = 32f,
            ) { onPick(i) }
            b.tag = "$tagPrefix$i"
            if (outlined) {
                val one = Math.round(dp(ctx, 1f))
                val outline = if (on) ACCENT else OUTLINE
                b.background = inset(ctx, interactive(ctx, Color.TRANSPARENT, dp(ctx, 9f), outline, one), 40f, 32f)
                b.setPadding(0, 0, 0, 0)
                b.setTextColor(if (on) TEXT else SECONDARY)
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                if (widthDp != null) Math.round(dp(ctx, widthDp.toFloat())) else -2, -2,
            )
            if (i > 0) lp.leftMargin = gap
            addView(b, lp)
        }
    }
}
