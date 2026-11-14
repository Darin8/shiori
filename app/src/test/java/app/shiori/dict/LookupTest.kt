package app.shiori.dict

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookupTest {
    private lateinit var db: DictionaryDb
    private lateinit var lookup: Lookup

    private fun zip(title: String, terms: String? = null, meta: String? = null): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("index.json", """{"title":"$title","format":3,"revision":"t"}""")
            if (terms != null) put("term_bank_1.json", terms)
            if (meta != null) put("term_meta_bank_1.json", meta)
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun term(expr: String, reading: String, rules: String, score: Int, seq: Int) =
        """["$expr","$reading","","$rules",$score,["x"],$seq,""]"""

    private fun freq(expr: String, reading: String, value: Int) =
        """["$expr","freq",{"reading":"$reading","frequency":{"value":$value}}]"""

    private fun arr(items: List<String>) = items.joinToString(prefix = "[", postfix = "]")

    @Before fun setUp() {
        db = DictionaryDb(ApplicationProvider.getApplicationContext(), null)
        lookup = Lookup(db, Deinflector(File("src/main/assets/japanese-transforms.json").readText()))
        val terms = listOf(
            term("終わる", "おわる", "v5", 50, 1),
            term("終", "おわり", "n", 10, 2),
            term("話", "はなし", "n", 10, 3),
            term("行く", "いく", "n", 10, 4), // noun-only: must not fit a verb conjugation
            term("橋", "はし", "n", 5, 5),
            term("端", "はし", "n", 90, 6),
            term("箸", "はし", "n", 99, 7),
            term("嘴", "はし", "n", 20, 8),
            term("片仮名", "かたかな", "n", 30, 9),
            term("食べる", "たべる", "v1", 40, 10),
            term("はし", "はし", "n", 1, 14),
            term("雲", "くも", "n", 10, 11),
            term("蜘蛛", "くも", "n", 10, 12),
            term("曇", "くも", "n", 50, 13),
        )
        importYomitanZip(zip("T", terms = arr(terms)), db)
        importYomitanZip(zip("F1", meta = arr(listOf(freq("橋", "はし", 10), freq("端", "はし", 80), freq("雲", "くも", 500), freq("蜘蛛", "くも", 20), freq("曇", "くも", 900)))), db)
        importYomitanZip(zip("F2", meta = arr(listOf(freq("端", "はし", 50), freq("終わる", "おわる", 300)))), db)
    }

    @After fun tearDown() = db.close()

    private val sentence = "まだ話は終わってないんだから"

    @Test fun `tapping a verb finds the longest conjugated form`() {
        val r = lookup.at(sentence, sentence.indexOf('終'))
        assertEquals("終わる", r.first().entry.expression)
        // Yomitan's transforms also read てない (te + negative) as a form of 終わる, so the longest valid match is 終わってない.
        assertEquals("終わってない", r.first().matched)
        assertEquals(1, r.count { it.entry.expression == "終わる" })
        assertTrue(r.first().reasons.isNotEmpty())
        assertEquals(listOf(Frequency("F2", 300, "300")), r.first().frequencies)
    }

    @Test fun `te form stops at a non word character`() {
        assertEquals("終わって", lookup.at("終わって、ない", 0).first().matched)
    }

    @Test fun `tapping a noun matches only itself`() {
        val r = lookup.at(sentence, sentence.indexOf('話'))
        assertEquals("話", r.first().entry.expression)
        assertEquals("話", r.first().matched)
    }

    @Test fun `particle with no entry gives nothing`() {
        assertTrue(lookup.at(sentence, sentence.indexOf('は')).isEmpty())
    }

    @Test fun `bad index and non word characters give nothing`() {
        assertTrue(lookup.at(sentence, -1).isEmpty())
        assertTrue(lookup.at(sentence, sentence.length).isEmpty())
        assertTrue(lookup.at("終わって、ない", 4).isEmpty())
        assertTrue(lookup.at("A終わる", 0).isEmpty())
    }

    @Test fun `matched never crosses a non word character`() {
        val r = lookup.at("終わって、ない", 0)
        assertEquals("終わって", r.first().matched)
        assertTrue(r.all { it.matched.length <= 4 })
    }

    @Test fun `maxLength caps the scan`() {
        val r = lookup.at(sentence, sentence.indexOf('終'), maxLength = 3)
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.matched.length <= 3 })
    }

    @Test fun `entry whose rules do not fit the deinflection is dropped`() {
        // 行く exists only as a noun, so the te-form 行って must not produce it.
        assertTrue(lookup.at("行って", 0).isEmpty())
        assertEquals("行く", lookup.at("行く", 0).first().entry.expression)
    }

    @Test fun `sorted like Yomitan (length, steps, exact, score)`() {
        assertEquals(listOf("終わる", "終"), lookup.at("終わって", 0).map { it.entry.expression })
        // Same length, steps and (non-exact) match: score decides, frequency is not consulted first.
        assertEquals(listOf("はし", "箸", "端", "嘴", "橋"), lookup.at("はし", 0).map { it.entry.expression })
    }

    @Test fun `score beats frequency and frequency breaks score ties`() {
        // 曇 score 50 (freq 900) first; 蜘蛛 and 雲 tie on score 10, so the lower frequency value 20 wins.
        assertEquals(listOf("曇", "蜘蛛", "雲"), lookup.at("くも", 0).map { it.entry.expression })
    }

    @Test fun `exact headword match beats reading only match despite lower score`() {
        // はし (score 1) is the headword itself; 箸 (99) matches only by reading.
        assertEquals("はし", lookup.at("はし", 0).first().entry.expression)
    }

    @Test fun `katakana text hits a hiragana entry and keeps the on screen match`() {
        val r = lookup.at("カタカナ", 0)
        assertEquals("片仮名", r.first().entry.expression)
        assertEquals("カタカナ", r.first().matched)
    }

    @Test fun `results are deduped per entry`() {
        val r = lookup.at("食べなかった", 0)
        assertEquals(1, r.count { it.entry.expression == "食べる" })
        assertEquals("食べなかった", r.first { it.entry.expression == "食べる" }.matched)
    }
}
