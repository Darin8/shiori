package app.shiori.dict

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val TERM_BANK = Regex("""term_bank_\d+\.json""")
private val META_BANK = Regex("""term_meta_bank_\d+\.json""")
private val TAG_BANK = Regex("""tag_bank_\d+\.json""")

/**
 * Imports a Yomitan dictionary zip (format 3) and returns its title from index.json.
 * The zip is spooled to a temp file so entry order does not matter; bank files are parsed one at a
 * time, never the whole dictionary. Re-importing a title replaces its rows.
 */
@OptIn(ExperimentalSerializationApi::class)
fun importYomitanZip(zip: InputStream, db: DictionaryDb): String {
    val tmp = File.createTempFile("yomitan", ".zip")
    try {
        zip.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        ZipFile(tmp).use { zf ->
            val indexEntry = zf.getEntry("index.json") ?: error("index.json missing")
            val index = Json.parseToJsonElement(zf.getInputStream(indexEntry).readBytes().decodeToString()).jsonObject
            val title = index["title"]?.str() ?: error("index.json has no title")
            val revision = index["revision"]?.str()
            val banks = zf.entries().asSequence().map { it.name }.sorted().toList()
            val hasTerms = banks.any { TERM_BANK.matches(it.substringAfterLast('/')) }
            val hasMeta = banks.any { META_BANK.matches(it.substringAfterLast('/')) }
            val sqlite = db.db
            // One transaction: on any failure everything rolls back, so a failed import leaves no partial
            // rows and a failed re-import keeps the old dictionary. The dicts row is written last.
            sqlite.beginTransaction()
            try {
                // A re-import keeps the dictionary's enabled flag and place in the order.
                var enabled = 1
                var position: Long? = null
                sqlite.rawQuery("SELECT enabled, position FROM dicts WHERE title = ?", arrayOf(title)).use { c ->
                    if (c.moveToFirst()) { enabled = c.getInt(0); position = if (c.isNull(1)) null else c.getLong(1) }
                }
                sqlite.execSQL("DELETE FROM terms WHERE dictionary = ?", arrayOf(title))
                sqlite.execSQL("DELETE FROM meta WHERE dictionary = ?", arrayOf(title))
                sqlite.execSQL("DELETE FROM dicts WHERE title = ?", arrayOf(title))
                sqlite.execSQL("DELETE FROM tags WHERE dictionary = ?", arrayOf(title))
                for (name in banks) {
                    val base = name.substringAfterLast('/')
                    val isTerm = TERM_BANK.matches(base)
                    val isTag = TAG_BANK.matches(base)
                    if (!isTerm && !isTag && !META_BANK.matches(base)) continue
                    zf.getInputStream(zf.getEntry(name)).use { stream ->
                        val rows = Json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
                        when {
                            isTerm -> insertTerms(sqlite, rows, title)
                            isTag -> insertTags(sqlite, rows, title)
                            else -> insertMeta(sqlite, rows, title)
                        }
                    }
                }
                val kind = if (hasTerms && hasMeta) "mixed" else if (hasMeta) "meta" else "terms"
                val place = position ?: sqlite.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM dicts", null)
                    .use { c -> c.moveToFirst(); c.getLong(0) }
                sqlite.execSQL(
                    "INSERT INTO dicts(title, revision, kind, enabled, position) VALUES(?,?,?,?,?)",
                    arrayOf(title, revision, kind, enabled, place),
                )
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            return title
        }
    } finally {
        tmp.delete()
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonElement.num(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

private fun insertTerms(sqlite: SQLiteDatabase, rows: Sequence<JsonElement>, dictionary: String) {
    val st = sqlite.compileStatement(
        "INSERT INTO terms(expression, reading, rules, score, glossary, sequence, dictionary, def_tags) VALUES(?,?,?,?,?,?,?,?)"
    )
    try {
        for (row in rows) {
            val r = row as? JsonArray ?: continue
            if (r.size < 6) continue
            val expression = r[0].str() ?: continue
            val reading = r[1].str()?.takeIf { it.isNotEmpty() } ?: expression
            st.clearBindings()
            st.bindString(1, expression)
            st.bindString(2, reading)
            st.bindString(3, r[3].str() ?: "")
            st.bindLong(4, (r[4].num() ?: 0).toLong())
            st.bindString(5, r[5].toString())
            st.bindLong(6, (r.getOrNull(6)?.num() ?: 0).toLong())
            st.bindString(7, dictionary)
            st.bindString(8, r[2].str() ?: "")
            st.executeInsert()
        }
    } finally {
        st.close()
    }
}

private fun insertTags(sqlite: SQLiteDatabase, rows: Sequence<JsonElement>, dictionary: String) {
    val st = sqlite.compileStatement("INSERT INTO tags(dictionary, name, ord) VALUES(?,?,?)")
    try {
        for (row in rows) {
            val r = row as? JsonArray ?: continue
            if (r.size < 3) continue
            val name = r[0].str() ?: continue
            st.clearBindings()
            st.bindString(1, dictionary)
            st.bindString(2, name)
            st.bindLong(3, (r[2].num() ?: 0).toLong())
            st.executeInsert()
        }
    } finally {
        st.close()
    }
}

private fun insertMeta(sqlite: SQLiteDatabase, rows: Sequence<JsonElement>, dictionary: String) {
    val st = sqlite.compileStatement(
        "INSERT INTO meta(expression, reading, mode, value, display, dictionary) VALUES(?,?,?,?,?,?)"
    )
    try {
        for (row in rows) {
            val r = row as? JsonArray ?: continue
            if (r.size < 3) continue
            val expression = r[0].str() ?: continue
            if (r[1].str() != "freq") continue
            var reading: String? = null
            var freq: JsonElement = r[2]
            val obj = freq as? JsonObject
            if (obj != null && "frequency" in obj) {
                reading = obj["reading"]?.str()
                freq = obj.getValue("frequency")
            }
            val value: Int
            val display: String
            if (freq is JsonObject) {
                value = freq["value"]?.num() ?: continue
                display = freq["displayValue"]?.str() ?: value.toString()
            } else {
                value = freq.num() ?: continue
                display = value.toString()
            }
            st.clearBindings()
            st.bindString(1, expression)
            if (reading != null) st.bindString(2, reading) else st.bindNull(2)
            st.bindString(3, "freq")
            st.bindLong(4, value.toLong())
            st.bindString(5, display)
            st.bindString(6, dictionary)
            st.executeInsert()
        }
    } finally {
        st.close()
    }
}
