package app.shiori.overlay

import app.shiori.ocr.Box
import app.shiori.ocr.OcrChar
import app.shiori.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitTesterTest {
    private fun hLine(text: String, left: Float, top: Float, size: Float = 20f): OcrLine {
        val chars = text.mapIndexed { i, c ->
            OcrChar(c.toString(), Box(left + size * i, top, left + size * (i + 1), top + size))
        }
        return OcrLine(text, Box(left, top, left + size * text.length, top + size), chars, false)
    }

    private fun vLine(text: String, left: Float, top: Float, size: Float = 20f): OcrLine {
        val chars = text.mapIndexed { i, c ->
            OcrChar(c.toString(), Box(left, top + size * i, left + size, top + size * (i + 1)))
        }
        return OcrLine(text, Box(left, top, left + size, top + size * text.length), chars, true)
    }

    @Test fun `tap inside a char box hits it`() {
        val lines = listOf(hLine("こんにちは", 100f, 100f))
        assertEquals(Hit(0, 2), hitTest(lines, 150f, 110f))
    }

    @Test fun `tap within slop of a char hits the nearest`() {
        val lines = listOf(hLine("こんにちは", 100f, 100f))
        // 5px above the line, over char index 1 (x 120..140)
        assertEquals(Hit(0, 1), hitTest(lines, 125f, 95f))
        // 8px left of the line start
        assertEquals(Hit(0, 0), hitTest(lines, 92f, 110f))
    }

    @Test fun `tap in empty space returns null`() {
        val lines = listOf(hLine("こんにちは", 100f, 100f))
        assertNull(hitTest(lines, 500f, 500f))
        assertNull(hitTest(lines, 125f, 80f)) // 20px above, slop is 12
    }

    @Test fun `hitTest returns null when there are no lines`() {
        assertNull(hitTest(emptyList(), 10f, 10f))
    }

    @Test fun `vertical line hits by y order`() {
        val lines = listOf(hLine("あい", 10f, 10f), vLine("たべる", 300f, 100f))
        assertEquals(Hit(1, 0), hitTest(lines, 310f, 105f))
        assertEquals(Hit(1, 1), hitTest(lines, 310f, 125f))
        assertEquals(Hit(1, 2), hitTest(lines, 310f, 145f))
    }

    @Test fun `empty region list keeps everything`() {
        val lines = listOf(hLine("あい", 10f, 10f), hLine("うえ", 600f, 500f))
        assertEquals(lines, linesInRegions(lines, emptyList()))
    }

    @Test fun `lines centred outside every region are dropped`() {
        val inside = hLine("あい", 110f, 110f)
        val outside = hLine("うえ", 600f, 500f)
        // straddles the edge but its centre is outside
        val straddle = hLine("おか", 190f, 110f)
        val region = Box(100f, 100f, 200f, 200f)
        assertEquals(listOf(inside), linesInRegions(listOf(inside, outside, straddle), listOf(region)))
    }

    @Test fun `multiple regions keep lines from each`() {
        val a = hLine("あい", 110f, 110f)
        val b = hLine("うえ", 610f, 510f)
        val c = hLine("おか", 900f, 900f)
        val regions = listOf(Box(100f, 100f, 200f, 200f), Box(600f, 500f, 700f, 600f))
        assertEquals(listOf(a, b), linesInRegions(listOf(a, b, c), regions))
    }
}
