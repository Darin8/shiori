package app.shiori.session

import app.shiori.ocr.Box
import app.shiori.ocr.OcrLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameContentTest {
    private fun line(t: String, l: Float) = OcrLine(t, Box(l, 100f, l + 200f, 140f), emptyList(), false)

    @Test fun jitterWithinToleranceIsSame() = assertTrue(sameContent(listOf(line("助かる", 50f)), listOf(line("助かる", 53f))))
    @Test fun movedBoxIsDifferent() = assertFalse(sameContent(listOf(line("助かる", 50f)), listOf(line("助かる", 60f))))
    @Test fun changedTextIsDifferent() = assertFalse(sameContent(listOf(line("a", 50f)), listOf(line("b", 50f))))
    @Test fun differentCountIsDifferent() = assertFalse(sameContent(listOf(line("a", 50f)), emptyList()))
}
