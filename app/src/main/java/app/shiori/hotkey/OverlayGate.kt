package app.shiori.hotkey

enum class WinKind { APP, SYSTEM, OTHER }

/** One accessibility window, reduced to what the gate needs. [pkg] is null when it could not be read. */
data class WinSnap(
    val kind: WinKind,
    val pkg: String?,
    val active: Boolean,
    val focused: Boolean,
    /** The window covers (nearly) the whole display: shade, recents, a full-screen System UI panel. */
    val covers: Boolean,
)

/**
 * Whether the tap layer may be shown: only while the package the OCR ran on ([game]) is the active app window
 * and no System UI window is active or covering the screen. Thread-safe: main writes snapshots, passes bind the game.
 * Hiding is immediate; an unreadable snapshot keeps the previous decision.
 */
class OverlayGate {
    @Volatile private var game: String? = null
    @Volatile private var lastSnapshot: List<WinSnap>? = null

    /** True when the layer must not be shown. Readable from any thread; writes are synchronized. */
    @Volatile var hidden: Boolean = false
        private set

    /** The package a pass ran on (null: unknown, never hides). A new game is judged at once against the last snapshot. */
    @Synchronized fun setGame(pkg: String?) {
        game = pkg
        val snap = lastSnapshot
        if (pkg == null || snap == null) hidden = false else decide(snap, pkg)
    }

    /** Returns true when this snapshot just made the layer hidden. */
    @Synchronized fun refresh(windows: List<WinSnap>): Boolean {
        lastSnapshot = windows
        val bound = game ?: return false
        val before = hidden
        decide(windows, bound)
        return !before && hidden
    }

    private fun decide(windows: List<WinSnap>, game: String) {
        val before = hidden
        val systemOnTop = windows.any { it.kind == WinKind.SYSTEM && (it.active || it.focused || it.covers) }
        val appWindow = windows.firstOrNull { it.kind == WinKind.APP && (it.active || it.focused) }
        hidden = when {
            systemOnTop -> true
            appWindow == null -> true // no app window has focus (transition or recents)
            appWindow.pkg == null -> before // cannot tell: keep the last decision
            else -> appWindow.pkg != game
        }
    }
}

/**
 * The package a pass may run on: the active, focused application window of a normal app. Null while System UI
 * is active or covering (shade, recents), no app window has focus, or the focused app is [ownPackage] or a home
 * package. Independent of the gate's earlier binding, so a pass can start after the user switched games.
 */
fun focusedGame(windows: List<WinSnap>, ownPackage: String, homePackages: Set<String>): String? {
    if (windows.any { it.kind == WinKind.SYSTEM && (it.active || it.focused || it.covers) }) return null
    val pkg = windows.firstOrNull { it.kind == WinKind.APP && (it.active || it.focused) }?.pkg ?: return null
    if (pkg == ownPackage || pkg in homePackages || pkg == "com.android.systemui") return null
    return pkg
}

/** The latest window snapshot and the game in front, built on the main thread; background passes only read it. */
class FocusSnapshot(val windows: List<WinSnap>, val focused: String?) {
    companion object { val EMPTY = FocusSnapshot(emptyList(), null) }
}

/**
 * Holds the latest [FocusSnapshot]. [update] and [clear] run on the main thread (where windows are read);
 * [latest] is safe from any thread, so passes never touch windows, root nodes or PackageManager.
 */
class WindowTracker(private val ownPackage: String, private val homePackages: () -> Set<String>) {
    @Volatile var latest: FocusSnapshot = FocusSnapshot.EMPTY
        private set

    fun update(windows: List<WinSnap>): FocusSnapshot =
        FocusSnapshot(windows, focusedGame(windows, ownPackage, homePackages())).also { latest = it }

    fun clear() { latest = FocusSnapshot.EMPTY }
}

/** Home-screen packages, cached because the lookup is an IPC. Refreshed on [invalidate] and after [ttlMs]. */
class HomePackageCache(
    private val compute: () -> Set<String>,
    private val now: () -> Long,
    private val ttlMs: Long = 60_000,
) {
    private var cached: Set<String>? = null
    private var at = 0L

    @Synchronized fun get(): Set<String> {
        val time = now()
        val hit = cached
        if (hit != null && time - at < ttlMs) return hit
        return compute().also { cached = it; at = time }
    }

    @Synchronized fun invalidate() { cached = null }
}
