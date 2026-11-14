package app.shiori.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shiori.dict.LookupResult
import app.shiori.dict.groupFrequencies
import app.shiori.ui.Sumi
import kotlin.math.log10

/** A popup root whose total height can be capped so its content scrolls. */
interface HeightLimited { fun limitHeight(px: Int?) }

enum class AddState { CHECKING, READY, ADDING, DUPLICATE, ADDED, UNAVAILABLE }

/** Pure add-button state: a press only counts from READY and immediately moves to ADDING. */
class AddGate(var state: AddState) {
    /** True when this press should start an add. */
    fun press(): Boolean {
        if (state != AddState.READY) return false
        state = AddState.ADDING
        return true
    }
}

/** How common a word is, 0.05..1, from its frequency rank: fill = clamp(1 - log10(rank) / 5, 0.05, 1). */
fun commonness(rank: Int): Float = (1f - log10(rank.coerceAtLeast(1).toFloat()) / 5f).coerceIn(0.05f, 1f)

/** Dictionary card shown near the tapped word, Sumi glass style. Plain views, no layout resources. */
class EntryPopup(
    private val context: Context,
    private val results: List<LookupResult>,
    addState: AddState,
    private val onAdd: (LookupResult) -> Unit,
    unavailableLabel: String? = null,
) {
    var onIndexChanged: ((LookupResult) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var index = 0
    private val gate = AddGate(addState)
    private val state get() = gate.state
    private var unavailable = unavailableLabel

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** Fixed popup width in px (content never changes it). */
    val widthPx = popupWidth(metrics.widthPixels, density)

    private val regular = Sumi.font(context, 400)
    private val semibold = Sumi.font(context, 600)

    private val headword = RubyView(context).apply { setTypeface(semibold) }
    private val meta = ChipFlow(context, dp(10), dp(2)).apply { clipChildren = false }
    private val status = text(11f, Sumi.SECONDARY)
    private val glossary = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val prev = stepButton("‹") { step(-1) }
    private val next = stepButton("›") { step(1) }
    private val counter = text(12f, Sumi.SECONDARY).apply { gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0) }
    private val stepper = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        translationX = -dp(5).toFloat()
        addView(prev, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(counter, LinearLayout.LayoutParams(-2, -2))
        addView(next, LinearLayout.LayoutParams(dp(40), dp(40)))
    }
    private val addButton = text(14f, Sumi.ON_ACCENT, weight = 600).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { press() }
    }

    /** ScrollView whose height is capped from outside. */
    private class CappedScroll(context: Context) : ScrollView(context) {
        var maxH = Int.MAX_VALUE
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    /** The popup window's root: fixed width, optional cap on its total height (set when the card must avoid text). */
    private inner class PopupView : LinearLayout(context), HeightLimited {
        override fun limitHeight(px: Int?) {
            maxTotalHeight = px ?: Int.MAX_VALUE
            requestLayout()
        }
        var maxTotalHeight = Int.MAX_VALUE
        override fun onMeasure(w: Int, h: Int) {
            // Fixed width regardless of the parent's spec or the content.
            super.onMeasure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY), h)
        }
    }

    val view: View
    private val popupView = PopupView()

    /** Caps the window's total height (px, shadow padding included) so the glossary scrolls; null lifts the cap. */
    fun limitHeight(px: Int?) = popupView.limitHeight(px)

    init {
        require(results.isNotEmpty()) { "EntryPopup needs at least one result" }
        val scroll = CappedScroll(context).apply { addView(glossary); isVerticalScrollBarEnabled = true }

        // Headword takes the width left beside Add (RubyView wraps, never clips); Add is pinned top right.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(headword, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(2) })
            addView(addButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }
        val maxCardDefault = (metrics.heightPixels * 0.55f).toInt()
        val card = object : LinearLayout(context) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = popupView.maxTotalHeight
                val maxCard = if (cap == Int.MAX_VALUE) {
                    maxCardDefault
                } else {
                    minOf(maxCardDefault, (cap - 2 * dp(SHADOW_PAD_DP)).coerceAtLeast(0))
                }
                scroll.maxH = Int.MAX_VALUE
                scroll.forceLayout() // maxH is not part of the measure spec, so bypass the measure cache
                super.onMeasure(w, h)
                // measuredHeight is clamped to the parent's spec, so add the children up by hand.
                var fixed = paddingTop + paddingBottom
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    if (c === scroll || c.visibility == View.GONE) continue
                    fixed += c.measuredHeight + (c.layoutParams as MarginLayoutParams).topMargin
                }
                val scrollTop = (scroll.layoutParams as MarginLayoutParams).topMargin
                if (fixed + scrollTop + scroll.measuredHeight > maxCard) {
                    scroll.maxH = (maxCard - fixed - scrollTop).coerceAtLeast(dp(48))
                    scroll.forceLayout()
                    super.onMeasure(w, h)
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(230, 0x15, 0x17, 0x1C))
                setStroke(dp(1), Sumi.OUTLINE)
            }
            elevation = dp(8).toFloat()
            clipChildren = false // the pager's inset pill is nudged left so its glyphs line up with the headword
            clipToPadding = false
            addView(head)
            addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        view = popupView.apply {
            val shadowPad = dp(SHADOW_PAD_DP) // room for the shadow
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
        bind()
    }

    fun current(): LookupResult = results[index]

    fun setAddState(state: AddState, unavailableLabel: String? = null) {
        gate.state = state
        this.unavailable = unavailableLabel
        bindAdd()
    }

    /** Gamepad: A adds, left/right step, B closes. Returns true when the key was used. */
    fun onKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> {
            press()
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
        KeyEvent.KEYCODE_BUTTON_B -> { onClose?.invoke(); true }
        else -> false
    }

    private fun press() {
        if (gate.press()) {
            bindAdd()
            onAdd(current())
        }
    }

    private fun step(delta: Int) {
        if (results.size < 2) return
        index = (index + delta + results.size) % results.size
        bind()
        onIndexChanged?.invoke(current())
    }

    private fun bind() {
        val r = current()
        val e = r.entry
        val g = glossarySenses(e.glossaryJson)

        headword.set(furiganaSegments(e.expression, e.reading), 22f * metrics.scaledDensity, Sumi.TEXT, Sumi.SECONDARY)

        meta.removeAllViews()
        // Pager first (left), then one badge per frequency dictionary: label and value(s), no bar.
        if (results.size > 1) meta.addView(stepper)
        for ((name, value) in groupFrequencies(r.frequencies)) {
            meta.addView(freqGroup(name.replace(Regex("v\\d.*$"), ""), value))
        }
        if (g.partsOfSpeech.isNotEmpty()) {
            meta.addView(text(11f, Sumi.SECONDARY).apply { text = g.partsOfSpeech.joinToString(" ") })
        }
        if (r.reasons.isNotEmpty()) {
            meta.addView(text(11f, Sumi.SECONDARY).apply { text = r.reasons.joinToString(" ← ") })
        }

        glossary.removeAllViews()
        if (results.map { it.entry.dictionary }.distinct().size > 1) {
            glossary.addView(
                text(11f, Sumi.SECONDARY).apply { text = e.dictionary.substringBefore(" [").removeSuffix(".org") },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(3) },
            )
        }
        g.senses.forEachIndexed { i, s ->
            val sb = SpannableStringBuilder()
            if (s.tags.isNotEmpty()) {
                val start = sb.length
                sb.append(s.tags.joinToString(" ")).append("  ")
                sb.setSpan(android.text.style.ForegroundColorSpan(Sumi.SECONDARY), start, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, sb.length, 0)
            }
            sb.append(s.glosses.joinToString("; "))
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    text(15f, Sumi.SECONDARY).apply { this.text = "${i + 1}."; setLineSpacing(0f, 1.0f) },
                    LinearLayout.LayoutParams(dp(24), -2),
                )
                addView(
                    text(15f, Color.parseColor("#DDE2EA")).apply { this.text = sb; setLineSpacing(0f, 1.0f) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
            glossary.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) 0 else dp(4) })
        }
        counter.text = "${index + 1}/${results.size}"
        bindAdd()
    }

    private fun bindAdd() {
        val look = when (state) {
            AddState.CHECKING -> AddLook("…", Sumi.CONTROL, 0, Sumi.SECONDARY)
            AddState.READY -> AddLook("Add", Sumi.ACCENT, 0, Sumi.ON_ACCENT)
            AddState.ADDING -> AddLook("Adding", Sumi.CONTROL, 0, Sumi.SECONDARY)
            AddState.DUPLICATE -> AddLook("In Anki", Color.TRANSPARENT, Sumi.OUTLINE_STRONG, Sumi.TEXT)
            AddState.ADDED -> AddLook("Added", Color.TRANSPARENT, Sumi.OUTLINE_STRONG, Sumi.POSITIVE)
            AddState.UNAVAILABLE -> AddLook("!", Color.TRANSPARENT, 0, Sumi.META)
        }
        addButton.text = look.label
        addButton.setTextColor(look.color)
        addButton.typeface = if (state == AddState.READY) semibold else regular
        addButton.isEnabled = state == AddState.READY
        addButton.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(look.fill)
            if (look.stroke != 0) setStroke(dp(1), look.stroke)
        }
        status.text = if (state == AddState.UNAVAILABLE) unavailable ?: "Anki unavailable" else ""
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private class AddLook(val label: String, val fill: Int, val stroke: Int, val color: Int)

    private fun text(sp: Float, color: Int, weight: Int = 400) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (weight >= 600) semibold else regular
    }

    private fun freqGroup(name: String, ranks: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(11f, Sumi.SECONDARY).apply { text = name })
            addView(
                text(11f, Sumi.TEXT).apply { text = ranks },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) },
            )
        }

    /** Compact pager button: 30dp visible pill inside a 40dp touch target. */
    private fun stepButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Sumi.TEXT)
        typeface = regular
        gravity = Gravity.CENTER
        background = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Sumi.CONTROL) }, dp(5),
        )
        setOnClickListener { onClick() }
    }

    companion object {
        /** Transparent padding around the card inside the popup window. */
        const val SHADOW_PAD_DP = 8
    }
}
