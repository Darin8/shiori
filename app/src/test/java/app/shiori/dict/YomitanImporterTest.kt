package app.shiori.dict

import androidx.test.core.app.ApplicationProvider
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
class YomitanImporterTest {
    private lateinit var db: DictionaryDb

    private fun res(name: String) = javaClass.getResourceAsStream("/dict/$name")!!

    @Before fun setUp() {
        db = DictionaryDb(ApplicationProvider.getApplicationContext(), null)
    }

    @After fun tearDown() = db.close()

    @Test fun `import returns title`() {
        assertEquals("MiniJitendex", importYomitanZip(res("mini-terms.zip"), db))
        assertEquals(listOf("MiniJitendex"), db.dictionaries())
    }

    @Test fun `findTerms by expression and by reading`() {
        importYomitanZip(res("mini-terms.zip"), db)
        assertEquals(listOf("食べる"), db.findTerms(listOf("食べる")).map { it.expression })
        val byReading = db.findTerms(listOf("いく"))
        assertEquals(listOf("行く"), byReading.map { it.expression })
        assertEquals(2, byReading[0].sequence)
        assertEquals(90, byReading[0].score)
        assertEquals("MiniJitendex", byReading[0].dictionary)
        assertEquals("""[{"type":"structured-content","content":["to go"]}]""", byReading[0].glossaryJson)
        assertEquals(3, db.findTerms(listOf("食べる", "いく", "終わる", "none")).size)
        assertTrue(db.findTerms(emptyList()).isEmpty())
    }

    @Test fun `findTerms handles more than 500 texts`() {
        importYomitanZip(res("mini-terms.zip"), db)
        val texts = (1..1200).map { "x$it" } + "終わる"
        assertEquals(listOf("終わる"), db.findTerms(texts).map { it.expression })
    }

    @Test fun `rules split on spaces`() {
        importYomitanZip(res("mini-terms.zip"), db)
        assertEquals(setOf("v5k-s"), db.findTerms(listOf("行く"))[0].rules)
        assertEquals(setOf("v1"), db.findTerms(listOf("食べる"))[0].rules)
    }

    @Test fun `frequencies by expression+reading`() {
        importYomitanZip(res("mini-freq.zip"), db)
        assertEquals(listOf(Frequency("MiniFreq", 312, "312")), db.frequencies("終わる", "おわる"))
        assertTrue(db.frequencies("終わる", "other").isEmpty())
    }

    @Test fun `frequency entries without reading match any reading`() {
        importYomitanZip(res("mini-freq.zip"), db)
        assertEquals(listOf(Frequency("MiniFreq", 55, "55")), db.frequencies("食べる", "たべる"))
        assertEquals(listOf(Frequency("MiniFreq", 7, "7㋕")), db.frequencies("行く", "いく"))
    }

    @Test fun `reimporting a title replaces its rows`() {
        importYomitanZip(res("mini-terms.zip"), db)
        importYomitanZip(res("mini-terms.zip"), db)
        assertEquals(3, db.findTerms(listOf("食べる", "行く", "終わる")).size)
        assertEquals(listOf("MiniJitendex"), db.dictionaries())
    }

    @Test fun `dictionaries are listed in import order`() {
        importYomitanZip(res("mini-freq.zip"), db)
        importYomitanZip(res("mini-terms.zip"), db)
        assertEquals(listOf("MiniFreq", "MiniJitendex"), db.dictionaries())
    }

    @Test fun `re-importing a dictionary with a NULL position appends it at the end`() {
        importYomitanZip(res("mini-freq.zip"), db)
        importYomitanZip(res("mini-terms.zip"), db)
        db.db.execSQL("UPDATE dicts SET position = NULL WHERE title = 'MiniJitendex'")
        importYomitanZip(res("mini-terms.zip"), db)
        assertEquals(listOf("MiniFreq", "MiniJitendex"), db.dictionaries())
    }

    private fun brokenZip(title: String): java.io.ByteArrayInputStream {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("index.json", """{"title":"$title","format":3,"revision":"t"}""")
            put("term_bank_1.json", """[["犬","いぬ","","",1,["dog"],9,""]]""")
            put("term_bank_2.json", """[["猫","ねこ","","",1,["cat"""")
        }
        return java.io.ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun `failed import leaves nothing behind`() {
        try { importYomitanZip(brokenZip("Broken"), db); org.junit.Assert.fail("expected failure") } catch (e: Exception) {
            if (e is AssertionError) throw e
        }
        assertTrue(db.dictionaries().isEmpty())
        assertTrue(db.findTerms(listOf("犬", "猫")).isEmpty())
    }

    @Test fun `failed re-import keeps the old dictionary`() {
        importYomitanZip(res("mini-terms.zip"), db)
        try { importYomitanZip(brokenZip("MiniJitendex"), db); org.junit.Assert.fail("expected failure") } catch (e: Exception) {
            if (e is AssertionError) throw e
        }
        assertEquals(listOf("MiniJitendex"), db.dictionaries())
        assertEquals(3, db.findTerms(listOf("食べる", "行く", "終わる")).size)
        assertTrue(db.findTerms(listOf("犬")).isEmpty())
    }
}
