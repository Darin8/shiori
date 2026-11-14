package app.shiori.anki

// Ports of the Japanese text helpers Yomitan's Anki templates rely on
// (ext/js/language/ja/japanese.js and ext/js/language/CJK-util.js, tag 25.2.18).

internal data class FuriganaSegment(val text: String, val reading: String)

private val HIRAGANA_RANGE = 0x3040..0x309f
private val KATAKANA_RANGE = 0x30a0..0x30ff
private val HIRAGANA_CONVERSION_RANGE = 0x3041..0x3096
private val KATAKANA_CONVERSION_RANGE = 0x30a1..0x30f6
private const val KATAKANA_SMALL_KA = 0x30f5
private const val KATAKANA_SMALL_KE = 0x30f6
private const val PROLONGED_SOUND_MARK = 0x30fc

private val JAPANESE_RANGES = listOf(
    HIRAGANA_RANGE, KATAKANA_RANGE,
    0x4e00..0x9fff, 0x3400..0x4dbf, 0x20000..0x2a6df, 0x2a700..0x2b73f, 0x2b740..0x2b81f, 0x2b820..0x2ceaf,
    0x2ceb0..0x2ebef, 0x30000..0x3134f, 0x31350..0x323af, 0x2ebf0..0x2ee5f, 0xf900..0xfaff, 0x2f800..0x2fa1f,
    0xff66..0xff9f, 0x30fb..0x30fc, 0xff61..0xff65, 0x3000..0x303f,
    0xff10..0xff19, 0xff21..0xff3a, 0xff41..0xff5a, 0xff01..0xff0f, 0xff1a..0xff1f, 0xff3b..0xff3f, 0xff5b..0xff60,
    0xffe0..0xffee,
)

private val VOWEL_TO_KANA = mapOf(
    "a" to "ぁあかがさざただなはばぱまゃやらゎわヵァアカガサザタダナハバパマャヤラヮワヵヷ",
    "i" to "ぃいきぎしじちぢにひびぴみりゐィイキギシジチヂニヒビピミリヰヸ",
    "u" to "ぅうくぐすずっつづぬふぶぷむゅゆるゥウクグスズッツヅヌフブプムュユルヴ",
    "e" to "ぇえけげせぜてでねへべぺめれゑヶェエケゲセゼテデネヘベペメレヱヶヹ",
    "o" to "ぉおこごそぞとどのほぼぽもょよろをォオコゴソゾトドノホボポモョヨロヲヺ",
    "" to "のノ",
)

private val KANA_TO_VOWEL: Map<Char, String> = buildMap {
    for ((vowel, chars) in VOWEL_TO_KANA) for (c in chars) put(c, vowel)
}

internal fun isStringPartiallyJapanese(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        if (JAPANESE_RANGES.any { cp in it }) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun isCodePointKana(cp: Int) = cp in HIRAGANA_RANGE || cp in KATAKANA_RANGE

private fun prolongedHiragana(previous: Char): Char? = when (KANA_TO_VOWEL[previous]) {
    "a" -> 'あ'
    "i" -> 'い'
    "u" -> 'う'
    "e" -> 'え'
    "o" -> 'う'
    else -> null
}

internal fun convertKatakanaToHiragana(text: String, keepProlongedSoundMarks: Boolean = false): String {
    val out = StringBuilder()
    val offset = HIRAGANA_CONVERSION_RANGE.first - KATAKANA_CONVERSION_RANGE.first
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        var converted = cp
        when {
            cp == KATAKANA_SMALL_KA || cp == KATAKANA_SMALL_KE -> Unit
            cp == PROLONGED_SOUND_MARK -> {
                if (!keepProlongedSoundMarks && out.isNotEmpty()) {
                    prolongedHiragana(out[out.length - 1])?.let { converted = it.code }
                }
            }
            cp in KATAKANA_CONVERSION_RANGE -> converted = cp + offset
        }
        out.appendCodePoint(converted)
    }
    return out.toString()
}

private class FuriganaGroup(val isKana: Boolean, var text: String, var textNormalized: String? = null)

/** Yomitan's distributeFurigana: splits term/reading into (text, reading) segments. */
internal fun distributeFurigana(term: String, reading: String): List<FuriganaSegment> {
    if (reading == term) return listOf(FuriganaSegment(term, ""))
    val groups = ArrayList<FuriganaGroup>()
    var pre: FuriganaGroup? = null
    var isKanaPre: Boolean? = null
    var i = 0
    while (i < term.length) {
        val cp = term.codePointAt(i)
        val c = String(Character.toChars(cp))
        i += c.length
        val isKana = isCodePointKana(cp)
        if (isKana == isKanaPre) {
            pre!!.text += c
        } else {
            pre = FuriganaGroup(isKana, c)
            groups += pre
            isKanaPre = isKana
        }
    }
    for (g in groups) if (g.isKana) g.textNormalized = convertKatakanaToHiragana(g.text)
    val segments = segmentize(reading, convertKatakanaToHiragana(reading), groups, 0)
    return segments ?: listOf(FuriganaSegment(term, reading))
}

private fun segmentize(reading: String, readingNormalized: String, groups: List<FuriganaGroup>, start: Int): MutableList<FuriganaSegment>? {
    val groupCount = groups.size - start
    if (groupCount <= 0) return if (reading.isEmpty()) ArrayList() else null
    val group = groups[start]
    val text = group.text
    val textLength = text.length
    if (group.isKana) {
        val normalized = group.textNormalized
        if (normalized != null && readingNormalized.startsWith(normalized)) {
            val segments = segmentize(reading.substring(textLength), readingNormalized.substring(textLength), groups, start + 1)
            if (segments != null) {
                if (reading.startsWith(text)) segments.add(0, FuriganaSegment(text, ""))
                else segments.addAll(0, kanaSegments(text, reading))
                return segments
            }
        }
        return null
    }
    var result: MutableList<FuriganaSegment>? = null
    var i = reading.length
    while (i >= textLength) {
        val segments = segmentize(reading.substring(i), readingNormalized.substring(i), groups, start + 1)
        if (segments != null) {
            if (result != null) return null // ambiguous
            segments.add(0, FuriganaSegment(text, reading.substring(0, i)))
            result = segments
        }
        if (groupCount == 1) break
        i--
    }
    return result
}

private fun kanaSegments(text: String, reading: String): List<FuriganaSegment> {
    val out = ArrayList<FuriganaSegment>()
    var start = 0
    var state = reading.getOrNull(0) == text[0]
    for (i in 1 until text.length) {
        val newState = reading.getOrNull(i) == text[i]
        if (state == newState) continue
        out += FuriganaSegment(text.substring(start, i), if (state) "" else reading.substring(start, i))
        state = newState
        start = i
    }
    out += FuriganaSegment(text.substring(start), if (state) "" else reading.substring(start))
    return out
}

/** Yomitan's furiganaPlain helper: "漢字[かんじ] 漢字[かんじ]かな" (a space only before later readings). */
internal fun furiganaPlain(expression: String, reading: String): String {
    val sb = StringBuilder()
    for ((text, r) in distributeFurigana(expression, reading)) {
        if (r.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(text).append('[').append(r).append(']')
        } else {
            sb.append(text)
        }
    }
    return sb.toString()
}

/** Handlebars' escapeExpression, applied by Yomitan's `{{...}}` (double-brace) output. */
internal fun escapeHandlebars(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#x27;")
            '`' -> sb.append("&#x60;")
            '=' -> sb.append("&#x3D;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
