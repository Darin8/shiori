package app.shiori

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.shiori.ocr.MlKitOcrEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's OCR engine on the newest saved frames (files/frames) and writes files/align/annot-<name>.png:
 * line boxes outlined in blue, char boxes filled in alternating translucent red and green.
 * Every box is logged (tag Shiori, prefix "align").
 */
class AlignmentDebug {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun annotate() {
        val frames = File(ctx.getExternalFilesDir(null), "frames").listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }?.take(2).orEmpty()
        assumeTrue("no saved frames", frames.isNotEmpty())
        val out = File(ctx.getExternalFilesDir(null), "align").apply { mkdirs() }
        val which = InstrumentationRegistry.getArguments().getString("engine", "default")
        val engine: app.shiori.ocr.OcrEngine = when (which) {
            "mlkit" -> MlKitOcrEngine()
            "meiki" -> app.shiori.ocr.MeikiOcrEngine(ctx)
            else -> app.shiori.ocr.defaultOcrEngine(ctx)
        }
        val line = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 3f }
        val fills = listOf(Color.argb(90, 255, 0, 0), Color.argb(90, 0, 255, 0))
        val fill = Paint().apply { style = Paint.Style.FILL }
        for (f in frames) {
            val bmp = BitmapFactory.decodeFile(f.path).copy(Bitmap.Config.ARGB_8888, true)
            runBlocking { engine.read(bmp) }
            val t0 = System.nanoTime()
            val lines = runBlocking { engine.read(bmp) }
            Log.i("Shiori", "align $which ms=${(System.nanoTime() - t0) / 1_000_000}")
            Log.i("Shiori", "align ${f.name} ${bmp.width}x${bmp.height} lines=${lines.size}")
            val c = Canvas(bmp)
            for (l in lines) {
                l.chars.forEachIndexed { i, x ->
                    fill.color = fills[i % 2]
                    c.drawRect(x.box.left, x.box.top, x.box.right, x.box.bottom, fill)
                }
                c.drawRect(l.box.left, l.box.top, l.box.right, l.box.bottom, line)
                Log.i("Shiori", "align line '${l.text}' ${l.box} " + l.chars.joinToString(" ") {
                    "${it.text}[${it.box.left.toInt()},${it.box.top.toInt()},${it.box.right.toInt()},${it.box.bottom.toInt()}]"
                })
            }
            File(out, "annot-$which-${f.name}").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
