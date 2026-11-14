package app.shiori.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

data class TermEntry(
    val expression: String,
    val reading: String,
    val rules: Set<String>,
    val score: Int,
    val glossaryJson: String,
    val sequence: Int,
    val dictionary: String,
    /** Term-row definition tags (e.g. "priority form", "★"), ordered like Yomitan's tag grouping (tag order, then name). */
    val definitionTags: List<String> = emptyList(),
    /** Position of the entry's dictionary in the user's dictionary order (Yomitan's dictionaryIndex). */
    val dictionaryIndex: Int = 0,
)

data class Frequency(val dictionary: String, val value: Int, val display: String)

data class DictInfo(val title: String, val revision: String?, val enabled: Boolean, val position: Int)

/** Yomitan dictionaries in SQLite. name = null gives an in-memory database. */
class DictionaryDb(context: Context, name: String? = "dict.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context, name, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE terms(expression TEXT NOT NULL, reading TEXT NOT NULL, rules TEXT NOT NULL, " +
                    "score INTEGER NOT NULL, glossary TEXT NOT NULL, sequence INTEGER NOT NULL, dictionary TEXT NOT NULL, " +
                    "def_tags TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL("CREATE INDEX terms_expression ON terms(expression)")
            db.execSQL("CREATE INDEX terms_reading ON terms(reading)")
            db.execSQL(
                "CREATE TABLE meta(expression TEXT NOT NULL, reading TEXT, mode TEXT NOT NULL, " +
                    "value INTEGER NOT NULL, display TEXT NOT NULL, dictionary TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX meta_expression ON meta(expression)")
            db.execSQL(
                "CREATE TABLE dicts(title TEXT NOT NULL UNIQUE, revision TEXT, kind TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, position INTEGER)"
            )
            db.execSQL("CREATE TABLE tags(dictionary TEXT NOT NULL, name TEXT NOT NULL, ord INTEGER NOT NULL)")
        }

        // Version 2 added definition tags (older databases must be imported again). Version 3 adds the dictionary
        // manager's columns in place: never drop tables for it, the imported rows are expensive to rebuild.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                for (table in listOf("terms", "meta", "dicts", "tags")) db.execSQL("DROP TABLE IF EXISTS $table")
                onCreate(db)
                return
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE dicts ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE dicts ADD COLUMN position INTEGER")
                db.execSQL("UPDATE dicts SET position = rowid")
                val n = db.rawQuery("SELECT COUNT(*) FROM dicts", null).use { c -> c.moveToFirst(); c.getLong(0) }
                android.util.Log.i("Shiori", "dict db migrated v$oldVersion to v$newVersion, kept $n dictionaries")
            }
        }
    }

    internal val db: SQLiteDatabase get() = helper.writableDatabase

    private fun tagOrders(): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        db.rawQuery("SELECT dictionary, name, ord FROM tags", null).use { c ->
            while (c.moveToNext()) out[c.getString(0) to c.getString(1)] = c.getInt(2)
        }
        return out
    }

    fun findTerms(texts: Collection<String>): List<TermEntry> {
        val out = ArrayList<TermEntry>()
        val orders by lazy { tagOrders() }
        for (chunk in texts.distinct().chunked(MAX_PARAMS / 2)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = (chunk + chunk).toTypedArray()
            db.rawQuery(
                "SELECT t.expression, t.reading, t.rules, t.score, t.glossary, t.sequence, t.dictionary, t.def_tags, " +
                    "d.position FROM terms t JOIN dicts d ON d.title = t.dictionary " +
                    "WHERE d.enabled = 1 AND (t.expression IN ($marks) OR t.reading IN ($marks)) " +
                    "ORDER BY d.position, t.score DESC, t.rowid",
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out += TermEntry(
                        expression = c.getString(0),
                        reading = c.getString(1),
                        rules = c.getString(2).split(' ').filter { it.isNotEmpty() }.toSet(),
                        score = c.getInt(3),
                        glossaryJson = c.getString(4),
                        sequence = c.getInt(5),
                        dictionary = c.getString(6),
                        definitionTags = c.getString(7).split(' ').filter { it.isNotEmpty() }.let { names ->
                            val dict = c.getString(6)
                            names.distinct().sortedWith(compareBy({ orders[dict to it] ?: 0 }, { it }))
                        },
                        dictionaryIndex = c.getInt(8),
                    )
                }
            }
        }
        return out
    }

    fun frequencies(expression: String, reading: String): List<Frequency> {
        val out = ArrayList<Frequency>()
        db.rawQuery(
            "SELECT m.dictionary, m.value, m.display FROM meta m JOIN dicts d ON d.title = m.dictionary " +
                "WHERE d.enabled = 1 AND m.expression = ? AND m.mode = 'freq' AND (m.reading IS NULL OR m.reading = ?) " +
                "ORDER BY d.position, m.rowid",
            arrayOf(expression, reading),
        ).use { c ->
            while (c.moveToNext()) out += Frequency(c.getString(0), c.getInt(1), c.getString(2))
        }
        return out
    }

    /** All imported dictionaries in the user's order. */
    fun dictionaryInfos(): List<DictInfo> {
        val out = ArrayList<DictInfo>()
        db.rawQuery("SELECT title, revision, enabled FROM dicts ORDER BY position, rowid", null).use { c ->
            while (c.moveToNext()) out += DictInfo(c.getString(0), c.getString(1), c.getInt(2) != 0, out.size + 1)
        }
        return out
    }

    /** Titles of all imported dictionaries in order. */
    fun dictionaries(): List<String> = dictionaryInfos().map { it.title }

    fun enabledDictionaries(): List<String> = dictionaryInfos().filter { it.enabled }.map { it.title }

    fun setEnabled(title: String, enabled: Boolean) {
        db.execSQL("UPDATE dicts SET enabled = ? WHERE title = ?", arrayOf(if (enabled) 1 else 0, title))
    }

    /** Moves [title] one place up (delta -1) or down (+1) and renumbers positions 1..n. */
    fun move(title: String, delta: Int) {
        val titles = dictionaries().toMutableList()
        val i = titles.indexOf(title)
        val j = i + delta
        if (i < 0 || j !in titles.indices) return
        titles[i] = titles[j].also { titles[j] = titles[i] }
        db.beginTransaction()
        try {
            titles.forEachIndexed { k, t -> db.execSQL("UPDATE dicts SET position = ? WHERE title = ?", arrayOf(k + 1, t)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Removes the dictionary's rows from every table in one transaction. */
    fun delete(title: String) {
        db.beginTransaction()
        try {
            for (table in listOf("terms", "meta", "tags")) db.execSQL("DELETE FROM $table WHERE dictionary = ?", arrayOf(title))
            db.execSQL("DELETE FROM dicts WHERE title = ?", arrayOf(title))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Row counts per table, for logging. */
    internal fun counts(): Map<String, Long> = listOf("terms", "meta", "dicts", "tags").associateWith { table ->
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    override fun close() = helper.close()

    private companion object {
        const val MAX_PARAMS = 500
    }
}
