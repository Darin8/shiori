package app.shiori.anki

import app.shiori.dict.Frequency
import app.shiori.dict.LookupResult
import app.shiori.dict.TermEntry

data class CardInput(
    val result: LookupResult,
    /** The whole OCR line containing the tapped word. */
    val line: String,
    /** Index in [line] where the matched text starts. */
    val matchStart: Int,
    val pictureFileName: String,
)

/**
 * Field values for "JP Mining Note" cards, reproducing what Yomitan renders for this field
 * mapping (Key/Word {expression}, WordReading {furigana-plain}, PrimaryDefinition {jpmn-primary-definition},
 * Sentence {cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}, WordReadingHiragana {jpmn-word-reading-hiragana},
 * FrequenciesStylized {jpmn-frequencies}, FrequencySort {jpmn-frequency-sort}, the other definition fields,
 * PASilence a literal). jp-mining-note's handlebars are ported with its default options (v1.0.10).
 * Fields that need data Shiori has none of (audio, pitch accent) stay empty. Every name in [fieldNames] is
 * present in the result.
 *
 * Sentence deliberately HTML-escapes & < > in the cloze parts (Yomitan inserts the raw text) because OCR text can
 * contain a stray '<'; it renders identically.
 */
fun buildFields(input: CardInput, fieldNames: List<String>): Map<String, String> {
    val entry = input.result.entry
    val matched = input.result.matched
    val start = input.matchStart.coerceIn(0, input.line.length)
    val end = (start + matched.length).coerceAtMost(input.line.length)
    val sentence = escapeHtml(input.line.substring(0, start)) + "<b>" + escapeHtml(input.line.substring(start, end)) +
        "</b>" + escapeHtml(input.line.substring(end))
    val expression = escapeHandlebars(entry.expression)

    val values = mapOf(
        "Key" to expression,
        "Word" to expression,
        "WordReading" to escapeHandlebars(furiganaPlain(entry.expression, entry.reading)),
        "PrimaryDefinition" to JpmnTemplates.definitionList(entry, JpmnTemplates.primaryTypes(entry.dictionary)),
        "Sentence" to sentence,
        "Picture" to "<img src=\"${input.pictureFileName}\">",
        "PASilence" to "[sound:_silence.wav]",
        "WordReadingHiragana" to JpmnTemplates.wordReadingHiragana(entry),
        "FrequenciesStylized" to JpmnTemplates.frequencies(input.result.frequencies),
        "FrequencySort" to JpmnTemplates.frequencySort(entry.dictionary, input.result.frequencies),
        // Secondary (other bilingual) and extra (other monolingual) definitions list dictionaries besides the primary
        // one; a card has one entry, so both stay empty.
        "SecondaryDefinition" to "",
        "ExtraDefinitions" to "",
        "UtilityDictionaries" to JpmnTemplates.definitionList(entry, setOf(DictType.Utility)),
    )
    return fieldNames.associateWith { values[it] ?: "" }
}

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal enum class DictType { Ignored, Utility, Bilingual, Monolingual }

/** jp-mining-note's handlebars (yomichan_templates/top.txt + bottom.txt, v1.0.10) for one dictionary entry. */
internal object JpmnTemplates {
    private val IGNORED = Regex("^(ADD_IGNORED_DICTIONARIES_HERE)$")
    private val UTILITY = Regex("^(NHK日本語発音アクセント新辞典|シン・漢字遣い参考|[Jj][Mm][Dd]ict( Surface)? Forms|JMedict)$")
    private val BILINGUAL = Regex(
        "^(([Jj][Mm][Dd]ict)(.*)|新和英|日本語文法辞典\\(全集\\)|KireiCake|NEW斎藤和英大辞典|CEDICT|CC-CEDICT|CantoDict|" +
            "Canto CEDICT|Words\\.hk C-E FS|CE Wiktionary|CC-Canto|ADD_BILINGUAL_DICTIONARIES_HERE)$"
    )
    private val FIRST_LINE_DICTS = Regex(
        "^(JMdict.*|Nico/Pixiv|故事・ことわざ・慣用句オンライン|四字熟語辞典オンライン|国語辞典オンライン|大辞林　第四版|新明解国語辞典　第八版)$"
    )
    private val FIRST_LINE = Regex("^(<span lang=\"ja\">)?(.*?)<br>")

    // Options (top.txt defaults).
    private val IGNORED_FREQ_DICT = Regex("^(JLPT_Level)$")
    private val IGNORED_FREQ_VALUE = Regex("❌")
    private val GRAMMAR_DICTS = Regex(
        "^(日本語文法辞典\\(全集\\)|毎日のんびり日本語教師|JLPT文法解説まとめ|どんなときどう使う 日本語表現文型辞典|絵でわかる日本語)$"
    )
    private const val NO_FREQ_DEFAULT = 9999999
    private const val GRAMMAR_OVERRIDE_VALUE = 0

