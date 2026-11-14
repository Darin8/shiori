package app.shiori.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.shiori.dict.Frequency
import app.shiori.dict.LookupResult
import app.shiori.dict.TermEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lays the popup out at the Nova's 1280x960 and writes PNGs for eyeballing; also checks the width is fixed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-xhdpi")
class PopupSnapshotTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun fixture(name: String) = javaClass.getResource("/popup/$name.json")!!.readText()
    private val jpdb = Frequency("JPDBv2㋕", 11058, "11058")
    private val dict = "Jitendex.org [2025-02-11]"

    private fun result(expr: String, reading: String, json: String, reasons: List<String> = emptyList(), matched: String = expr) =
        LookupResult(
            TermEntry(expr, reading, emptySet(), 0, json, 1, dict, listOf("★")),
            matched, reasons, listOf(jpdb),
        )

    private val results = mapOf(
        "tagg" to listOf(
            result("タッグ", "タッグ", fixture("taggu")), result("タグ", "タグ", fixture("taggu")),
            result("荷札", "にふだ", fixture("taggu")), result("札", "ふだ", fixture("taggu")),
            result("タッグ戦", "タッグせん", fixture("taggu")), result("タグる", "タグる", fixture("taggu")),
        ),
        "tasukaru" to listOf(result("助かる", "たすかる", fixture("tasukaru"), listOf("volitional"), "助かろう")),
        "owaru" to listOf(result("終わる", "おわる", fixture("owaru"))),
        "long" to listOf(result("終わる", "おわる", longGlossary())),
        "longword" to listOf(
            result("取り返しがつかない", "とりかえしがつかない", fixture("owaru")), result("使いこなす", "つかいこなす", fixture("owaru")),
        ),
    )

    private fun longGlossary(): String {
        val lis = (1..30).joinToString(",") { """{"tag":"li","data":{"sense-number":"$it"},"content":{"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"sense number $it with a fairly long gloss text"}}}""" }
        return """[{"type":"structured-content","content":{"tag":"div","content":[{"tag":"span","data":{"code":"n"},"content":"noun"},{"tag":"ol","content":[$lis]}]}}]"""
    }

    private fun dump(v: android.view.View, d: Int) {
        println("DUMP " + " ".repeat(d) + v.javaClass.simpleName + " " + v.measuredWidth + "x" + v.measuredHeight)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), d + 1)
    }

    private fun render(name: String, state: AddState = AddState.READY, label: String? = null): EntryPopup {
        val p = EntryPopup(ctx, results.getValue(name), state, onAdd = {}, unavailableLabel = label)
        val v = p.view
        v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#4A6B3A"))
        c.translate(60f, 60f)
        v.draw(c)
        val dir = File(System.getProperty("popup.out") ?: "build/popup")
        dir.mkdirs()
        File(dir, "popup-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return p
    }

    @Test fun widthIsFixedWhateverTheContentOrSpec() {
        for (n in results.keys) {
            val p = render(n)
            assertEquals(p.widthPx, p.view.measuredWidth)
            p.view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
            assertEquals(p.widthPx, p.view.measuredWidth)
            assertTrue("$n ${p.view.measuredHeight}", p.view.measuredHeight <= 960 * 0.55f + 40)
        }
    }

    @Test fun headwordIsNeverClipped() {
        for (expr in listOf("使いこなす", "取り返しがつかない", "どうにもこうにもならない状況に陥る")) {
            val p = EntryPopup(ctx, listOf(result(expr, "つかいこなす", fixture("owaru"))), AddState.READY, onAdd = {})
            p.view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST))
            val card = (p.view as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
            val head = card.getChildAt(0) as android.view.ViewGroup
            val hw = head.getChildAt(0)
            val add = head.getChildAt(1)
            // Headword and Add share the top row: Add keeps its size at the right, the headword takes the rest and may wrap.
            assertEquals(expr, card.measuredWidth - card.paddingLeft - card.paddingRight, head.measuredWidth)
            assertTrue(expr, hw.measuredWidth in 1..(head.measuredWidth - add.measuredWidth))
            assertTrue(expr, add.measuredHeight >= (44 * ctx.resources.displayMetrics.density).toInt())
        }
    }

    private fun resultIn(dictName: String, expr: String, reading: String, json: String) =
        LookupResult(TermEntry(expr, reading, emptySet(), 0, json, 1, dictName, listOf("★")), expr, emptyList(), listOf(jpdb))

    /** Fake game frame: text lines, the tapped word highlighted, the popup placed by the real placement and its leader. */
    private fun scene(file: String, popup: EntryPopup, lines: List<Box>, word: Box) {
        val v = popup.view as HeightLimited
        val root = popup.view
        fun measure() = root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST))
        measure()
        val text = Box(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })
        val d = ctx.resources.displayMetrics.density
        val gap = (9 * d).toInt()
        var pl = placePopupClear(1280, 960, word, text, root.measuredWidth, root.measuredHeight, gap = gap)
        if (pl.maxHeight != null) { v.limitHeight(pl.maxHeight); measure(); pl = placePopupClear(1280, 960, word, text, root.measuredWidth, root.measuredHeight, gap = gap) }
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#4A6B3A"))
        val paint = android.graphics.Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true }
        c.drawRect(text.left - 20, text.top - 10, text.right + 20, text.bottom + 10, android.graphics.Paint().apply { color = Color.argb(150, 0, 0, 0) })
        for (l in lines) c.drawText("こんにちは、今日はいい天気ですね。 これはテストです。", l.left, l.bottom - 8, paint)
        drawWordHighlight(c, android.graphics.RectF(word.left, word.top, word.right, word.bottom), d)
        val card = Box(pl.pos.left + 8 * d, pl.pos.top + 8 * d, pl.pos.left + root.measuredWidth - 8 * d, pl.pos.top + root.measuredHeight - 8 * d)
        leaderLine(card, word, 14f * d)?.let { drawLeader(c, it, d) }
        c.save(); c.translate(pl.pos.left.toFloat(), pl.pos.top.toFloat()); root.draw(c); c.restore()
        val dir = File(System.getProperty("popup.out") ?: "build/popup")
        dir.mkdirs()
        File(dir, "popup-v3-$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(pl.pos.top >= 0 && pl.pos.top + root.measuredHeight <= 960)
        if (word.top < text.bottom) assertTrue("covers text", pl.pos.top + root.measuredHeight - 8 * d <= text.top || pl.pos.top + 8 * d >= text.bottom || pl.maxHeight == null)
    }

    private fun lineBoxes(top: Float, n: Int) = (0 until n).map { Box(90f, top + it * 56f, 1190f, top + it * 56f + 44f) }

    @Test fun v3Renders() {
        val mid = lineBoxes(640f, 3)
        scene("short", EntryPopup(ctx, results.getValue("owaru"), AddState.READY, onAdd = {}), mid, Box(100f, 650f, 190f, 684f))
        scene("longword", EntryPopup(ctx, listOf(result("取り返しがつかない", "とりかえしがつかない", fixture("owaru"))), AddState.READY, onAdd = {}), mid, Box(500f, 650f, 800f, 684f))
        val multi = listOf(
            resultIn("Jitendex.org [2025-02-11]", "終わる", "おわる", fixture("owaru")),
            resultIn("JMdict [2026-11-11]", "終わる", "おわる", fixture("taggu")),
            resultIn("Jitendex.org [2025-02-11]", "終わり", "おわり", fixture("owaru")),
        )
        scene("multi", EntryPopup(ctx, multi, AddState.READY, onAdd = {}), mid, Box(300f, 700f, 390f, 734f))
        scene("single", EntryPopup(ctx, results.getValue("tasukaru"), AddState.DUPLICATE, onAdd = {}), lineBoxes(60f, 2), Box(400f, 60f, 520f, 104f))
        scene("bottom", EntryPopup(ctx, results.getValue("tagg"), AddState.READY, onAdd = {}), lineBoxes(800f, 2), Box(200f, 856f, 290f, 900f))
        scene("tall", EntryPopup(ctx, results.getValue("long"), AddState.READY, onAdd = {}), lineBoxes(420f, 6), Box(200f, 700f, 290f, 744f))
    }

    @Test fun rendersAllShapes() {
        render("tagg"); render("tasukaru"); render("owaru"); render("long"); render("longword")
        render("owaru", AddState.UNAVAILABLE, "AnkiDroid not installed")
        File(System.getProperty("popup.out") ?: "build/popup", "popup-owaru.png").let { assertTrue(it.length() > 1000) }
    }
}
