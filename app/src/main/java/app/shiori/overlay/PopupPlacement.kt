package app.shiori.overlay

/** A word box in frame pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class PopupPos(val left: Int, val top: Int)

const val POPUP_MARGIN_PX = 16
private const val GAP_PX = 8

/** Fixed popup width: 40% of the frame, never below [minDp], never wider than the frame minus margins. */
fun popupWidth(frameW: Int, density: Float, minDp: Int = 300): Int =
    maxOf((frameW * 0.40f).toInt(), (minDp * density).toInt())
        .coerceAtMost((frameW - 2 * POPUP_MARGIN_PX).coerceAtLeast(1))

/**
 * Top-left of a [popupW]×[popupH] popup next to [word], all in frame pixels: below the word, else above, else right,
 * else left, always clamped on screen (a popup larger than the frame is pinned to the origin).
 */
fun placePopup(
    frameW: Int, frameH: Int, word: Box, popupW: Int, popupH: Int,
    margin: Int = POPUP_MARGIN_PX, gap: Int = GAP_PX,
): PopupPos {
    fun clampX(x: Float) = x.toInt()
        .coerceIn(margin, maxOf(margin, frameW - popupW - margin))
        .let { if (popupW + 2 * margin > frameW) 0 else it }
    fun clampY(y: Float) = y.toInt()
        .coerceIn(margin, maxOf(margin, frameH - popupH - margin))
        .let { if (popupH + 2 * margin > frameH) 0 else it }
    val below = word.bottom + gap
    val above = word.top - gap - popupH
    val right = word.right + gap
    val left = word.left - gap - popupW
    return when {
        below + popupH <= frameH - margin -> PopupPos(clampX(word.left), below.toInt())
        above >= margin -> PopupPos(clampX(word.left), above.toInt())
        right + popupW <= frameW - margin -> PopupPos(right.toInt(), clampY(word.top))
        left >= margin -> PopupPos(left.toInt(), clampY(word.top))
        else -> {
            // Nothing fits cleanly: use the roomier vertical side, clamped.
            val roomBelow = frameH - word.bottom
            val roomAbove = word.top
            PopupPos(clampX(word.left), clampY(if (roomBelow >= roomAbove) below else above))
        }
    }
}

/** A straight line in the same pixel space as the boxes it joins. */
data class Leader(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * Line from the popup edge facing [word] to the word's nearest edge: popup below the word -> popup top to word bottom,
 * above -> popup bottom to word top, right/left likewise horizontally. The line sits over the word where the two
 * overlap along the shared axis, kept [inset] away from the popup's corners; null when the boxes overlap on both axes.
 */
fun leaderLine(popup: Box, word: Box, inset: Float = 14f): Leader? {
    fun along(pLo: Float, pHi: Float, wLo: Float, wHi: Float): Float {
        val lo = maxOf(pLo + inset, wLo)
        val hi = minOf(pHi - inset, wHi)
        val mid = (wLo + wHi) / 2f
        return if (lo <= hi) (lo + hi) / 2f else mid.coerceIn(pLo + inset, maxOf(pLo + inset, pHi - inset))
    }
    return when {
        popup.top >= word.bottom ->
            along(popup.left, popup.right, word.left, word.right).let { Leader(it, popup.top, it, word.bottom) }
        popup.bottom <= word.top ->
            along(popup.left, popup.right, word.left, word.right).let { Leader(it, popup.bottom, it, word.top) }
        popup.left >= word.right ->
            along(popup.top, popup.bottom, word.top, word.bottom).let { Leader(popup.left, it, word.right, it) }
        popup.right <= word.left ->
            along(popup.top, popup.bottom, word.top, word.bottom).let { Leader(popup.right, it, word.left, it) }
        else -> null
    }
}

/** A placement plus, when the card must shrink to avoid the text, the most height (px) its window may use. */
data class PopupPlacement(val pos: PopupPos, val maxHeight: Int?)

/** Smallest band (px) worth shrinking the card into; below this the old next-to-the-word placement is used. */
const val MIN_BAND_PX = 160

/**
 * Places the popup so it does not cover the OCR text: [text] is the bounding union of every line. The card goes in the
 * larger free vertical band (above or below the whole block), horizontally centred on [word] and clamped on screen.
 * When the card is taller than that band, [PopupPlacement.maxHeight] is the band height so the glossary scrolls
 * inside it. If the larger band is under [minBand], falls back to [placePopup].
 */
fun placePopupClear(
    frameW: Int, frameH: Int, word: Box, text: Box, popupW: Int, popupH: Int,
    margin: Int = POPUP_MARGIN_PX, gap: Int = GAP_PX, minBand: Int = MIN_BAND_PX,
): PopupPlacement {
    val bandAbove = (text.top - gap - margin).toInt()
    val bandBelow = (frameH - margin - (text.bottom + gap)).toInt()
    val above = bandAbove > bandBelow
    val band = maxOf(bandAbove, bandBelow)
    if (band < minOf(minBand, popupH)) {
        return PopupPlacement(placePopup(frameW, frameH, word, popupW, popupH, margin, gap), null)
    }
    val h = minOf(popupH, band)
    val x = ((word.left + word.right) / 2f - popupW / 2f).toInt()
        .coerceIn(margin, maxOf(margin, frameW - popupW - margin))
        .let { if (popupW + 2 * margin > frameW) 0 else it }
    val y = if (above) (text.top - gap - h).toInt() else (text.bottom + gap).toInt()
    return PopupPlacement(PopupPos(x, y), if (popupH > band) band else null)
}
