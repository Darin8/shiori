package app.shiori.dict

import java.text.Normalizer

data class LookupResult(
    val entry: TermEntry,
    val matched: String,
    val reasons: List<String>,
    val frequencies: List<Frequency>,
)

/** Longest-match dictionary lookup from a tapped character, like Yomitan's scan. */
class Lookup(private val db: DictionaryDb, private val deinflector: Deinflector) {

    private class Hit(val entry: TermEntry, val matched: String, val reasons: List<String>, val exact: Boolean)

    fun at(line: String, charIndex: Int, maxLength: Int = 16): List<LookupResult> {
        if (charIndex !in line.indices || !isWordChar(line, charIndex)) return emptyList()
        var end = charIndex
        while (end < line.length && end - charIndex < maxLength && isWordChar(line, end)) end++
        // Do not cut a surrogate pair in half.
        if (end < line.length && end > charIndex && Character.isLowSurrogate(line[end])) end--
        if (end <= charIndex) return emptyList()

        // (matched substring, deinflection) pairs for every prefix, longest first.
        val pairs = ArrayList<Pair<String, Deinflection>>()
        val texts = LinkedHashSet<String>()
        for (e in end downTo charIndex + 1) {
            val sub = line.substring(charIndex, e)
            for (variant in variants(sub)) {
                for (d in deinflector.deinflect(variant)) {
                    pairs += sub to d
                    texts += d.text
                }
            }
        }
        if (texts.isEmpty()) return emptyList()

        val byText = pairs.groupBy { it.second.text }
        val best = LinkedHashMap<List<Any>, Hit>()
        for (entry in db.findTerms(texts)) {
            for (text in setOf(entry.expression, entry.reading)) {
                for ((matched, d) in byText[text].orEmpty()) {
                    if (!deinflector.rulesMatch(d.rules, entry.rules)) continue
                    val key = listOf(entry.dictionary, entry.expression, entry.reading, entry.sequence)
                    val old = best[key]
                    val hit = Hit(entry, matched, d.reasons, d.text == entry.expression)
                    if (old == null || better(hit, old)) best[key] = hit
                }
            }
        }

        // Order follows Yomitan's Translator._sortTermDictionaryEntries (ee81f8d, ext/js/language/translator.js)
        // with no sort-frequency dictionary: source length desc, fewer inflection steps, exact headword match,
        // score desc. Frequency rank is only the final tie-break, then headword and sequence for stability.
        val ranked = best.values.map { h -> h to db.frequencies(h.entry.expression, h.entry.reading) }
        return ranked
            .sortedWith(
                compareByDescending<Pair<Hit, List<Frequency>>> { it.first.matched.length }
                    .thenBy { it.first.reasons.size }
                    .thenByDescending { it.first.exact }
                    .thenBy { it.first.entry.dictionaryIndex }
                    .thenByDescending { it.first.entry.score }
                    .thenBy { p -> p.second.minOfOrNull { it.value } ?: Int.MAX_VALUE }
                    .thenBy { it.first.entry.expression }
                    .thenBy { it.first.entry.sequence }
            )
            .map { (h, freqs) -> LookupResult(h.entry, h.matched, h.reasons, freqs) }
    }

    private fun better(a: Hit, b: Hit) = when {
        a.matched.length != b.matched.length -> a.matched.length > b.matched.length
        a.reasons.size != b.reasons.size -> a.reasons.size < b.reasons.size
        else -> a.exact && !b.exact
    }

    private fun variants(s: String): Set<String> {
        val full = toFullWidth(s)
        return linkedSetOf(s, toHiragana(s), full, toHiragana(full))
    }

    private fun toHiragana(s: String) = buildString {
        for (c in s) append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    }

    private fun toFullWidth(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c in '!'..'~' -> sb.append(c + 0xFEE0)
                c in 'ｦ'..'ﾝ' -> {
                    val next = s.getOrNull(i + 1)
                    val joined = if (next == 'ﾞ' || next == 'ﾟ') Normalizer.normalize("$c$next", Normalizer.Form.NFKC) else null
                    if (joined != null && joined.length == 1) {
                        sb.append(joined)
                        i++
                    } else {
                        sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC))
                    }
                }
                c == 'ｰ' -> sb.append('ー')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun isWordChar(s: String, i: Int): Boolean {
        val c = s[i]
        if (Character.isSurrogate(c)) {
            val cp = if (Character.isHighSurrogate(c) && i + 1 < s.length) s.codePointAt(i)
            else if (Character.isLowSurrogate(c) && i > 0) s.codePointAt(i - 1) else return false
            return cp in 0x20000..0x2FA1F
        }
        return c in 'ぁ'..'ゟ' || (c in 'ァ'..'ヿ' && c != '・') ||
            c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' ||
            c == '々' || c == '〆' || c in 'ｦ'..'ﾟ'
    }
}
