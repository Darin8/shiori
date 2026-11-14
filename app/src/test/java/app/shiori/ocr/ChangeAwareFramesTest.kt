package app.shiori.ocr

import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Thresholds against local test frames (1280x960, skipped when absent): the same frame, the same frame with
 * capture noise and a brightness shift, a small shift, and another dialogue frame.
 */
class ChangeAwareFramesTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, ".superpowers/sdd/2026-11-11-retromine/frames") }.firstOrNull { it.isDirectory }
    private val dialogue1 = "frame-1794462777188.png"
    private val dialogue2 = "frame-1794503882356.png"
    private val launcher = "frame-1794463199946.png"

    /** The text window at the bottom, and the first dialogue line box (same place in both dialogue frames). */
    private val region = Box(60f, 590f, 1220f, 880f)
    private val line1 = Box(190f, 680f, 1090f, 740f)
    private val line2 = Box(160f, 750f, 1125f, 805f)

    private fun load(name: String): BufferedImage {
        assumeTrue("frames not found", dir != null && File(dir, name).exists())
        return ImageIO.read(File(dir, name))
    }

    private fun px(img: BufferedImage): IntArray =
        IntArray(img.width * img.height) { img.getRGB(it % img.width, it / img.width) }

    private fun luma(
        img: BufferedImage, noise: Int = 0, bright: Int = 0, dx: Int = 0, dy: Int = 0, seed: Long = 1,
    ): LumaFrame {
        val w = img.width
        val h = img.height
        val rnd = Random(seed)
        val src = px(img)
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[(y - dy).coerceIn(0, h - 1) * w + (x - dx).coerceIn(0, w - 1)]
            fun ch(v: Int) = (v + bright + if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0).coerceIn(0, 255)
            out[y * w + x] = (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or
                (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
        return LumaFrame.fromArgb(w, h, out)
    }

    /** Paints one character-wide patch (about 30 px) of the line with a flat colour: a one-glyph change. */
    private fun erase(img: BufferedImage, box: Box): BufferedImage {
        val c = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        c.graphics.drawImage(img, 0, 0, null)
        c.graphics.color = java.awt.Color(40, 60, 60)
        c.graphics.fillRect((box.left + 300).toInt(), box.top.toInt(), 34, box.height.toInt())
        return c
    }

    private fun regionMad(a: LumaFrame, b: LumaFrame): Float {
        val cols = ChangeTuning.REGION_COLS
        val rows = (cols * region.height / region.width).toInt().coerceIn(2, ChangeTuning.REGION_MAX_ROWS)
        return meanAbsDiff(a.thumb(region, cols, rows), b.thumb(region, cols, rows))
    }

    @Test
    fun `region gate - same frame and capture noise are unchanged (a brightness step counts as a change), other dialogue is changed`() {
        val a = load(dialogue1)
        val b = load(dialogue2)
        val base = luma(a)
        val same = regionMad(base, luma(a))
        val noisy = regionMad(base, luma(a, noise = 6, seed = 7))
        val bright = regionMad(base, luma(a, bright = 1))
        val other = regionMad(base, luma(b))
        val glyph = regionMad(base, luma(erase(a, line1)))
        println("region MAD oneGlyph=$glyph")
        assertTrue("one glyph $glyph", glyph > ChangeTuning.REGION_MAD)
        println(
            "region MAD same=$same noise6=$noisy bright+1=$bright (counts as changed) " +
                "otherDialogue=$other threshold=${ChangeTuning.REGION_MAD}",
        )
        assertEquals(0f, same, 0f)
        assertTrue("noise $noisy", noisy <= ChangeTuning.REGION_MAD)
        assertTrue("other $other", other > ChangeTuning.REGION_MAD)
    }

    @Test
    fun `line signature - same, noisy and moved-with-box lines match, one glyph or other text does not`() {
        val a = load(dialogue1)
        val b = load(dialogue2)
        val base = luma(a)
        for ((name, box) in listOf("line1" to line1, "line2" to line2)) {
            val s0 = LineSig.of(base, box)
            val noisy = s0.diff(LineSig.of(luma(a, noise = 6, seed = 5), box))
            val bright = s0.diff(LineSig.of(luma(a, bright = 2), box))
            // The detector box moves with the text (here 1 px right): the crop is the same text.
            val moved = Box(box.left + 1, box.top, box.right + 1, box.bottom)
            val shifted = s0.diff(LineSig.of(luma(a, dx = 1), moved))
            val fixedBox1px = s0.diff(LineSig.of(luma(a, dx = 1, dy = 1), box))
            println("sig $name content moved 1px inside a fixed box: $fixedBox1px")
            val erased = erase(a, box)
            val oneChar = s0.diff(LineSig.of(luma(erased), box))
            println("sig $name oneCharErased=$oneChar")
            val other = s0.diff(LineSig.of(luma(b), box))
            val launch = s0.diff(LineSig.of(luma(load(launcher)), box))
            println(
                "sig $name same=0 noise6=$noisy bright+2=$bright movedBox=$shifted otherDialogue=$other " +
                    "launcher=$launch max=${ChangeTuning.SIG_MAX_DIFF}",
            )
            assertEquals(0f, s0.diff(LineSig.of(luma(a), box)), 0f)
            assertTrue("$name noisy $noisy", noisy <= ChangeTuning.SIG_MAX_DIFF)
            assertTrue("$name bright $bright", bright <= ChangeTuning.SIG_MAX_DIFF)
            assertTrue("$name moved box $shifted", shifted <= ChangeTuning.SIG_MAX_DIFF)
            assertTrue("$name one glyph $oneChar", oneChar > ChangeTuning.SIG_MAX_DIFF)
            assertTrue("$name other $other", other > ChangeTuning.SIG_MAX_DIFF)
            assertTrue("$name launcher $launch", launch > ChangeTuning.SIG_MAX_DIFF)
        }
    }

    @Test
    fun `cache - same frame hits, jittered frame hits, other dialogue misses`() {
        val a = load(dialogue1)
        val b = load(dialogue2)
        val cache = LineCache()
        val det = LineDet(line1, false)
        fun ocr(text: String) = OcrLine(text, line1, listOf(OcrChar(text, line1)), false)
        var recognised = 0
        fun run(f: LumaFrame, settle: Boolean, text: String) = kotlinx.coroutines.runBlocking {
            cache.process(listOf(det), { LineSig.of(f, it.box) }, settle) {
                recognised += it.size
                it.map { _ -> ocr(text) }
            }
        }
        assertEquals(1, run(luma(a), false, "A").recognized)
        assertEquals(1, run(luma(a, noise = 6, seed = 9), true, "X").reused)
        assertEquals(1, recognised)
        val miss = run(luma(b), true, "B")
        assertEquals(0, miss.reused)
        assertEquals(1, miss.deferred)
        assertEquals("A", miss.lines.single().text) // previous layer stays while it settles
        val settled = run(luma(b, noise = 4, seed = 2), true, "B")
        assertEquals(1, settled.recognized)
        assertEquals("B", settled.lines.single().text)
    }
}
