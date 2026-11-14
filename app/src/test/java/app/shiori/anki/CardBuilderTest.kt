package app.shiori.anki

import androidx.test.core.app.ApplicationProvider
import app.shiori.dict.Deinflector
import app.shiori.dict.DictionaryDb
import app.shiori.dict.Lookup
import app.shiori.dict.importYomitanZip
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards rebuilt from the real Jitendex.org [2025-02-11] and JPDBv2㋕ rows must equal the cards mined on
 * the PC with Yomitan and JP Mining Note (the json files in reference-notes, saved by scripts/fetch-reference-notes.ps1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardBuilderTest {
    private lateinit var db: DictionaryDb
    private lateinit var lookup: Lookup

    private fun res(name: String) = javaClass.getResourceAsStream("/reference-notes/$name")!!

    @Before fun setUp() {
        db = DictionaryDb(ApplicationProvider.getApplicationContext(), null)
        importYomitanZip(res("jitendex-subset.zip"), db)
        importYomitanZip(res("jpdb-subset.zip"), db)
        lookup = Lookup(db, Deinflector(File("src/main/assets/japanese-transforms.json").readText()))
    }

    @After fun tearDown() = db.close()

    private class Reference(val fieldNames: List<String>, val fields: Map<String, String>)

    private fun reference(id: Long): Reference {
        val o = Json.parseToJsonElement(res("$id.json").reader().readText()).jsonObject
        return Reference(
            o.getValue("fieldNames").jsonArray.map { it.jsonPrimitive.content },
            o.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    private fun assertSame(field: String, expected: String, actual: String) {
        if (expected == actual) return
        val i = expected.zip(actual).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(expected.length, actual.length) else it }
        fun ctx(s: String) = s.substring(maxOf(0, i - 80), minOf(s.length, i + 120))
        throw AssertionError("$field differs at $i\nexpected: …${ctx(expected)}\nactual:   …${ctx(actual)}")
    }

    private fun checkNote(id: Long, headword: String) {
        val ref = reference(id)
        val sentence = ref.fields.getValue("Sentence")
        val m = Regex("<b>(.*?)</b>").find(sentence)!!
        val line = sentence.replace("<b>", "").replace("</b>", "")
        val matchStart = m.range.first
        val picture = "capture-$id.jpg"

        val results = lookup.at(line, matchStart)
        val result = results.first { it.entry.expression == headword }
        assertEquals(m.groupValues[1], result.matched)
        val fields = buildFields(CardInput(result, line, matchStart, picture), ref.fieldNames)

        assertEquals(ref.fieldNames, fields.keys.toList())
        for (name in listOf(
            "Key", "Word", "WordReading", "Sentence", "PrimaryDefinition", "FrequenciesStylized", "FrequencySort",
            "WordReadingHiragana", "PASilence", "SecondaryDefinition", "ExtraDefinitions", "UtilityDictionaries",
        )) assertSame(name, ref.fields.getValue(name), fields.getValue(name))
        assertEquals("<img src=\"$picture\">", fields["Picture"])
        assertEquals("", fields["SentenceAudio"])
        // The PC card's WordAudio is a Yomitan-downloaded mp3; Shiori has no audio source, so it stays empty.
        assertEquals("", fields["WordAudio"])
        // Everything else the PC left empty (pitch accent fields, flags, hints...).
        for ((name, expected) in ref.fields) {
            if (name in FILLED_BY_SHIORI_OR_PC_ONLY) continue
            assertSame(name, expected, fields.getValue(name))
        }
    }

    @Test fun `inflected verb with several senses`() = checkNote(1743744316389, "拾う")
    @Test fun `noun with forms table`() = checkNote(1740811834125, "恐怖")
    @Test fun `verb inflected into a passive with four senses`() = checkNote(1759006607975, "避ける")

    @Test fun `every requested field is present and unknown names are empty`() {
        val note = reference(1740811834125)
        val result = lookup.at("恐怖", 0).first()
        val fields = buildFields(CardInput(result, "恐怖", 0, "p.jpg"), listOf("Key", "NoSuchField", "Picture"))
        assertEquals(listOf("Key", "NoSuchField", "Picture"), fields.keys.toList())
        assertEquals("", fields["NoSuchField"])
        assertTrue(note.fieldNames.contains("Key"))
    }

    @Test fun `sentence is escaped and keeps the text around the match`() {
        val result = lookup.at("恐怖", 0).first()
        val fields = buildFields(CardInput(result, "a<b>&恐怖\"c", 5, "p.jpg"), listOf("Sentence"))
        assertEquals("a&lt;b&gt;&amp;<b>恐怖</b>\"c", fields["Sentence"])
    }

    @Test fun `furigana plain matches Yomitan`() {
        assertEquals("恐怖[きょうふ]", furiganaPlain("恐怖", "きょうふ"))
        assertEquals("切[き]り 替[か]わる", furiganaPlain("切り替わる", "きりかわる"))
        assertEquals("羽目[はめ]を 外[はず]す", furiganaPlain("羽目を外す", "はめをはずす"))
        assertEquals("だらしない", furiganaPlain("だらしない", "だらしない"))
        assertEquals("らあめん", convertKatakanaToHiragana("ラーメン"))
    }

    @Test fun `frequency sort uses harmonic mean of the first value per dictionary`() {
        fun f(d: String, v: Int, s: String = v.toString()) = app.shiori.dict.Frequency(d, v, s)
        assertEquals("1987", JpmnTemplates.frequencySort("X", listOf(f("J", 1987), f("J", 5203, "5203㋕"))))
        assertEquals("9999999", JpmnTemplates.frequencySort("X", emptyList()))
        assertEquals("9999999", JpmnTemplates.frequencySort("X", listOf(f("J", 1, "❌"))))
        // harmonic of 100 and 300 across two dictionaries: 2 / (1/100 + 1/300) = 150
        assertEquals("150", JpmnTemplates.frequencySort("X", listOf(f("A", 100), f("B", 300))))
    }

    private companion object {
        // Shiori fills Picture itself; the PC card's audio is a downloaded mp3 / a GSM clip (not reproduced);
        // AJTWordPitch and SentenceReading are filled by Anki add-ons after the card is added, not by the Yomitan mapping.
        val FILLED_BY_SHIORI_OR_PC_ONLY = setOf("Picture", "WordAudio", "SentenceAudio", "AJTWordPitch", "SentenceReading")
    }
}
