package app.shiori.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineCacheTest {
    private val cache = LineCache(capacity = 4)
    private var recognised = 0

    /** A box's content stands in for its pixels: equal content gives equal signatures. */
    private fun sig(content: Int) = LineSig(
        ByteArray(100) { if (it < (content % 9) * 10) 1 else 0 }.also { b -> b[99] = (content % 3).toByte() },
    )
    private fun box(x: Float, y: Float = 100f) = Box(x, y, x + 400f, y + 40f)
    private fun line(text: String, b: Box) = OcrLine(text, b, text.map { OcrChar(it.toString(), b) }, false)

    private fun pass(
        settle: Boolean,
        vararg boxes: Pair<Box, Int>,
        text: (Int) -> String = { "t$it" },
    ): LineCache.Outcome = runBlocking {
        val contents = boxes.associate { it.first to it.second }
        cache.process(boxes.map { LineDet(it.first, false) }, { sig(contents.getValue(it.box)) }, settle) { dets ->
            recognised += dets.size
            dets.map { line(text(contents.getValue(it.box)), it.box) }
        }
    }

    @Test
    fun `identical box is reused without recognising again`() {
        pass(false, box(10f) to 1)
        val o = pass(true, box(10f) to 1)
        assertEquals(1, recognised)
        assertEquals(1, o.reused)
        assertEquals(listOf("t1"), o.lines.map { it.text })
    }

    @Test
    fun `changed text is recognised once it is stable for one more tick, the old text stays meanwhile`() {
        pass(false, box(10f) to 1)
        val first = pass(true, box(10f) to 2)
        assertEquals(0, first.recognized)
        assertEquals(1, first.deferred)
        assertEquals(listOf("t1"), first.lines.map { it.text })
        assertTrue(cache.hasPending)
        val second = pass(true, box(10f) to 2)
        assertEquals(1, second.recognized)
        assertEquals(listOf("t2"), second.lines.map { it.text })
        assertFalse(cache.hasPending)
        assertEquals(2, recognised)
    }

    @Test
    fun `text that keeps changing is held back, then read once it was held MAX_DEFER times`() {
        pass(false, box(10f) to 1)
        for (c in 2..(1 + ChangeTuning.MAX_DEFER + 1)) {
            val o = pass(true, box(10f) to c)
            assertEquals(0, o.recognized)
            assertEquals(listOf("t1"), o.lines.map { it.text })
        }
        val o = pass(true, box(10f) to 9)
        assertEquals(1, o.recognized)
        assertEquals(listOf("t9"), o.lines.map { it.text })
        assertFalse(cache.hasPending)
    }

    @Test
    fun `a new line behind a moving background still shows up`() {
        // Dialogue advances: the new line's signature differs on every tick.
        var shown = emptyList<String>()
        for (c in listOf(1, 2, 3, 4, 5)) shown = pass(true, box(10f) to c).lines.map { it.text }
        assertEquals(listOf("t4"), shown)
    }

    @Test
    fun `a noisy box re-read with the same text is then reused despite more jitter`() {
        // sig(1) vs sig(2) differ by 10%; the text is forced equal so the cache learns the box is noisy.
        val same = { _: Int -> "same" }
        pass(false, box(10f) to 1, text = same)
        pass(false, box(10f) to 2, text = same)
        val o = pass(true, box(10f) to 3, text = same)
        assertEquals(1, o.reused)
    }

    @Test
    fun `two detections of the same line are shown once`() {
        val b = box(10f)
        val l = line("x", b)
        assertEquals(1, dedupe(listOf(l, line("x", Box(b.left + 2f, b.top, b.right + 2f, b.bottom)))).size)
        assertEquals(2, dedupe(listOf(l, line("y", Box(b.left + 2f, b.top, b.right + 2f, b.bottom)))).size)
    }

    @Test
    fun `a brand-new box shows nothing until it settles`() {
        val a = pass(true, box(10f, 100f) to 1)
        assertEquals(emptyList<OcrLine>(), a.lines)
        val b = pass(true, box(10f, 100f) to 1)
        assertEquals(listOf("t1"), b.lines.map { it.text })
    }

    @Test
    fun `a moved box reuses the cached line shifted to the new place`() {
        pass(false, box(10f) to 1)
        val o = pass(true, Box(14f, 102f, 414f, 142f) to 1)
        assertEquals(1, recognised)
        assertEquals(1, o.reused)
        val l = o.lines.single()
        assertEquals(Box(14f, 102f, 414f, 142f), l.box)
        assertEquals(Box(14f, 102f, 414f, 142f), l.chars.first().box)
    }

    @Test
    fun `a new box elsewhere is recognised while the old one is reused`() {
        pass(false, box(10f, 100f) to 1)
        pass(true, box(10f, 100f) to 1, box(10f, 300f) to 7)
        val o = pass(true, box(10f, 100f) to 1, box(10f, 300f) to 7)
        assertEquals(listOf("t1", "t7"), o.lines.map { it.text })
        assertEquals(1, o.reused)
        assertEquals(1, o.recognized)
    }

    @Test
    fun `hotkey passes read every miss at once and still reuse hits`() {
        pass(false, box(10f, 100f) to 1)
        val o = pass(false, box(10f, 100f) to 1, box(10f, 300f) to 9)
        assertEquals(1, o.reused)
        assertEquals(1, o.recognized)
        assertEquals(0, o.deferred)
        assertEquals(listOf("t1", "t9"), o.lines.map { it.text })
        assertFalse(cache.hasPending)
    }

    @Test
    fun `a box that vanishes drops its line`() {
        pass(false, box(10f, 100f) to 1, box(10f, 300f) to 2)
        val o = pass(true, box(10f, 100f) to 1)
        assertEquals(listOf("t1"), o.lines.map { it.text })
    }

    @Test
    fun `reset forgets everything`() {
        pass(false, box(10f) to 1)
        cache.reset()
        val o = pass(true, box(10f) to 1)
        assertEquals(0, o.reused)
        assertEquals(emptyList<OcrLine>(), o.lines)
    }

    @Test
    fun `the cache keeps only the newest entries`() {
        for (i in 0 until 6) pass(false, box(10f, 10f + i * 60f) to i + 1)
        val o = pass(true, box(10f, 10f) to 1, box(10f, 10f + 5 * 60f) to 6)
        assertEquals(1, o.reused) // the oldest were dropped, the newest remain
    }

    @Test
    fun `a box with no text is cached too`() {
        val empty: suspend (List<LineDet>) -> List<OcrLine?> = { d -> recognised += d.size; d.map { null } }
        runBlocking {
            cache.process(listOf(LineDet(box(10f), false)), { sig(1) }, false, empty)
            val o = cache.process(listOf(LineDet(box(10f), false)), { sig(1) }, true, empty)
            assertEquals(1, recognised)
            assertEquals(1, o.reused)
            assertEquals(emptyList<OcrLine>(), o.lines)
        }
    }
}
