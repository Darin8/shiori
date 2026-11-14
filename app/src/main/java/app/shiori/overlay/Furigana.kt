package app.shiori.overlay

import app.shiori.anki.distributeFurigana

private fun isKanaChar(c: Char) = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ' || c in 'ｦ'..'ﾟ'

/**
 * Ruby segments for a headword: (text, reading above it or null). No furigana when the reading is missing, equals
 * the expression, or the expression is all kana. Uses Yomitan's furigana distribution.
 */
fun furiganaSegments(expression: String, reading: String): List<Pair<String, String?>> {
    if (reading.isEmpty() || reading == expression || expression.all { isKanaChar(it) || it == 'ー' }) {
        return listOf(expression to null)
    }
    return distributeFurigana(expression, reading).map { it.text to it.reading.ifEmpty { null } }
}
