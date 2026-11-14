package app.shiori

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.shiori.ocr.Box
import app.shiori.ocr.MeikiOcrEngine
import app.shiori.ocr.ReadContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Times detect against recognise on the device and checks the cache on real frames. Reads the PNGs in
 * files/bench/frames (see scripts/bench.ps1). Per frame: live pass 1 (settling), live pass 2 (recognises),
 * live pass 3 (all reused), hotkey pass (reuses). Results go to logcat, tag Shiori, prefix "changeaware".
 */
class ChangeAwareDevice {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cacheAndTiming() {
        val dir = File(ctx.getExternalFilesDir(null), "bench/frames")
        val frames = dir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no bench/frames on device", frames.isNotEmpty())
        val engine = MeikiOcrEngine(ctx)
        runBlocking {
            for (f in frames) {
                val bmp = BitmapFactory.decodeFile(f.path)
                engine.reset()
                engine.read(bmp) // warm up the model sessions
                val live = ReadContext(true, emptyList())
                val results = (1..3).map {
                    val t = System.nanoTime()
                    val lines = engine.read(bmp, live)
                    val ms = (System.nanoTime() - t) / 1_000_000
                    val st = engine.lastStats!!
                    Log.i("Shiori", "changeaware ${f.name} live$it lines=${lines.size} total=${ms}ms detect=${st.detectMs}ms recog=${st.recogMs}ms reused=${st.reused} recognized=${st.recognized}")
                    lines to st
                }
                assertEquals(0, results[0].second.recognized.coerceAtMost(0))
                val third = results[2].second
                assertEquals("third live pass reuses everything", 0, third.recognized)
                assertEquals(results[1].first.map { it.text }, results[2].first.map { it.text })
                val t = System.nanoTime()
                val hot = engine.read(bmp, ReadContext(false, emptyList()))
                Log.i("Shiori", "changeaware ${f.name} hotkey lines=${hot.size} total=${(System.nanoTime() - t) / 1_000_000}ms stats=${engine.lastStats}")
                assertEquals(0, engine.lastStats!!.recognized)
                assertTrue(hot.map { it.text } == results[1].first.map { it.text })
            }
        }
    }
}
