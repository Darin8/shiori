package app.shiori

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.shiori.dict.DictInfo
import app.shiori.dict.DictionaryDb
import app.shiori.dict.importYomitanZip
import app.shiori.ui.Sumi

/**
 * Dictionary manager as a panel: enable, order, delete and Import. Embedded in the setup screen; the host forwards
 * [onActivityResult] for [IMPORT_REQUEST]. Database work runs off the UI thread, then the list is redrawn.
 */
class DictionaryPanel(
    private val activity: Activity,
    private val db: () -> DictionaryDb,
    private val onChanged: () -> Unit = {},
    private val scrollable: Boolean = true,
) {
    private val ctx get() = activity
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val progress = ProgressBar(activity).apply { isIndeterminate = true; visibility = View.GONE }
    private val importButton = Sumi.button(activity, "Import", Sumi.Style.FILLED, padDp = 16) { pickFile() }
    @Volatile private var busy = false
    private var confirming: String? = null
    private var shown: List<DictInfo> = emptyList()

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        background = Sumi.shape(Sumi.PANEL, dp(14).toFloat())
        val head = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Dictionaries"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Sumi.TEXT)
                typeface = Sumi.font(activity, 600)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(importButton)
        }
        addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        addView(progress, LinearLayout.LayoutParams(-1, -2))
        if (scrollable) addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else addView(list, LinearLayout.LayoutParams(-1, -2))
    }

    /** Reloads the list from the database. */
    fun reload() = background { }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        activity.startActivityForResult(intent, IMPORT_REQUEST)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == IMPORT_REQUEST && resultCode == Activity.RESULT_OK) data?.data?.let { importDictionary(it) }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        progress.visibility = if (on) View.VISIBLE else View.GONE
        importButton.isEnabled = !on
        importButton.alpha = if (on) 0.5f else 1f
    }

    /** Runs [work] off the UI thread, then shows the refreshed list. */
    private fun <T> background(work: () -> T) {
        Thread {
            try { work() } catch (e: Exception) { Log.e("Shiori", "dictionary action failed", e) }
            val infos = db().dictionaryInfos()
            activity.runOnUiThread { if (!activity.isDestroyed) { show(infos); onChanged() } }
        }.start()
    }

    /** Draws [infos] now (UI thread); also used directly by tests. */
    fun show(infos: List<DictInfo>) {
        shown = infos
        list.removeAllViews()
        infos.forEachIndexed { i, d ->
            list.addView(row(i, d, infos.size), LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) 0 else dp(6) })
        }
    }

    private fun iconButton(label: String, desc: String, enabled: Boolean, sp: Float, color: Int, onClick: () -> Unit) =
        Sumi.button(activity, label, Sumi.Style.GHOST, heightDp = 40, sp = sp, radiusDp = 8f, padDp = 0, visibleDp = 32f) { onClick() }.apply {
            contentDescription = desc
            isEnabled = enabled
            setTextColor(color)
            alpha = if (enabled) 1f else 0.3f
        }

    private fun row(i: Int, d: DictInfo, count: Int): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(6), dp(2), dp(4), dp(2))
            background = Sumi.shape(Sumi.ROW, dp(12).toFloat())
        }
        row.addView(Sumi.Switch(activity).apply {
            isOn = d.enabled
            onChange = { on -> background { db().setEnabled(d.title, on) } }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, 0, 0) }
        names.addView(TextView(activity).apply {
            text = d.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (d.enabled) Sumi.TEXT else Sumi.SECONDARY)
            typeface = Sumi.font(activity)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        names.addView(TextView(activity).apply {
            text = dictMeta(d.revision)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(if (d.enabled) Sumi.META else DISABLED_META)
            typeface = Sumi.font(activity)
            isSingleLine = true
            setAutoSizeTextTypeUniformWithConfiguration(9, 10, 1, TypedValue.COMPLEX_UNIT_SP) // shrinks to fit, never cut mid-word
        })
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val bw = dp(40)
        row.addView(iconButton("▲", "Move up", i > 0 && !busy, 11f, Sumi.FAINT) { background { db().move(d.title, -1) } }, LinearLayout.LayoutParams(bw, dp(40)))
        row.addView(iconButton("▼", "Move down", i < count - 1 && !busy, 11f, Sumi.FAINT) { background { db().move(d.title, 1) } }, LinearLayout.LayoutParams(bw, dp(40)))
        val armed = confirming == d.title
        val del: View = if (armed) {
            Sumi.button(activity, "Delete?", Sumi.Style.ACCENT_OUTLINE, heightDp = 40, sp = 13f, radiusDp = 8f, padDp = 4, visibleDp = 32f) { confirmDelete(d.title) }
                .apply { tag = "delete-confirm" }
        } else {
            iconButton("✕", "Delete", !busy, 13f, Sumi.SECONDARY) { armDelete(d.title) }.apply { tag = "delete" }
        }
        row.addView(del, LinearLayout.LayoutParams(if (armed) -2 else bw, dp(40)).apply { leftMargin = dp(2) })
        return row
    }

    /** First tap on a row's delete: it becomes "Delete?" for 3 s; a second tap in that time deletes. */
    internal fun armDelete(title: String) {
        confirming = title
        show(shown)
        list.postDelayed({
            if (confirming == title) { confirming = null; show(shown) }
        }, 3000)
    }

    private fun confirmDelete(title: String) {
        confirming = null
        setBusy(true)
        Thread {
            try { db().delete(title) } catch (e: Exception) { Log.e("Shiori", "delete failed", e) }
            val infos = db().dictionaryInfos()
            activity.runOnUiThread { if (!activity.isDestroyed) { setBusy(false); show(infos); onChanged() } }
        }.start()
    }

    private fun importDictionary(uri: Uri) {
        setBusy(true)
        Thread {
            try {
                activity.contentResolver.openInputStream(uri)?.let { importYomitanZip(it, db()) }
            } catch (e: Exception) {
                Log.e("Shiori", "dictionary import failed", e)
            }
            val infos = db().dictionaryInfos()
            activity.runOnUiThread { if (!activity.isDestroyed) { setBusy(false); show(infos); onChanged() } }
        }.start()
    }

    companion object {
        const val IMPORT_REQUEST = 1002
        private val DISABLED_META = Color.parseColor("#7A8292")
    }
}

/** Short second line for a dictionary row: "Terms, 2025.02.11.0" becomes "Terms 2025-02-11"; never cut mid-word. */
internal fun dictMeta(revision: String?): String {
    if (revision.isNullOrBlank()) return ""
    var s = revision.trim().replace(",", "")
    s = Regex("""(\d{4})[.-](\d{2})[.-](\d{2})(?:\.\d+)*""").replace(s) { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
    while (s.length > 24 && s.contains(' ')) s = s.substringBeforeLast(' ')
    return s
}
