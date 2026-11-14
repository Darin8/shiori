package app.shiori.session

/** How OCR is triggered: one pass per hotkey press, or a pass every few seconds while the session runs. */
enum class OcrMode(val pref: String, val label: String) {
    HOTKEY("hotkey", "Hotkey"),
    LIVE("live", "Live");

    companion object {
        fun fromPref(v: String?): OcrMode = entries.firstOrNull { it.pref == v } ?: HOTKEY
    }
}

object LiveInterval {
    val choices = listOf(1, 2, 3, 5)
    const val DEFAULT = 3
    const val PREF_MODE = "ocrMode"
    const val PREF_SECONDS = "liveIntervalSec"

    fun fromPref(v: Int): Int = if (v in choices) v else DEFAULT
}
