package app.shiori.anki

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Maintenance tool, not a check: cuts the real Jitendex and JPDB dictionaries down to the rows the reference-note
 * tests need and writes small Yomitan zips (real index.json, real tag bank) into src/test/resources/reference-notes.
 * Run with SHIORI_REAL_DICTS pointing at the folder holding the two real zips (it skips otherwise):
 *   $env:SHIORI_REAL_DICTS = "C:\path\to\shiori-data"
 *   .\gradlew.bat :app:testDebugUnitTest --tests "app.shiori.anki.BuildReferenceDicts"
 */
class BuildReferenceDicts {
    @Test fun build() {
        val dir = System.getenv("SHIORI_REAL_DICTS")
        assumeTrue("SHIORI_REAL_DICTS not set", dir != null)
        val out = File("src/test/resources/reference-notes")
        val words = System.getenv("REFERENCE_WORDS")?.split(',') ?: REFERENCE_WORDS
        for (zip in File(dir!!).listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }) {
            ZipFile(zip).use { z ->
                val title = Json.parseToJsonElement(z.getInputStream(z.getEntry("index.json")).reader().readText())
                    .jsonObject["title"]!!.let { (it as JsonPrimitive).content }
                val target = when {
                    title.startsWith("Jitendex.org") -> "jitendex-subset.zip"
                    title == "JPDBv2㋕" -> "jpdb-subset.zip"
                    else -> return@use
                }
                ZipOutputStream(File(out, target).outputStream()).use { o ->
                    fun put(name: String, bytes: ByteArray) {
                        o.putNextEntry(ZipEntry(name))
                        o.write(bytes)
                        o.closeEntry()
                    }
                    put("index.json", z.getInputStream(z.getEntry("index.json")).readBytes())
                    z.getEntry("tag_bank_1.json")?.let { put("tag_bank_1.json", z.getInputStream(it).readBytes()) }
                    val rows = mutableListOf<kotlinx.serialization.json.JsonElement>()
                    for (e in z.entries().asSequence().sortedBy { it.name }) {
                        if (!Regex("""term(_meta)?_bank_\d+\.json""").matches(e.name)) continue
                        for (row in Json.parseToJsonElement(z.getInputStream(e).reader().readText()) as JsonArray) {
                            if ((row as JsonArray)[0].let { (it as JsonPrimitive).contentOrNull } in words) rows.add(row)
                        }
                    }
                    val bank = if (target.startsWith("jitendex")) "term_bank_1.json" else "term_meta_bank_1.json"
                    put(bank, JsonArray(rows).toString().toByteArray())
                    println("BUILT $target: ${rows.size} rows for $title")
                }
            }
        }
    }

    private companion object {
        val REFERENCE_WORDS = listOf("拾う", "恐怖", "避ける")
    }
}
