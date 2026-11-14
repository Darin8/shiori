package app.shiori.hotkey

class HotkeyRecorder {
    private val down = mutableSetOf<Int>()
    private val chord = linkedSetOf<Int>()

    fun reset() {
        down.clear()
        chord.clear()
    }

    /** Returns the recorded set once every pressed button has been released. */
    fun onKey(action: Int, keyCode: Int): Set<Int>? {
        if (action == ACTION_DOWN) {
            if (down.add(keyCode) && chord.size < MAX_KEYS) chord.add(keyCode)
            return null
        }
        down.remove(keyCode)
        if (down.isEmpty() && chord.isNotEmpty()) {
            val result = chord.toSet()
            chord.clear()
            return result
        }
        return null
    }

    companion object {
        const val MAX_KEYS = 3
    }
}
