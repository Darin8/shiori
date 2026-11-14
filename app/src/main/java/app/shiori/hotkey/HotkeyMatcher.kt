package app.shiori.hotkey

enum class Decision { TRIGGER, CONSUME, PASS }

// Mirror android.view.KeyEvent.ACTION_DOWN / ACTION_UP so this file stays pure Kotlin.
const val ACTION_DOWN = 0
const val ACTION_UP = 1

class HotkeyMatcher(keys: Set<Int>) {
    var keys: Set<Int> = keys
        set(value) {
            field = value
            held.clear()
            completer = null
        }

    private val held = mutableSetOf<Int>()
    private var completer: Int? = null

    fun onKey(action: Int, keyCode: Int, repeatCount: Int): Decision {
        if (keyCode !in keys) return Decision.PASS
        val single = keys.size == 1
        if (action == ACTION_DOWN) {
            if (repeatCount > 0) {
                return if (single || keyCode == completer) Decision.CONSUME else Decision.PASS
            }
            held.add(keyCode)
            if (held.containsAll(keys)) {
                completer = keyCode
                return Decision.TRIGGER
            }
            return Decision.PASS
        }
        held.remove(keyCode)
        if (single || keyCode == completer) {
            completer = null
            return Decision.CONSUME
        }
        return Decision.PASS
    }
}
