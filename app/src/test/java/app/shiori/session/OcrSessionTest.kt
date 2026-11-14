package app.shiori.session

import app.shiori.needsRegionForLive
import android.graphics.Bitmap
import app.shiori.ocr.Box
import app.shiori.ocr.OcrChar
import app.shiori.ocr.OcrEngine
import app.shiori.ocr.OcrLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OcrSessionTest {
    private val log = mutableListOf<String>()
    private val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var mode = OcrMode.HOTKEY
    private var active = true
    private var focused: String? = "com.game"
    private var hidden = false
    private var focusFails = false
    private var intervalMs = 3000L
    private val games = mutableListOf<String?>()

    private fun line(text: String, left: Float): OcrLine {
        val chars = text.mapIndexed { i, c -> OcrChar(c.toString(), Box(left + 10f * i, 0f, left + 10f * (i + 1), 10f)) }
        return OcrLine(text, Box(left, 0f, left + 10f * text.length, 10f), chars, false)
    }

    private val ui = object : TapUi {
        var open = false
        var shown: List<OcrLine>? = null
        var shownFrame: Bitmap? = null
        var canShow: (() -> Boolean)? = null
        override val popupOpen: Boolean get() = open
        override fun update(frame: Bitmap, lines: List<OcrLine>, canShow: () -> Boolean) {
            log += "update"; shown = lines; shownFrame = frame; this.canShow = canShow
        }
        override fun closePopup() { log += "closePopup"; open = false }
        override fun clear() { log += "clear"; open = false }
        override fun requestConsent() { log += "consent" }
    }

    private fun TestScope.session(
        capture: suspend () -> Bitmap? = { log += "capture"; frame },
        ocr: OcrEngine = OcrEngine { log += "ocr"; listOf(line("はい", 0f)) },
        region: (String?) -> List<Box> = { if (mode == OcrMode.LIVE) screenRegion else emptyList() },
        onFrame: (Bitmap, String?) -> Unit = { _, _ -> log += "save" },
        onCaptured: (Bitmap, String?) -> Unit = { _, _ -> },
        cost: CostMeter? = null,
    ) = OcrSession(
        capture, ocr, ui, { if (focusFails) error("windows gone") else focused }, this, { mode }, { intervalMs }, { active },
        io = StandardTestDispatcher(testScheduler), region = region, onFrame = onFrame, onCaptured = onCaptured,
        onGame = { games += it; hidden = false }, hidden = { hidden },
        now = { currentTime }, log = { log += it }, cost = cost,
    )

    private class CountingEngine : OcrEngine {
        var reads = 0
        var resets = 0
        var lastLive: Boolean? = null
        var lastRegions: List<Box>? = null
        override suspend fun read(frame: Bitmap) = emptyList<OcrLine>()
        override suspend fun read(frame: Bitmap, ctx: app.shiori.ocr.ReadContext): List<OcrLine> {
            reads++; lastLive = ctx.live; lastRegions = ctx.regions
            return listOf(OcrLine("はい", Box(0f, 0f, 20f, 10f), emptyList(), false))
        }
        override fun reset() { resets++ }
    }

    private val screenRegion = listOf(Box(0f, 0f, 12f, 8f))

    private fun passes() = log.count { it == "update" }
    private fun ocrs() = log.count { it == "ocr" }

    // ---- hotkey mode

    @Test
    fun `hotkey mode press runs one capture and OCR and refreshes the layer`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        assertEquals(listOf("capture", "ocr", "update", "save"), log)
        assertSame(frame, ui.shownFrame)
        assertEquals(listOf("はい"), ui.shown!!.map { it.text })
    }

    @Test
    fun `hotkey mode keeps the layer until the next press and each press refreshes it`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        advanceTimeBy(60_000)
        assertEquals(1, passes())
        assertFalse(log.contains("clear"))
        s.onHotkey(); advanceUntilIdle()
        assertEquals(2, passes())
    }

    @Test
    fun `hotkey while a popup is open only closes the popup`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle(); log.clear()
        ui.open = true
        s.onHotkey(); advanceUntilIdle()
        assertEquals(listOf("closePopup"), log)
    }

    @Test
    fun `a press while a pass is running is ignored`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        var captures = 0
        val s = session(capture = { captures++; gate.await() })
        s.onHotkey(); advanceUntilIdle()
        s.onHotkey(); s.onHotkey(); advanceUntilIdle()
        assertEquals(1, captures)
        gate.complete(frame); advanceUntilIdle()
        assertEquals(1, passes())
    }

    @Test
    fun `no capture session asks for consent`() = runTest {
        active = false
        val s = session(capture = { null })
        s.onHotkey(); advanceUntilIdle()
        assertEquals(listOf("consent"), log)
    }

    @Test
    fun `a capture timeout during a running session does not open the consent screen`() = runTest {
        val s = session(capture = { null })
        s.onHotkey(); advanceUntilIdle()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `OCR failure still refreshes with no lines`() = runTest {
        val s = session(ocr = OcrEngine { throw IllegalStateException("boom") })
        s.onHotkey(); advanceUntilIdle()
        assertEquals(emptyList<OcrLine>(), ui.shown)
    }

    @Test
    fun `region filters lines`() = runTest {
        val s = session(
            ocr = OcrEngine { listOf(line("はい", 0f), line("いいえ", 500f)) },
            region = { pkg -> if (pkg == "com.game") listOf(Box(400f, 0f, 700f, 20f)) else emptyList() },
        )
        s.onHotkey(); advanceUntilIdle()
        assertEquals(listOf("いいえ"), ui.shown!!.map { it.text })
    }

    @Test
    fun `a result that arrives under a popup is dropped`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val s = session(capture = { gate.await() })
        s.onHotkey(); advanceUntilIdle()
        ui.open = true
        gate.complete(frame); advanceUntilIdle()
        assertFalse(log.contains("update"))
    }

    // ---- stops and races

    @Test
    fun `stop while a pass is in flight drops its result`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val s = session(capture = { gate.await() })
        s.onHotkey(); advanceUntilIdle()
        s.stop()
        gate.complete(frame); advanceUntilIdle()
        assertFalse(log.contains("update"))
    }

    // ---- races between the background check and the posted update

    @Test
    fun `hiding between the check and the posted update shows nothing`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        val canShow = ui.canShow!!
        assertTrue(canShow())
        hidden = true // Recents transition handled on main
        assertFalse(canShow())
    }

    @Test
    fun `stop between the check and the posted update shows nothing`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        val canShow = ui.canShow!!
        assertTrue(canShow())
        s.stop()
        assertFalse(canShow())
    }

    @Test
    fun `a popup opening or the capture session ending before the posted update shows nothing`() = runTest {
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        val canShow = ui.canShow!!
        ui.open = true
        assertFalse(canShow())
        ui.open = false
        assertTrue(canShow())
        active = false
        assertFalse(canShow())
    }


    @Test
    fun `stop racing a hotkey capture does not open the consent screen`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val s = session(capture = { gate.await() })
        s.onHotkey(); advanceUntilIdle()
        active = false
        s.stop()
        gate.complete(null); advanceUntilIdle()
        assertFalse(log.contains("consent"))
    }

    @Test
    fun `a throwing focus read on a hotkey press is swallowed`() = runTest {
        focusFails = true
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        assertFalse(log.contains("capture"))
    }

    // ---- focus changes

    @Test
    fun `after being away a pass runs and rebinds to the newly focused game`() = runTest {
        val s = session()
        focused = "com.a"
        s.onHotkey(); advanceUntilIdle()
        s.onAway()
        focused = "com.b"
        log.clear()
        s.onHotkey(); advanceUntilIdle()
        assertEquals(listOf("com.a", "com.b"), games)
        assertEquals(1, passes())
    }


    @Test
    fun `hotkey after returning to a game shows the layer`() = runTest {
        val s = session()
        focused = null
        s.onHotkey(); advanceUntilIdle()
        assertEquals(0, passes())
        s.onAway()
        hidden = true
        focused = "com.game"
        s.onHotkey(); advanceUntilIdle()
        assertEquals(1, passes())
        assertTrue(ui.canShow!!())
    }


    @Test
    fun `hotkey passes always read fresh, without settling`() = runTest {
        val eng = CountingEngine()
        val s = session(ocr = eng, region = { screenRegion })
        s.onHotkey(); advanceUntilIdle()
        assertEquals(false, eng.lastLive)
        s.onHotkey(); advanceUntilIdle()
        assertEquals(2, eng.reads)
    }

    @Test
    fun `the engine cache resets when the game changes and on stop`() = runTest {
        val eng = CountingEngine()
        val s = session(ocr = eng)
        s.onHotkey(); advanceUntilIdle()
        val first = eng.resets
        s.onHotkey(); advanceUntilIdle()
        assertEquals(first, eng.resets)
        focused = "com.other"
        s.onHotkey(); advanceUntilIdle()
        assertEquals(first + 1, eng.resets)
        s.stop()
        assertEquals(first + 2, eng.resets)
    }


    // ---- live mode (interval)

    @Test
    fun `live reads once at start and then every interval, even when nothing changed`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        assertEquals(1, ocrs())
        advanceTimeBy(2999); runCurrent()
        assertEquals(1, ocrs())
        advanceTimeBy(1); runCurrent()
        assertEquals(2, ocrs())
        advanceTimeBy(9000); runCurrent()
        assertEquals(5, ocrs())
        s.stop()
    }

    @Test
    fun `the interval follows the setting`() = runTest {
        mode = OcrMode.LIVE
        intervalMs = 1000
        val s = session()
        s.onSessionStarted(); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(6, ocrs())
        s.stop()
    }

    @Test
    fun `live passes read the regions without settling and hold nothing back`() = runTest {
        mode = OcrMode.LIVE
        val eng = CountingEngine()
        val s = session(ocr = eng)
        s.onSessionStarted(); runCurrent()
        assertEquals(false, eng.lastLive)
        assertEquals(screenRegion, eng.lastRegions)
        s.stop()
    }

    @Test
    fun `no second pass starts while one is in flight`() = runTest {
        mode = OcrMode.LIVE
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val eng = object : OcrEngine {
            override suspend fun read(frame: Bitmap) = emptyList<OcrLine>()
            override suspend fun read(frame: Bitmap, ctx: app.shiori.ocr.ReadContext): List<OcrLine> {
                reads++; gate.await(); return emptyList()
            }
        }
        val s = session(ocr = eng)
        s.onSessionStarted(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, reads)
        gate.complete(Unit); runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, reads)
        s.stop()
    }

    @Test
    fun `stop ends the interval`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        s.stop(); s.stop()
        assertFalse(s.liveOn)
        assertEquals(2, log.count { it == "clear" })
        log.clear()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `live with no regions never reads or updates on its own and starts once a region appears`() = runTest {
        mode = OcrMode.LIVE
        var regions = emptyList<Box>()
        val s = session(region = { regions })
        s.onSessionStarted(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, ocrs())
        assertEquals(0, passes())
        regions = screenRegion
        advanceTimeBy(3000); runCurrent()
        assertEquals(1, ocrs())
        s.stop()
    }

    @Test
    fun `live needs a region to start from the setup screen`() {
        assertTrue(needsRegionForLive(OcrMode.LIVE, 0))
        assertFalse(needsRegionForLive(OcrMode.LIVE, 1))
        assertFalse(needsRegionForLive(OcrMode.HOTKEY, 0))
    }

    @Test
    fun `live frames are not saved`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); advanceTimeBy(9000); runCurrent()
        assertFalse(log.contains("save"))
        s.stop()
    }

    @Test
    fun `live does not read while a popup is open and reads again once it closes`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        ui.open = true
        log.clear()
        advanceTimeBy(30_000); runCurrent()
        assertFalse(log.contains("capture"))
        assertFalse(log.contains("ocr"))
        ui.open = false
        advanceTimeBy(3000); runCurrent()
        assertTrue(log.contains("ocr"))
        s.stop()
    }

    @Test
    fun `identical live passes cause one ui update`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); advanceTimeBy(12_000); runCurrent()
        assertEquals(5, ocrs())
        assertEquals(1, passes())
        s.stop()
    }

    @Test
    fun `a live pass that finds different text updates the layer`() = runTest {
        mode = OcrMode.LIVE
        var text = "はい"
        val s = session(ocr = OcrEngine { log += "ocr"; listOf(line(text, 0f)) }, region = { listOf(Box(0f, 0f, 100f, 20f)) })
        s.onSessionStarted(); runCurrent()
        text = "いいえ"
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, passes())
        assertEquals(listOf("いいえ"), ui.shown!!.map { it.text })
        s.stop()
    }

    @Test
    fun `a refused posted update makes the next identical live pass push its lines again`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        assertEquals(1, passes())
        ui.open = true
        assertFalse(ui.canShow!!()) // main thread refused it: the layer does not hold these lines
        ui.open = false
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, passes())
        s.stop()
    }

    @Test
    fun `live without a capture session asks for consent and does not run`() = runTest {
        mode = OcrMode.LIVE
        active = false
        val s = session(capture = { null })
        s.onSessionStarted(); runCurrent()
        assertTrue(log.contains("consent"))
        assertFalse(s.liveOn)
        s.stop()
    }

    @Test
    fun `switching the mode to hotkey stops live and switching back restarts it`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        assertTrue(s.liveOn)
        mode = OcrMode.HOTKEY
        s.onSettingsChanged()
        assertFalse(s.liveOn)
        mode = OcrMode.LIVE
        s.onSettingsChanged(); runCurrent()
        assertTrue(s.liveOn)
        s.stop()
    }

    @Test
    fun `hotkey mode never starts live on session start`() = runTest {
        val s = session()
        s.onSessionStarted(); advanceTimeBy(30_000); runCurrent()
        assertFalse(s.liveOn)
        assertEquals(0, ocrs())
    }

    // ---- hotkey in live mode

    @Test
    fun `the hotkey in live mode reads the whole screen once, ignoring regions, and keeps live running`() = runTest {
        mode = OcrMode.LIVE
        val eng = CountingEngine()
        val s = session(ocr = eng)
        s.onSessionStarted(); runCurrent()
        assertEquals(screenRegion, eng.lastRegions)
        s.onHotkey(); runCurrent()
        assertEquals(emptyList<Box>(), eng.lastRegions)
        assertEquals(2, eng.reads)
        assertTrue(s.liveOn)
        advanceTimeBy(3000); runCurrent()
        assertEquals(screenRegion, eng.lastRegions)
        s.stop()
    }

    @Test
    fun `the hotkey in live mode works with no regions`() = runTest {
        mode = OcrMode.LIVE
        val s = session(region = { emptyList() })
        s.onSessionStarted(); runCurrent()
        s.onHotkey(); runCurrent()
        assertEquals(1, passes())
        s.stop()
    }

    @Test
    fun `in live mode the hotkey with a popup open only closes the popup`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        log.clear()
        ui.open = true
        s.onHotkey(); runCurrent()
        assertEquals(listOf("closePopup"), log)
        assertTrue(s.liveOn)
        s.stop()
    }

    // ---- focus and the gate in live mode

    @Test
    fun `a throwing focus read never ends live`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        focusFails = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, ocrs())
        assertTrue(s.liveOn)
        focusFails = false
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, ocrs())
        s.stop()
    }

    @Test
    fun `a pass skips while the launcher, recents or our app is focused`() = runTest {
        focused = null
        val s = session()
        s.onHotkey(); advanceUntilIdle()
        assertEquals(emptyList<String>(), log)
        assertEquals(emptyList<String?>(), games)
        mode = OcrMode.LIVE
        s.onSessionStarted(); advanceTimeBy(30_000); runCurrent()
        assertFalse(log.contains("capture"))
        s.stop()
    }

    @Test
    fun `live reads again when the game comes back in front`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        assertEquals(1, ocrs())
        focused = null
        s.onAway()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, ocrs())
        focused = "com.game"
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, ocrs())
        assertEquals(2, passes()) // the layer was cleared by onAway, so the same text is pushed again
        s.stop()
    }

    @Test
    fun `live started in Shiori then switching to another game rebinds and reads on the next tick`() = runTest {
        mode = OcrMode.LIVE
        focused = null // Shiori in front
        val s = session()
        s.onSessionStarted(); advanceTimeBy(9000); runCurrent()
        assertEquals(0, ocrs())
        // The gate is still bound to the game read earlier: it stays hidden until something rebinds it.
        games += "com.old"
        hidden = true
        focused = "com.new"
        advanceTimeBy(3000); runCurrent()
        assertEquals(1, ocrs())
        assertEquals("com.new", games.last())
        s.stop()
    }

    @Test
    fun `live reads again after the gate hides and shows the same game`() = runTest {
        mode = OcrMode.LIVE
        val s = session()
        s.onSessionStarted(); runCurrent()
        assertEquals(1, ocrs())
        hidden = true; focused = null; s.onAway()
        advanceTimeBy(9000); runCurrent()
        assertEquals(1, ocrs())
        hidden = false; focused = "com.game"
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, ocrs())
        s.stop()
    }

    @Test
    fun `every real pass reports the captured frame and package, fake ones do not`() = runTest {
        val seen = mutableListOf<Pair<Bitmap, String?>>()
        val s = session(onCaptured = { b, p -> seen += b to p })
        s.onHotkey(); advanceUntilIdle()
        mode = OcrMode.LIVE
        s.onSessionStarted(); runCurrent()
        assertEquals(2, seen.size)
        assertSame(frame, seen[0].first)
        assertEquals("com.game", seen[0].second)
        s.debugFakePass { frame to emptyList() }; runCurrent()
        assertEquals(2, seen.size)
        s.stop()
    }

    @Test
    fun `live toggled off while a pass is in flight drops its result`() = runTest {
        mode = OcrMode.LIVE
        val gate = CompletableDeferred<Bitmap?>()
        val s = session(capture = { gate.await() })
        s.onSessionStarted(); runCurrent()
        mode = OcrMode.HOTKEY
        s.onSettingsChanged()
        gate.complete(frame); runCurrent()
        assertFalse(log.contains("update"))
    }

    @Test
    fun `live cost log has pass lines and summaries`() = runTest {
        mode = OcrMode.LIVE
        val out = mutableListOf<String>()
        val meter = CostMeter({ out += it }, { 0L }, { currentTime }, { "b" })
        val s = session(cost = meter)
        s.onSessionStarted(); advanceTimeBy(40_000); runCurrent()
        assertTrue(out.toString(), out.any { it.startsWith("cost pass ") })
        assertTrue(out.toString(), out.any { it.startsWith("cost summary ") })
        s.stop()
    }
}
