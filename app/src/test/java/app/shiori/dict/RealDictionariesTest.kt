package app.shiori.dict

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.serialization.json.jsonObject
import java.util.zip.ZipFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs only when SHIORI_REAL_DICTS points at a directory holding the real dictionary zips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealDictionariesTest {
    @Test fun importRealDictionaries() {
        val dir = System.getenv("SHIORI_REAL_DICTS")
        assumeTrue("SHIORI_REAL_DICTS not set", dir != null)
        val db = DictionaryDb(ApplicationProvider.getApplicationContext(), "real-test.db")
        try {
            for (zip in File(dir!!).listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }) {
                val indexTitle = ZipFile(zip).use { z ->
                    val e = z.getEntry("index.json")
                    kotlinx.serialization.json.Json.parseToJsonElement(z.getInputStream(e).reader().readText()).jsonObject["title"]
                }
                val t0 = System.nanoTime()
                val title = zip.inputStream().buffered().use { importYomitanZip(it, db) }
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("REAL ${zip.name}: index.json title=\"$indexTitle\" imported as \"$title\" in ${ms} ms")
            }
            println("REAL dictionaries=${db.dictionaries()}")
            for ((k, v) in db.counts()) println("REAL rows $k = $v")
            val t0 = System.nanoTime()
            val hits = db.findTerms(listOf("食べる", "終わる"))
            println("REAL findTerms ${hits.size} hits in ${(System.nanoTime() - t0) / 1_000_000} ms; freq 終わる=${db.frequencies("終わる", "おわる")}")
            val lookup = Lookup(db, Deinflector(File("src/main/assets/japanese-transforms.json").readText()))
            for ((line, word) in listOf(
                "まだ話は終わってないんだから" to "終わって",
                "昨日は何も食べなかったよ" to "食べなかった",
                "とても静かなところです" to "静かな",
                "一緒に行ってみよう" to "行って",
            )) {
                val i = line.indexOf(word)
                val l0 = System.nanoTime()
                val r = lookup.at(line, i)
                val ms = (System.nanoTime() - l0) / 1_000_000
                val top = r.firstOrNull()
                println(
                    "REAL lookup $word in $line: ${r.size} results in $ms ms; top=${top?.entry?.expression}/${top?.entry?.reading} " +
                        "matched=${top?.matched} reasons=${top?.reasons} dict=${top?.entry?.dictionary} freq=${top?.frequencies}"
                )
            }
        } finally {
            db.close()
        }
    }
}