    fun dictType(name: String): DictType = when {
        IGNORED.containsMatchIn(name) -> DictType.Ignored
        UTILITY.containsMatchIn(name) -> DictType.Utility
        BILINGUAL.containsMatchIn(name) -> DictType.Bilingual
        else -> DictType.Monolingual
    }

    // With opt-first-definition-type "bilingual" the primary dictionary is the first bilingual one, else the first
    // monolingual one. A card has a single dictionary entry here, so the primary dictionary is that entry's
    // dictionary unless it is ignored or a utility dictionary.
    private fun isPrimary(dictionary: String) = dictType(dictionary).let { it == DictType.Bilingual || it == DictType.Monolingual }

    fun primaryTypes(dictionary: String): Set<DictType> = if (isPrimary(dictionary)) setOf(dictType(dictionary)) else emptySet()

    /** `<ol><li data-details="D">…</li></ol>`, or "" when the entry's dictionary is not of one of [types]. */
    fun definitionList(entry: TermEntry, types: Set<DictType>): String {
        if (dictType(entry.dictionary) !in types) return ""
        return "<ol><li data-details=\"${escapeHandlebars(entry.dictionary)}\">${glossarySingle(entry)}</li></ol>"
    }

    private fun glossarySingle(entry: TermEntry): String {
        val sb = StringBuilder()
        sb.append("<span class=\"dict-group__tag-list\">")
        for (tag in entry.definitionTags) {
            sb.append("<span class=\"dict-group__tag dict-group__tag--name\"><span class=\"dict-group__tag-inner\">")
                .append(escapeHandlebars(tag)).append("</span></span>")
        }
        sb.append("<span class=\"dict-group__tag dict-group__tag--dict\"><span class=\"dict-group__tag-inner\">")
            .append(escapeHandlebars(entry.dictionary)).append("</span></span></span>")
        var glossary = formatGlossary(entry.glossaryJson)
        // opt-wrap-first-line-spans: dictionaries not in the first-line exceptions get their first line wrapped.
        if (!FIRST_LINE_DICTS.containsMatchIn(entry.dictionary)) {
            FIRST_LINE.find(glossary)?.let { m ->
                glossary = m.groupValues[1] + "<span class=\"dict-group__glossary--first-line\">" + m.groupValues[2] +
                    "</span><span class=\"dict-group__glossary--first-line-break\"><br></span>" + glossary.substring(m.range.last + 1)
            }
        }
        sb.append("<span class=\"dict-group__glossary\">").append(glossary).append("</span>")
        return sb.toString()
    }

    fun wordReadingHiragana(entry: TermEntry): String {
        val reading = entry.reading.ifEmpty { entry.expression }
        return convertKatakanaToHiragana(reading, keepProlongedSoundMarks = false)
    }

    fun frequencies(frequencies: List<Frequency>): String = frequencies.joinToString("") { f ->
        "<div class=\"frequencies__group\" data-details=\"${escapeHandlebars(f.dictionary)}\">" +
            "<div class=\"frequencies__number\"><span class=\"frequencies__number-inner\">" +
            escapeHandlebars(f.display).replace("❌", "✖") +
            "</span></div><div class=\"frequencies__dictionary\"><span class=\"frequencies__dictionary-inner\">" +
            escapeHandlebars(f.dictionary) + "</span></div></div>"
    }

    /** jpmn-frequency-sort with opt-freq-sorting-method "harmonic" (the default), as JS arithmetic. */
    fun frequencySort(dictionary: String, frequencies: List<Frequency>): String {
        var result = -1.0
        var previousDict = ""
        var t = 1.0
        for (f in frequencies) {
            if (IGNORED_FREQ_DICT.containsMatchIn(f.dictionary) || IGNORED_FREQ_VALUE.containsMatchIn(f.display)) continue
            // Only the first frequency of each dictionary counts.
            if (previousDict == f.dictionary) continue
            previousDict = f.dictionary
            val n = Regex("\\d+").find(f.display)?.value?.toDouble() ?: 0.0
            if (n > 0) {
                if (result == -1.0) {
                    result = 1 / n
                } else {
                    result += 1 / n
                    t += 1
                }
            }
        }
        val value: Int = when {
            result == -1.0 -> NO_FREQ_DEFAULT
            else -> toInt32((1 / result) * t)
        }
        return if (GRAMMAR_DICTS.containsMatchIn(dictionary)) GRAMMAR_OVERRIDE_VALUE.toString() else value.toString()
    }

    /** JavaScript's `x >> 0`. */
    private fun toInt32(x: Double): Int = if (x.isNaN() || x.isInfinite()) 0 else x.toLong().toInt()
}
