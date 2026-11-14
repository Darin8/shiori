package app.shiori.hotkey

/**
 * Decides which window-state events name the real foreground app: not us, not System UI, and the window
 * class must be an Activity (volume panel, shade and consent dialogs are not). Activity checks are cached.
 */
class ForegroundFilter(
    private val ownPackage: String,
    private val isActivity: (pkg: String, className: String) -> Boolean,
) {
    private val cache = HashMap<String, Boolean>()

    fun accept(pkg: String?, className: String?): Boolean {
        if (pkg.isNullOrEmpty() || className.isNullOrEmpty()) return false
        if (pkg == ownPackage || pkg == SYSTEM_UI) return false
        return cache.getOrPut("$pkg/$className") { isActivity(pkg, className) }
    }

    private companion object { const val SYSTEM_UI = "com.android.systemui" }
}
