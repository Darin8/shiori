package app.shiori.session

import android.graphics.Bitmap
import android.util.Log
import app.shiori.ocr.Box
import app.shiori.ocr.OcrEngine
import app.shiori.ocr.OcrLine
import app.shiori.ocr.PassStats
import app.shiori.ocr.ReadContext
import app.shiori.overlay.linesInRegions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The invisible tap layer over the running game, plus the entry popup. */
interface TapUi {
    /** True while an entry popup is on screen. Safe to read from any thread. */
    val popupOpen: Boolean

    /**
     * Replaces the tap boxes (empty removes the layer). [frame] is kept for the card picture. The caller already
     * checked [canShow] off-thread; call it again on the window thread right before showing, and show nothing if false.
     */
    fun update(frame: Bitmap, lines: List<OcrLine>, canShow: () -> Boolean)

    /** Closes the popup and its highlight, keeps the tap boxes. */
    fun closePopup()

    /** Removes the layer and the popup. */
    fun clear()

    fun requestConsent()
}

/**
 * Capture + OCR passes that refresh the tap layer; the game is never paused or covered.
 * - [OcrMode.HOTKEY]: each hotkey press is one pass; the layer stays until the next press.
 * - [OcrMode.LIVE]: every [intervalMs] a pass reads the user's OCR regions (none set: no reads). The hotkey reads
 *   the whole screen.
 * A pass is skipped (never queued) while another runs or a popup is open, and a result arriving under a popup is
 * dropped. [sessionActive] tells "no capture session" (ask for consent) from a capture that just timed out.
 */
