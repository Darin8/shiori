package app.shiori.hotkey

enum class HotkeyButtonAction { OPEN_ACCESSIBILITY, START_RECORDING, CANCEL_RECORDING }

/** What the setup screen's Hotkey button does: recording needs the accessibility service. */
fun hotkeyButtonAction(recording: Boolean, serviceRunning: Boolean): HotkeyButtonAction = when {
    recording -> HotkeyButtonAction.CANCEL_RECORDING
    !serviceRunning -> HotkeyButtonAction.OPEN_ACCESSIBILITY
    else -> HotkeyButtonAction.START_RECORDING
}
