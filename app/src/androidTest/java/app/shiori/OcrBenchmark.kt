package app.shiori

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.shiori.ocr.MeikiOcrEngine
import app.shiori.ocr.MlKitOcrEngine
import app.shiori.ocr.OcrEngine
import app.shiori.ocr.OcrLine
import app.shiori.ocr.charErrorRate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads the PNGs in files/bench/frames and files/bench/truth.json from the app external files dir
 * (/sdcard/Android/data/app.retromine/files/bench/, see scripts/bench.ps1), runs each engine over
 * every frame and prints the stats to logcat (tag Shiori) and the instrumentation output.
 *
 * Scoring: every truth line is scored against the OCR line with the lowest CER for that frame
 * (an unmatched truth line scores 1.0). Furigana check: frames whose file name contains "furigana"
 * count as merged when a matched OCR line has 2 or more characters beyond the truth line.
 */
class OcrBenchmark {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val lines = StringBuilder()

    private fun say(s: String) {
        Log.i("Shiori", s)
        lines.appendLine(s)
    }

    @Test
    fun benchmark() {
        val dir = File(ctx.getExternalFilesDir(null), "bench")
        val truthFile = File(dir, "truth.json")
        assumeTrue("no bench/truth.json on device", truthFile.exists())
        val truthJson = JSONObject(truthFile.readText())
        val truth = truthJson.keys().asSequence().associateWith { k ->
            val a = truthJson.getJSONArray(k)
            List(a.length()) { a.getString(it) }
        }.filterValues { it.isNotEmpty() }
        assumeTrue("bench/truth.json has no truth lines", truth.isNotEmpty())
        val frames = truth.keys.sorted().filter { File(dir, "frames/$it").exists() }
        assumeTrue("no matching frames in bench/frames", frames.isNotEmpty())

        val engines: List<Pair<String, OcrEngine>> = listOf(
            "mlkit" to MlKitOcrEngine(),
            "meiki" to MeikiOcrEngine(ctx),
        )
        for ((name, engine) in engines) run(name, engine, dir, frames, truth)

        instr.sendStatus(0, Bundle().apply { putString("stream", "\n$lines") })
    }

    private fun run(name: String, engine: OcrEngine, dir: File, frames: List<String>, truth: Map<String, List<String>>) {
        val cers = ArrayList<Double>()
        val frameCer = HashMap<String, Double>()
        val times = ArrayList<Long>()
        val furigana = ArrayList<Pair<String, Boolean>>()
        for ((i, f) in frames.withIndex()) {
            val bmp = BitmapFactory.decodeFile(File(dir, "frames/$f").path)
            if (bmp == null) say("[$name] $f DECODE FAILED")
            assertNotNull("frame $f failed to decode", bmp)
            if (i == 0) runBlocking { engine.read(bmp) } // warm-up: model load and first-run costs
            val t0 = System.nanoTime()
            val result = runBlocking { engine.read(bmp) }
            times += (System.nanoTime() - t0) / 1_000_000
            val perLine = truth.getValue(f).map { exp ->
                val best = result.minByOrNull { charErrorRate(exp, it.text) }
                Triple(exp, best, best?.let { charErrorRate(exp, it.text) } ?: 1.0)
            }
            perLine.forEach { cers += it.third }
            frameCer[f] = perLine.map { it.third }.average()
            if ("furigana" in f) {
                furigana += f to perLine.any { (exp, best, _) -> best != null && best.text.length >= exp.length + 2 }
            }
            say("[$name] $f cer=%.3f ms=%d lines=%s".format(frameCer[f], times.last(), result.map(OcrLine::text)))
        }
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[minOf(sorted.size - 1, ((sorted.size * 9 + 9) / 10) - 1)]
        say("[$name] mean CER %.4f over %d truth lines, %d frames".format(cers.average(), cers.size, frames.size))
        say("[$name] worst 3: " + frameCer.entries.sortedByDescending { it.value }.take(3)
            .joinToString { "${it.key} %.3f".format(it.value) })
        say("[$name] ms per frame: median=$median p90=$p90")
        say("[$name] furigana merged: " + if (furigana.isEmpty()) "no furigana frames" else furigana.joinToString { "${it.first}=${it.second}" })
    }
}
