package app.shiori.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class DictionaryManagerTest {
    private lateinit var db: DictionaryDb
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun zip(title: String, terms: String? = null, meta: String? = null): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("index.json", """{"title":"$title","format":3,"revision":"r1"}""")
            if (terms != null) put("term_bank_1.json", terms)
            if (meta != null) put("term_meta_bank_1.json", meta)
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun term(expr: String, reading: String, score: Int) = """["$expr","$reading","","",$score,["x"],1,""]"""
    private fun freq(expr: String, reading: String, v: Int) =
        """["$expr","freq",{"reading":"$reading","frequency":{"value":$v}}]"""

    @Before fun setUp() { db = DictionaryDb(ctx, null) }
    @After fun tearDown() = db.close()

    private fun twoTermDicts() {
        // B has the higher score, A is imported first.
        importYomitanZip(zip("A", terms = "[${term("犬", "いぬ", 1)}]"), db)
        importYomitanZip(zip("B", terms = "[${term("犬", "いぬ", 99)}]"), db)
    }

    private fun lookup() = Lookup(db, Deinflector(File("src/main/assets/japanese-transforms.json").readText()))

    @Test fun `migration v2 to v3 keeps rows and dicts`() {
        val name = "mig.db"
        ctx.deleteDatabase(name)
        val path = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE terms(expression TEXT NOT NULL, reading TEXT NOT NULL, rules TEXT NOT NULL, score INTEGER NOT NULL, glossary TEXT NOT NULL, sequence INTEGER NOT NULL, dictionary TEXT NOT NULL, def_tags TEXT NOT NULL DEFAULT '')")
            old.execSQL("CREATE TABLE meta(expression TEXT NOT NULL, reading TEXT, mode TEXT NOT NULL, value INTEGER NOT NULL, display TEXT NOT NULL, dictionary TEXT NOT NULL)")
            old.execSQL("CREATE TABLE dicts(title TEXT NOT NULL UNIQUE, revision TEXT, kind TEXT NOT NULL)")
            old.execSQL("CREATE TABLE tags(dictionary TEXT NOT NULL, name TEXT NOT NULL, ord INTEGER NOT NULL)")
            old.execSQL("INSERT INTO terms VALUES('犬','いぬ','',  5,'[\"x\"]',1,'Old','')")
            old.execSQL("INSERT INTO meta VALUES('犬',NULL,'freq',7,'7','OldFreq')")
            old.execSQL("INSERT INTO dicts VALUES('Old','r','terms')")
            old.execSQL("INSERT INTO dicts VALUES('OldFreq','r','meta')")
            old.version = 2
        }
        DictionaryDb(ctx, name).use { m ->
            assertEquals(listOf("Old", "OldFreq"), m.dictionaries())
            assertEquals(listOf(true, true), m.dictionaryInfos().map { it.enabled })
            assertEquals(listOf("犬"), m.findTerms(listOf("犬")).map { it.expression })
            assertEquals(listOf(Frequency("OldFreq", 7, "7")), m.frequencies("犬", "いぬ"))
        }
        ctx.deleteDatabase(name)
    }

    @Test fun `disabled dictionary is excluded from terms and frequencies`() {
        importYomitanZip(zip("A", terms = "[${term("犬", "いぬ", 1)}]"), db)
        importYomitanZip(zip("F", meta = "[${freq("犬", "いぬ", 3)}]"), db)
        db.setEnabled("A", false)
        db.setEnabled("F", false)
        assertTrue(db.findTerms(listOf("犬")).isEmpty())
        assertTrue(db.frequencies("犬", "いぬ").isEmpty())
        assertEquals(emptyList<String>(), db.enabledDictionaries())
        db.setEnabled("A", true)
        assertEquals(1, db.findTerms(listOf("犬")).size)
    }

    @Test fun `reorder changes lookup order`() {
        twoTermDicts()
        assertEquals(listOf("A", "B"), lookup().at("犬", 0).map { it.entry.dictionary })
        db.move("B", -1)
        assertEquals(listOf("B", "A"), lookup().at("犬", 0).map { it.entry.dictionary })
        db.move("B", 1)
        assertEquals(listOf("A", "B"), db.dictionaries())
    }

    @Test fun `frequencies follow position order`() {
        importYomitanZip(zip("F1", meta = "[${freq("犬", "いぬ", 1)}]"), db)
        importYomitanZip(zip("F2", meta = "[${freq("犬", "いぬ", 2)}]"), db)
        db.move("F2", -1)
        assertEquals(listOf("F2", "F1"), db.frequencies("犬", "いぬ").map { it.dictionary })
    }

    @Test fun `delete removes only that title`() {
        importYomitanZip(zip("A", terms = "[${term("犬", "いぬ", 1)}]", meta = "[${freq("犬", "いぬ", 3)}]"), db)
        importYomitanZip(zip("B", terms = "[${term("猫", "ねこ", 1)}]", meta = "[${freq("猫", "ねこ", 4)}]"), db)
        db.delete("A")
        assertEquals(listOf("B"), db.dictionaries())
        assertEquals(mapOf("terms" to 1L, "meta" to 1L, "dicts" to 1L, "tags" to 0L), db.counts())
        assertEquals(listOf("猫"), db.findTerms(listOf("犬", "猫")).map { it.expression })
    }

    @Test fun `new imports go last and reimport keeps enabled and position`() {
        twoTermDicts()
        importYomitanZip(zip("C", terms = "[${term("猫", "ねこ", 1)}]"), db)
        assertEquals(listOf("A", "B", "C"), db.dictionaries())
        db.setEnabled("B", false)
        db.move("B", 1)
        importYomitanZip(zip("B", terms = "[${term("犬", "いぬ", 99)}]"), db)
        val infos = db.dictionaryInfos()
        assertEquals(listOf("A", "C", "B"), infos.map { it.title })
        assertEquals(false, infos.first { it.title == "B" }.enabled)
    }
}