class OcrSession(
    private val capture: suspend () -> Bitmap?,
    private val ocr: OcrEngine,
    private val ui: TapUi,
    /** The normal app in front right now (null: launcher, recents, our app, System UI). Gates passes and binds them. */
    private val focusedGame: () -> String?,
    private val scope: CoroutineScope,
    private val mode: () -> OcrMode,
    private val intervalMs: () -> Long,
    private val sessionActive: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val region: (String?) -> List<Box> = { emptyList() },
    /** Hotkey passes only: the captured frame and game package, for frame collection. */
    private val onFrame: (Bitmap, String?) -> Unit = { _, _ -> },
    /** Every real pass: the latest captured frame and its package, kept in memory only. */
    private val onCaptured: (Bitmap, String?) -> Unit = { _, _ -> },
    private val cost: CostMeter? = null,
    /** The package a pass ran on, so the caller can hide the layer once it leaves the foreground. */
    private val onGame: (String?) -> Unit = {},
    /** True while the game is not in front: no layer, no redraw. */
    private val hidden: () -> Boolean = { false },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = { Log.i("Shiori", it) },
) {
    private enum class Result { Done, Busy, NoFrame, Dropped }

    private val busy = AtomicBoolean(false)
    @Volatile private var epoch = 0
    @Volatile private var liveJob: Job? = null
    @Volatile private var lastPkg: String? = null
    /** Lines the last real pass showed, so a live pass that finds the same again leaves the overlay alone. */
    @Volatile private var shownLines: List<OcrLine> = emptyList()

    val liveOn: Boolean get() = liveJob?.isActive == true

    fun onHotkey() {
        if (ui.popupOpen) {
            ui.closePopup()
            return
        }
        // Hotkey mode honours the regions (none: whole screen); Live mode always reads the whole screen.
        val whole = mode() == OcrMode.LIVE
        val startEpoch = epoch
        scope.launch {
            // A Stop that raced this capture must not pop the consent screen afterwards.
            val noFrame = pass(manual = true, whole = whole) == Result.NoFrame
            if (noFrame && startEpoch == epoch && !sessionActive()) ui.requestConsent()
        }
    }

    /** The screen-capture session just became ready. */
    fun onSessionStarted() {
        log("cost mark session start mode=${mode().pref}")
        if (mode() == OcrMode.LIVE) startLive()
    }

    /** Settings changed (mode): start or stop live to match. */
    fun onSettingsChanged() {
        when {
            mode() != OcrMode.LIVE && liveOn -> stopLive(clear = true)
            mode() == OcrMode.LIVE && !liveOn && sessionActive() -> startLive()
        }
    }

    /** The game left the foreground: drop the layer and popup now. Live resumes by itself once the game is back. */
    fun onAway() {
        shownLines = emptyList()
        ui.clear()
    }

    /** Session ended or service stopping: no more passes, no layer, no popup. Idempotent. */
    fun stop() {
        log("cost mark session stop")
        epoch++
        stopLive(clear = false)
        resetChange()
        ui.clear()
    }

    private fun startLive() {
        if (liveOn) return
        log("cost mark live start interval=${intervalMs()}ms")
        cost?.reset()
        val startEpoch = epoch
        liveJob = scope.launch {
            var first = true
            while (isActive) {
                if (!first) delay(intervalMs())
                first = false
                // One failed tick (windows, PackageManager, UI) never ends the loop.
                try {
                    val pkg = focused()
                    // The gate stays hidden while bound to another package and only a pass rebinds it: a game focused
                    // after a different one must rebind here, or the loop sees "hidden" forever and never reads it.
                    if (pkg != null && hidden() && !ui.popupOpen) onGame(pkg)
                    when {
                        pkg == null || hidden() -> skipTick("away")
                        ui.popupOpen -> skipTick("popup")
                        region(pkg).isEmpty() -> skipTick("noregion")
                        busy.get() -> skipTick("busy")
                        else -> launch {
                            val noFrame = pass(manual = false) == Result.NoFrame
                            if (noFrame && startEpoch == epoch && !sessionActive()) {
                                stopLive(clear = true)
                                ui.requestConsent()
                            }
                        }
                    }
                } catch (x: CancellationException) {
                    throw x
                } catch (x: Throwable) {
                    Log.e("Shiori", "live tick failed", x)
                }
            }
        }
    }

    private fun skipTick(kind: String) {
        log("cost skip $kind")
        cost?.onSkip(kind)
    }

    private fun stopLive(clear: Boolean) {
        if (liveJob != null) log("cost mark live stop")
        epoch++ // a pass still in flight must not show
        liveJob?.cancel()
        liveJob = null
        if (clear) {
            resetChange()
            ui.clear()
        }
    }

    /** Forget cached lines (Stop, live off, game change). */
    private fun resetChange() {
        lastPkg = null
        shownLines = emptyList()
        ocr.reset()
    }

    /** Debug builds: the normal pass (focus check, gate binding, drop rules) with fake results instead of capture and OCR. */
    fun debugFakePass(make: () -> Pair<Bitmap, List<OcrLine>>) {
        scope.launch { pass(manual = false, fake = make) }
    }

    private suspend fun pass(
        manual: Boolean,
        whole: Boolean = false,
        fake: (() -> Pair<Bitmap, List<OcrLine>>)? = null,
    ): Result {
        if (!busy.compareAndSet(false, true)) return Result.Busy
        val startEpoch = epoch
        try {
            val pkg = focused() ?: return Result.Dropped
            if (pkg != lastPkg) {
                resetChange()
                lastPkg = pkg
            }
            onGame(pkg)
            val t0 = now()
            val fakeOut = fake?.invoke()
            val frame = fakeOut?.first ?: capture() ?: return Result.NoFrame
            val t1 = now()
            if (startEpoch != epoch) return Result.Dropped
            if (fake == null) onCaptured(frame, pkg)
            if (manual && fake == null) scope.launch(io) {
                try { onFrame(frame, pkg) } catch (x: Exception) { Log.e("Shiori", "frame save failed", x) }
            }
            val regions = if (fake == null && !whole) region(pkg) else emptyList()
            val soft = if (fake == null) software(frame) else frame
            var stats: PassStats? = null
            val lines = if (fakeOut != null) fakeOut.second else try {
                val r = linesInRegions(ocr.read(soft, ReadContext(live = false, regions = regions)), regions)
                stats = ocr.lastStats
                r
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                Log.e("Shiori", "OCR failed", x)
                emptyList()
            }
            val t2 = now()
            if (!mayShow(startEpoch, fake != null)) return Result.Dropped
            // A live pass that found the same text (boxes within a few px) leaves the overlay untouched: no window churn.
            val same = fake == null && !manual && sameContent(shownLines, lines)
            if (!same) ui.update(frame, lines) {
                // A refused update means the layer does not hold these lines, so the next pass must push its own
                // even when they look the same.
                mayShow(startEpoch, fake != null).also { if (!it) shownLines = emptyList() }
            }
            if (fake == null) shownLines = lines
            cost?.onPass(
                t1 - t0, stats?.detectMs ?: 0, stats?.recogMs ?: (t2 - t1),
                stats?.reused ?: 0, stats?.recognized ?: lines.size, t2 - t0,
            )
            return Result.Done
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            Log.e("Shiori", "pass failed", x)
            return Result.Dropped
        } finally {
            busy.set(false)
        }
    }

    /** The layer may be shown for a pass from epoch [passEpoch]: not stopped since, no popup, game in front, session alive. */
    private fun mayShow(passEpoch: Int, fake: Boolean = false): Boolean =
        passEpoch == epoch && (fake || sessionActive()) && !hidden() && !ui.popupOpen

    /** [focusedGame] that never throws: a failed window read counts as "no game in front". */
    private fun focused(): String? = try {
        focusedGame()
    } catch (x: CancellationException) {
        throw x
    } catch (x: Throwable) {
        Log.e("Shiori", "focus read failed", x)
        null
    }

    /** ML Kit rejects HARDWARE bitmaps; anything not plain ARGB_8888 is copied. */
    private fun software(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
}

/** True when both passes found the same texts at boxes that differ by at most [tol] px per edge (detector jitter). */
internal fun sameContent(a: List<OcrLine>, b: List<OcrLine>, tol: Float = 4f): Boolean =
    a.size == b.size && a.indices.all { i ->
        val boxA = a[i].box
        val boxB = b[i].box
        a[i].text == b[i].text && abs(boxA.left - boxB.left) <= tol && abs(boxA.top - boxB.top) <= tol &&
            abs(boxA.right - boxB.right) <= tol && abs(boxA.bottom - boxB.bottom) <= tol
    }
