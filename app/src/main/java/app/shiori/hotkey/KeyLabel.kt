package app.shiori.hotkey

import android.view.KeyEvent

fun keyLabel(keyCode: Int): String =
    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_")

fun keysLabel(keys: Set<Int>): String = keys.joinToString(" + ") { keyLabel(it) }
