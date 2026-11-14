package app.shiori.hotkey

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import app.shiori.ShioriApp
import app.shiori.capture.CaptureService
import app.shiori.capture.CapturedFrame
import app.shiori.capture.FrameFiles
import app.shiori.ocr.Box
import app.shiori.ocr.OcrChar
import app.shiori.ocr.OcrLine
import app.shiori.session.DeviceProbes
import app.shiori.session.LiveInterval
import app.shiori.session.OcrMode
import app.shiori.session.OcrSession
import app.shiori.session.OverlayTapUi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class HotkeyService : AccessibilityService(), CaptureService.Companion.Listener {
    /** A throw anywhere in a background job is logged, never fatal to the process. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "background job failed", t) },
    )
    private val main = Handler(Looper.getMainLooper())
    private val foregroundFilter by lazy {
        ForegroundFilter(packageName) { pkg, cls ->
            try { packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true } catch (e: Exception) { false }
        }
    }
    private val gate = OverlayGate()
    private val matcher = HotkeyMatcher(emptySet())
    internal val recorder = HotkeyRecorder()
    private lateinit var prefs: SharedPreferences
    private lateinit var ui: OverlayTapUi
    private lateinit var session: OcrSession
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEYS) matcher.keys = loadKeys(prefs)
        val liveSettingChanged = key == LiveInterval.PREF_MODE || key == LiveInterval.PREF_SECONDS
        if (liveSettingChanged && ::session.isInitialized) session.onSettingsChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        matcher.keys = loadKeys(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (::session.isInitialized) session.stop()
        val app = application as ShioriApp
        ui = OverlayTapUi(this, app, scope)
        session = OcrSession(
            capture = { CaptureService.instance?.captureFrame() },
            ocr = app.ocr,
            ui = ui,
            focusedGame = { tracker.latest.focused },
            scope = scope,
            mode = { OcrMode.fromPref(prefs.getString(LiveInterval.PREF_MODE, null)) },
            intervalMs = {
                LiveInterval.fromPref(prefs.getInt(LiveInterval.PREF_SECONDS, LiveInterval.DEFAULT)) * 1000L
            },
            sessionActive = { CaptureService.instance?.hasProjection == true },
            region = { pkg -> pkg?.let { loadRegions(prefs, it) } ?: emptyList() },
            onFrame = { bmp, pkg -> if (prefs.getBoolean(PREF_SAVE_FRAMES, true)) saveFrame(bmp, pkg) },
            onCaptured = { bmp, pkg -> app.latestFrame = CapturedFrame(bmp, pkg) },
            cost = DeviceProbes.costMeter(this),
            onGame = { gate.setGame(it) },
            hidden = { gate.hidden },
        )
        CaptureService.listener = this
        // The capture session may already be running (service reconnected).
        if (CaptureService.instance?.hasProjection == true) {
            refreshGate("connect")
            session.onSessionStarted()
        }
        registerReceiver(
            packageRx,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        instance = this
        registerDebugReceiver()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (recording) {
            val done = recorder.onKey(event.action, event.keyCode)
            if (done != null) {
                prefs.edit().putString(PREF_KEYS, done.joinToString(",")).apply()
                matcher.keys = done
                recording = false
            }
            return false
        }
        return when (matcher.onKey(event.action, event.keyCode, event.repeatCount)) {
            Decision.TRIGGER -> {
                Log.i(TAG, "hotkey trigger")
                // Fresh read on the main thread (even with no capture session, so the consent screen can still open).
                refreshGate("hotkey", force = true)
                session.onHotkey()
                true
            }
            Decision.CONSUME -> true
            Decision.PASS -> {
                // A popup owns the gamepad; with only the tap layer showing, keys pass through to the game.
                if (::ui.isInitialized && ui.popupOpen) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ui.popupKey(event.keyCode)
                    true
                } else false
            }
        }
    }

    private val debuggable get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var debugReceiver: BroadcastReceiver? = null

    /** Window id to package, from STATE_CHANGED events (and filled on first read). Main thread only. */
    private val windowPkgs = HashMap<Int, String>()
    private val homeCache = HomePackageCache({ queryHomePackages() }, { SystemClock.elapsedRealtime() })
    private val tracker by lazy { WindowTracker(packageName) { homeCache.get() } }
    private val packageRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = homeCache.invalidate()
    }

    private fun displayBounds(): Rect =
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds

    private fun sessionRunning() = CaptureService.instance?.hasProjection == true

    /** Reads the accessibility windows. Main thread only: the only place windows, root nodes and [windowPkgs] are touched. */
    private fun snapshot(): List<WinSnap> {
        val display = displayBounds()
        val live = windows
        windowPkgs.keys.retainAll(live.map { it.id }.toSet())
        return live.mapNotNull { w ->
            val kind = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WinKind.APP
                AccessibilityWindowInfo.TYPE_SYSTEM -> WinKind.SYSTEM
                else -> WinKind.OTHER
            }
            val bounds = Rect().also { w.getBoundsInScreen(it) }
            // The event's package first; the root node is an IPC into the app's UI, so it is only read once per window.
            val pkg = windowPkgs[w.id] ?: w.root?.packageName?.toString()?.also { windowPkgs[w.id] = it }
            if (pkg == packageName && kind != WinKind.APP) return@mapNotNull null // our overlay windows
            val covers = bounds.width() >= display.width() * 0.9f && bounds.height() >= display.height() * 0.9f
            WinSnap(kind, pkg, w.isActive, w.isFocused, covers)
        }
    }

    private fun describeWindows(windows: List<WinSnap>) = windows.joinToString(" | ") {
        "${it.kind} ${it.pkg} act=${it.active} foc=${it.focused} cov=${it.covers}"
    }

    /**
     * Main thread. Re-reads the windows, publishes the snapshot for background passes and re-evaluates the gate; on the
     * hiding transition the layer goes away at once. Does nothing while no session runs (unless [force]).
     */
    private fun refreshGate(why: String, force: Boolean = false) {
        if (!force && !sessionRunning()) {
            tracker.clear()
            return
        }
        try {
            val snap = snapshot()
            tracker.update(snap)
            val becameHidden = gate.refresh(snap)
            if (debuggable) Log.i(TAG, "gate $why hidden=${gate.hidden} windows: ${describeWindows(snap)}")
            if (becameHidden && ::session.isInitialized) session.onAway()
        } catch (x: Exception) {
            Log.e(TAG, "window refresh failed", x)
        }
    }

    private fun queryHomePackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val all = packageManager.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }
        val default = packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        return (all + listOfNotNull(default)).toSet()
    }

    /** Debug builds only: `am broadcast -a app.shiori.DEBUG_FAKE_LINES` shows two fake OCR lines for the foreground package. */
    private fun registerDebugReceiver() {
        if (!debuggable) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == DEBUG_STOP) {
                    // Same path as the capture session ending (CaptureService is not exported, so adb cannot send its Stop).
                    Log.i(TAG, "debug stop")
                    onSessionStopped()
                    return
                }
                val b = displayBounds()
                Log.i(TAG, "debug fake pass ${b.width()}x${b.height()}")
                refreshGate("fake", force = true)
                session.debugFakePass {
                    Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888) to fakeLines(b.width(), b.height())
                }
            }
        }
        val filter = IntentFilter("app.shiori.DEBUG_FAKE_LINES").apply { addAction(DEBUG_STOP) }
        // Stays EXPORTED on purpose: broadcasts from `adb shell am broadcast` (uid shell) are dropped by a
        // RECEIVER_NOT_EXPORTED receiver. Only registered when FLAG_DEBUGGABLE (guard above).
        registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        debugReceiver = rx
    }

    private fun fakeLines(w: Int, h: Int): List<OcrLine> = listOf(0.35f, 0.5f).mapIndexed { n, fy ->
        val top = h * fy
        val text = "テスト行${n + 1}です"
        val cw = w * 0.04f
        val bottom = top + h * 0.06f
        val chars = text.mapIndexed { k, ch ->
            OcrChar(ch.toString(), Box(w * 0.2f + k * cw, top, w * 0.2f + (k + 1) * cw, bottom))
        }
        OcrLine(text, Box(chars.first().box.left, top, chars.last().box.right, bottom), chars, false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (debuggable) {
            val type = AccessibilityEvent.eventTypeToString(event.eventType)
            Log.i(TAG, "a11y $type pkg=${event.packageName} cls=${event.className} wid=${event.windowId}")
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString()
                if (pkg != null && event.windowId >= 0) windowPkgs[event.windowId] = pkg
                // Only real app windows count as the game: not our overlay, System UI panels, or dialogs.
                if (foregroundFilter.accept(pkg, cls)) foregroundPackage = pkg
                refreshGate("state")
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshGate("windows")
        }
    }

    private fun saveFrame(bmp: Bitmap, pkg: String?) {
        val dir = File(getExternalFilesDir(null), "frames").apply { mkdirs() }
        val f = File(dir, "frame-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pkg?.let { prefs.edit().putString("last_frame_package", it).apply() }
        FrameFiles.prune(dir)
        Log.i(TAG, "saved ${f.name} ${bmp.width}x${bmp.height}")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        recording = false
        if (::session.isInitialized) session.stop()
        return super.onUnbind(intent)
    }

    override fun onSessionStarted() {
        // Windows are read on the main thread only; the first pass needs a fresh snapshot to know the game in front.
        main.post {
            refreshGate("session")
            if (::session.isInitialized) session.onSessionStarted()
        }
    }

    override fun onSessionStopped() {
        if (::session.isInitialized) session.stop()
        gate.setGame(null)
        tracker.clear()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        debugReceiver = null
        try { unregisterReceiver(packageRx) } catch (_: Exception) {}
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (instance === this) instance = null
        if (CaptureService.listener === this) CaptureService.listener = null
        if (::session.isInitialized) session.stop()
        scope.cancel()
        recording = false
        super.onDestroy()
    }

    companion object {
        const val TAG = "Shiori"
        const val DEBUG_STOP = "app.shiori.DEBUG_STOP"
        /** The app's old name, kept so settings saved under it survive. */
        const val PREFS = "retromine"
        const val PREF_KEYS = "hotkeyKeys"
        const val PREF_SAVE_FRAMES = "saveFrames"

        @Volatile var instance: HotkeyService? = null
        @Volatile var foregroundPackage: String? = null
        @Volatile var recording: Boolean = false
            set(value) {
                if (value) instance?.recorder?.reset()
                field = value
            }

        fun loadKeys(prefs: SharedPreferences): Set<Int> =
            prefs.getString(PREF_KEYS, null)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                ?: emptySet()

        /** Region pref `region:<package>`: boxes "l,t,r,b" joined by ';' in frame pixels (legacy: one box). */
        fun loadRegions(prefs: SharedPreferences, pkg: String): List<Box> =
            app.shiori.overlay.parseRegions(prefs.getString("region:$pkg", null))
    }
}
