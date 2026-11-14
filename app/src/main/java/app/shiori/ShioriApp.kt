package app.shiori

import android.app.Application
import app.shiori.anki.AnkiExporter
import app.shiori.capture.CapturedFrame
import app.shiori.dict.Deinflector
import app.shiori.dict.DictionaryDb
import app.shiori.dict.Lookup
import app.shiori.ocr.defaultOcrEngine

/** App-level singletons shared by the setup screen and the hotkey service. */
class ShioriApp : Application() {
    val dictionaryDb by lazy { DictionaryDb(this) }
    val deinflector by lazy {
        Deinflector(assets.open("japanese-transforms.json").bufferedReader().use { it.readText() })
    }
    val lookup by lazy { Lookup(dictionaryDb, deinflector) }
    val ocr by lazy { defaultOcrEngine(this) }
    val exporter by lazy { AnkiExporter(this) }

    /** The latest frame any pass captured (hotkey or live), in memory only; the region editor starts from it. */
    @Volatile var latestFrame: CapturedFrame? = null
}
