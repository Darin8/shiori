package app.shiori.session

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.shiori.MainActivity
import app.shiori.ShioriApp
import app.shiori.anki.AnkiTarget
import app.shiori.anki.CardInput
import app.shiori.dict.LookupResult
import app.shiori.ocr.OcrLine
import app.shiori.overlay.AddState
import app.shiori.overlay.EntryPopup
import app.shiori.overlay.Hit
import app.shiori.overlay.TapLayerOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Real [TapUi]: tap layer over the game, tap to look up, popup, add to Anki. View work runs on the main thread. */
class OverlayTapUi(
    private val context: Context,
    private val app: ShioriApp,
    private val scope: CoroutineScope,
) : TapUi {
    private val main = Handler(Looper.getMainLooper())
    private val overlay = TapLayerOverlay(context)
    private var frame: Bitmap? = null
    private var popup: EntryPopup? = null
    private var tapSeq = 0
    @Volatile private var targetCache: AnkiTarget? = null
    @Volatile private var popupShown = false

    override val popupOpen: Boolean get() = popupShown

    /** Main thread only: a popup is on screen. */
    private fun setPopup(p: EntryPopup?) {
        popup = p
        popupShown = p != null
    }

    fun popupKey(keyCode: Int): Boolean = popup?.onKey(keyCode) ?: false

    /** The newest result that arrived under an open popup, shown once it closes so the layer never keeps older lines. */
    private var deferred: Deferred? = null

    private class Deferred(val frame: Bitmap, val lines: List<OcrLine>, val canShow: () -> Boolean)

    override fun update(frame: Bitmap, lines: List<OcrLine>, canShow: () -> Boolean) {
        main.post {
            if (popup != null) { deferred = Deferred(frame, lines, canShow); return@post }
            // Re-checked here: a Recents transition or Stop handled on main since the background check wins.
            if (!canShow()) return@post
            deferred = null
            apply(frame, lines)
        }
    }

    private fun apply(frame: Bitmap, lines: List<OcrLine>) {
        this.frame = frame
        targetCache = null
        overlay.update(frame.width, frame.height, lines, onOutside = { clearSelection() }) { hit -> onTap(lines, hit) }
    }

    override fun closePopup() {
        main.post { clearSelection() }
    }

    override fun clear() {
        main.post {
            tapSeq++
            targetCache = null
            setPopup(null)
            frame = null
            deferred = null
            overlay.dismiss()
        }
    }

    override fun requestConsent() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_REQUEST_CONSENT, true)
        )
    }

    private fun clearSelection() {
        tapSeq++
        setPopup(null)
        overlay.closePopup()
        deferred?.let { d -> deferred = null; if (d.canShow()) apply(d.frame, d.lines) }
    }

    private fun onTap(lines: List<OcrLine>, hit: Hit) {
        val line = lines.getOrNull(hit.lineIndex) ?: return
        val seq = ++tapSeq
        scope.launch(Dispatchers.Default) {
            val results = try { app.lookup.at(line.text, hit.charIndex) } catch (e: Exception) {
                Log.e(TAG, "lookup failed", e); emptyList()
            }
            main.post {
                if (seq != tapSeq || frame == null) return@post
                if (results.isEmpty()) { clearSelection(); return@post }
                showEntry(line, hit, results)
            }
        }
    }

    /** Shows the popup at once with Add disabled; the AnkiDroid check fills in the state afterwards. */
    private fun showEntry(line: OcrLine, hit: Hit, results: List<LookupResult>) {
        lateinit var p: EntryPopup
        p = EntryPopup(context, results, AddState.CHECKING, onAdd = { r -> add(p, line, hit, r) })
        p.onClose = { clearSelection() }
        p.onIndexChanged = { r ->
            overlay.highlight(hit, r.matched.length)
            p.setAddState(AddState.CHECKING)
            check(p, r)
        }
        setPopup(p)
        overlay.highlight(hit, results[0].matched.length)
        overlay.showPopup(p.view, near(line, hit.charIndex, results[0].matched.length)) { clearSelection() }
        check(p, results[0])
    }

    /** Background AnkiDroid check; ignored if the user moved on (other word, other entry, closed). */
    private fun check(p: EntryPopup, r: LookupResult) {
        val seq = tapSeq
        scope.launch(Dispatchers.Default) {
            val (s, l) = addState(r)
            main.post { if (seq == tapSeq && popup === p && p.current() === r) p.setAddState(s, l) }
        }
    }

    private fun near(line: OcrLine, start: Int, length: Int): RectF {
        val end = (start + length).coerceAtMost(line.chars.size)
        val boxes = line.chars.subList(start.coerceAtMost(end), end).map { it.box }
        if (boxes.isEmpty()) return RectF(line.box.left, line.box.top, line.box.right, line.box.bottom)
        return RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    /** AnkiDroid target, looked up once per snapshot (until an add fails). Blocking; off the main thread. */
    private fun target(): AnkiTarget = targetCache ?: app.exporter.target().also { targetCache = it }

    /** Blocking (AnkiDroid provider); call off the main thread. */
    private fun addState(r: LookupResult): Pair<AddState, String?> =
        when (val t = target()) {
            is AnkiTarget.Unavailable -> AddState.UNAVAILABLE to t.label
            is AnkiTarget.Ok ->
                (if (app.exporter.isDuplicate(t, r.entry.expression)) AddState.DUPLICATE else AddState.READY) to null
        }

    private fun add(p: EntryPopup, line: OcrLine, hit: Hit, r: LookupResult) {
        val shot = frame ?: return
        scope.launch(Dispatchers.Default) {
            val id = when (val t = target()) {
                is AnkiTarget.Unavailable -> null
                is AnkiTarget.Ok -> app.exporter.add(t, CardInput(r, line.text, hit.charIndex, ""), shot)
            }
            if (id == null) targetCache = null
            main.post {
                if (popup !== p || p.current() !== r) return@post
                if (id != null) p.setAddState(AddState.ADDED) else p.setAddState(AddState.UNAVAILABLE, "Add failed")
            }
        }
    }

    private companion object { const val TAG = "Shiori" }
}
