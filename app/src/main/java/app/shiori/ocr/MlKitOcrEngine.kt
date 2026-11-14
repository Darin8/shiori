package app.shiori.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Google ML Kit Japanese text recognition (bundled model). Symbol boxes become per-character boxes. */
class MlKitOcrEngine : OcrEngine {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun read(frame: Bitmap): List<OcrLine> {
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            val box = Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val symbols = line.elements.flatMap { it.symbols }
            val chars = symbols.mapNotNull { s ->
                s.boundingBox?.let {
                    OcrChar(s.text, Box(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()))
                }
            }
            val usable = chars.isNotEmpty() && chars.size == symbols.size
            // OcrLine invariant: text is exactly the concatenated char texts.
            val lineText = if (usable) chars.joinToString("") { it.text } else line.text
            val vertical = box.height > 2 * box.width &&
                (if (usable) chars.size else lineText.length) >= 3
            out += OcrLine(lineText, box, if (usable) chars else spreadChars(lineText, box, vertical), vertical)
        }
        return out
    }

    fun close() {
        recognizer.close()
    }
}
