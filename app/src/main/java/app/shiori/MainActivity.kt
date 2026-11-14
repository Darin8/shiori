package app.shiori

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shiori.anki.AnkiExporter
import app.shiori.capture.CaptureService
import app.shiori.capture.FrameFiles
import app.shiori.capture.frameAvailable
import app.shiori.hotkey.HotkeyButtonAction
import app.shiori.hotkey.HotkeyService
import app.shiori.hotkey.hotkeyButtonAction
import app.shiori.hotkey.keysLabel
import app.shiori.overlay.parseRegions
import app.shiori.overlay.regionLabel
import app.shiori.session.LiveInterval
import app.shiori.session.OcrMode
import app.shiori.ui.Sumi
import com.ichi2.anki.api.AddContentApi
import java.io.File

/** Setup screen: settings on the left, dictionary manager on the right (one column when narrow). */
class MainActivity : Activity() {
    private val consentRequest = 1001
    private val dictDb get() = (application as ShioriApp).dictionaryDb
    @Volatile private var pendingConsent = false
    @Volatile private var dictTitles: List<String> = emptyList()
    /** Whether a saved frame exists on disk; null until the first background listing. */
    @Volatile private var savedFrames: Boolean? = null
    private lateinit var rows: LinearLayout
    private lateinit var cta: LinearLayout
    private lateinit var panel: DictionaryPanel
    private val prefs by lazy { getSharedPreferences(HotkeyService.PREFS, Context.MODE_PRIVATE) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render(readStatus()) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Sumi.INK))
        val wide = resources.configuration.screenWidthDp >= 600 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        panel = DictionaryPanel(this, { dictDb }, onChanged = { refreshDictionaries() }, scrollable = wide)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isFocusedByDefault = true
            defaultFocusHighlightEnabled = false
            setPadding(dp(16), 0, dp(16), 0)
        }
        val title = TextView(this).apply {
            text = "Shiori"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Sumi.TEXT)
            typeface = Sumi.font(this@MainActivity, 600)
        }
        val root = if (wide) wideLayout(title) else narrowLayout(title)
        val padH = dp(20)
        val padV = dp(if (wide) 16 else 20)
        root.setBackgroundColor(Sumi.INK)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(padH + b.left, padV + b.top, padH + b.right, padV + b.bottom)
            WindowInsets.CONSUMED
        }
        root.setPadding(padH, padV, padH, padV)
        setContentView(root)
        val notificationsGranted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    private fun ctaParams(wide: Boolean) =
        LinearLayout.LayoutParams(-1, dp(if (wide) 52 else 56)).apply { topMargin = dp(12) }

    /** Settings and the call to action on the left, dictionary panel on the right. */
    private fun wideLayout(title: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
            val scroll = ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(rows) }
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(cta, ctaParams(wide = true))
        }
        addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        addView(panel.view, LinearLayout.LayoutParams(0, -1, 1.2f).apply { leftMargin = dp(14) })
    }

    /** One scrolling column with the call to action pinned underneath. */
    private fun narrowLayout(title: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val column = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
            addView(rows)
            addView(panel.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        addView(ScrollView(this@MainActivity).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(cta, ctaParams(wide = false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_CONSENT, false)) pendingConsent = true
    }

    override fun onResume() {
        super.onResume()
        refreshDictionaries()
        refreshSavedFrames()
        panel.reload()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        render(readStatus())
        if (pendingConsent || intent.getBooleanExtra(EXTRA_REQUEST_CONSENT, false)) {
            pendingConsent = false
            intent.removeExtra(EXTRA_REQUEST_CONSENT)
            requestConsent()
        }
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        HotkeyService.recording = false
        super.onPause()
    }

    private fun readStatus(): SetupStatus {
        val keys = prefs.getString("hotkeyKeys", null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        return SetupStatus(
            hotkeyService = accessibilityEnabled(),
            hotkeyKeys = keys,
            dictionaries = dictTitles,
        )
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val ours = "$packageName/.hotkey.HotkeyService"
        val full = "$packageName/${HotkeyService::class.java.name}"
        return enabled.split(':').any { it == ours || it == full }
    }

    private fun refreshSavedFrames() {
        Thread {
            val any = FrameFiles.any(File(getExternalFilesDir(null), "frames"))
            val changed = savedFrames != any
            savedFrames = any
            if (changed) runOnUiThread { if (!isDestroyed) render(readStatus()) }
        }.start()
    }

    private fun refreshDictionaries() {
        Thread {
            dictTitles = dictDb.enabledDictionaries()
            runOnUiThread { if (!isDestroyed) render(readStatus()) }
        }.start()
    }

    private fun render(status: SetupStatus) {
        val focusedTag = currentFocus?.tag
        rows.removeAllViews()
        val items = ArrayList<View>()
        items += settingRow("OCR", modePicker())
        if (ocrMode() == OcrMode.LIVE) items += settingRow("Interval", intervalPicker())
        items += hotkeyRow(status.hotkeyKeys)
        items += regionRow()
        items += permissionRows(status)
        items += settingRow("Save frames", Sumi.Switch(this).apply {
            tag = "saveFrames"
            isOn = prefs.getBoolean(HotkeyService.PREF_SAVE_FRAMES, true)
            onChange = { on -> prefs.edit().putBoolean(HotkeyService.PREF_SAVE_FRAMES, on).apply() }
        })
        items.forEachIndexed { i, v ->
            rows.addView(v)
            if (i < items.size - 1) rows.addView(hairline())
        }
        renderSession()
        if (focusedTag != null) rows.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    private fun hairline() = View(this).apply {
        setBackgroundColor(Sumi.HAIR)
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }

    /** Label on the left, [control] on the right. */
    private fun settingRow(label: String, control: View?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        addView(TextView(this@MainActivity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Sumi.TEXT)
            typeface = Sumi.font(this@MainActivity)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (control != null) addView(control)
    }

    private fun smallText(label: String, color: Int) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color)
        typeface = Sumi.font(this@MainActivity)
    }

    private fun button(
        label: String, tag: String, enabled: Boolean = true, style: Sumi.Style = Sumi.Style.OUTLINED, onClick: (TextView) -> Unit,
    ): TextView {
        lateinit var b: TextView
        b = Sumi.button(this, label, style) { onClick(b) }
        b.tag = tag
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
        return b
    }

    private fun hotkeyRow(keys: Set<Int>): View {
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (keys.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = keysLabel(keys)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Sumi.TEXT)
                typeface = Sumi.font(this@MainActivity)
                background = Sumi.shape(Sumi.CONTROL, dp(6).toFloat())
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        box.addView(button(if (HotkeyService.recording && HotkeyService.instance != null) "…" else "Set", "hotkey") { b ->
            when (hotkeyButtonAction(HotkeyService.recording, HotkeyService.instance != null)) {
                HotkeyButtonAction.OPEN_ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                HotkeyButtonAction.START_RECORDING -> { HotkeyService.recording = true; b.text = "…" }
                HotkeyButtonAction.CANCEL_RECORDING -> { HotkeyService.recording = false; b.text = "Set" }
            }
        })
        return settingRow("Hotkey", box)
    }

    /** Regions set for the game the last frame came from. */
    private fun regionCount(): Int {
        val mem = (application as ShioriApp).latestFrame
        val pkg = mem?.pkg ?: prefs.getString("last_frame_package", null) ?: return 0
        return parseRegions(prefs.getString("region:$pkg", null)).size
    }

    private fun regionRow(): View {
        val mem = (application as ShioriApp).latestFrame
        val pkg = mem?.pkg ?: prefs.getString("last_frame_package", null)
        val hasFrame = pkg != null && frameAvailable(mem != null, savedFrames)
        val count = regionCount()
        return settingRow(
            "OCR region",
            button(
                regionLabel(count), "region", hasFrame,
                if (needsRegionForLive(ocrMode(), count)) Sumi.Style.ACCENT_OUTLINE else Sumi.Style.OUTLINED,
            ) {
                startActivity(Intent(this, RegionActivity::class.java))
            },
        )
    }

    /** Missing permissions each get a row with their action; otherwise one summary row. */
    private fun permissionRows(status: SetupStatus): List<View> {
        val out = ArrayList<View>()
        if (!status.hotkeyService) {
            out += settingRow("Accessibility", button("Allow", "perm-access") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        val exporter = (application as ShioriApp).exporter
        val installed = AddContentApi.getAnkiDroidPackageName(this) != null
        val permitted = checkSelfPermission(AnkiExporter.PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!installed || !permitted) {
            out += settingRow("AnkiDroid", button(if (installed) "Allow" else "Install", "perm-anki") {
                if (!installed) {
                    try { startActivity(exporter.installIntent()) } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.ichi2.anki/")))
                    }
                } else requestPermissions(exporter.requestPermissionIntent(), 1003)
            })
        }
        if (out.isEmpty()) out += settingRow("Permissions", smallText("All granted", Sumi.SECONDARY))
        return out
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render(readStatus())
    }

    /** The main call to action, pinned under the settings: Start session, or Stop session with the running status. */
    private fun renderSession() {
        val active = CaptureService.instance?.hasProjection == true
        val needRegion = !active && needsRegionForLive(ocrMode(), regionCount())
        cta.removeAllViews()
        cta.tag = "session"
        val one = dp(1)
        cta.background = if (active) Sumi.interactive(this, Sumi.ROW, dp(12).toFloat(), Sumi.OUTLINE, one)
        else Sumi.interactive(this, Sumi.ACCENT, dp(12).toFloat())
        cta.alpha = 1f
        if (active) {
            cta.addView(View(this).apply { background = Sumi.shape(Sumi.POSITIVE, dp(5).toFloat()) }, LinearLayout.LayoutParams(dp(10), dp(10)))
        }
        cta.addView(TextView(this).apply {
            text = if (active) "Stop session" else if (needRegion) "Set region" else "Start session"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(if (active) Sumi.TEXT else Sumi.ON_ACCENT)
            typeface = Sumi.font(this@MainActivity, 600)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (active) cta.addView(View(this), LinearLayout.LayoutParams(dp(10), 1)) // balances the dot, keeping the label centred
        cta.setOnClickListener {
            if (active) {
                CaptureService.stop(this)
                cta.postDelayed({ render(readStatus()) }, 300)
            } else if (needRegion) startActivity(Intent(this, RegionActivity::class.java)) // closes itself when there is no frame yet
            else requestConsent()
        }
    }

    private fun ocrMode(): OcrMode = OcrMode.fromPref(prefs.getString(LiveInterval.PREF_MODE, null))

    private fun modePicker(): View =
        Sumi.segmented(this, OcrMode.entries.map { it.label }, OcrMode.entries.indexOf(ocrMode()), tagPrefix = "mode") { i ->
            val m = OcrMode.entries[i]
            if (m != ocrMode()) prefs.edit().putString(LiveInterval.PREF_MODE, m.pref).apply()
        }

    private fun intervalPicker(): View {
        val cur = LiveInterval.fromPref(prefs.getInt(LiveInterval.PREF_SECONDS, LiveInterval.DEFAULT))
        return Sumi.segmented(
            this, LiveInterval.choices.map { "$it s" }, LiveInterval.choices.indexOf(cur),
            outlined = true, tagPrefix = "interval", widthDp = 44,
        ) { i ->
            val v = LiveInterval.choices[i]
            if (v != cur) prefs.edit().putInt(LiveInterval.PREF_SECONDS, v).apply()
        }
    }

    private fun requestConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), consentRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        panel.onActivityResult(requestCode, resultCode, data)
        if (requestCode == consentRequest && resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            cta.postDelayed({ render(readStatus()) }, 500)
        }
    }

    companion object {
        const val EXTRA_REQUEST_CONSENT = "request_consent"
    }
}

/** Live reads only inside regions, so it cannot start without one. */
internal fun needsRegionForLive(mode: OcrMode, regionCount: Int): Boolean = mode == OcrMode.LIVE && regionCount == 0
