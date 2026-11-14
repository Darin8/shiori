package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Test

class HotkeyButtonTest {
    @Test fun `service missing opens accessibility settings`() =
        assertEquals(HotkeyButtonAction.OPEN_ACCESSIBILITY, hotkeyButtonAction(recording = false, serviceRunning = false))

    @Test fun `service running starts recording`() =
        assertEquals(HotkeyButtonAction.START_RECORDING, hotkeyButtonAction(false, true))

    @Test fun `tapping while recording cancels`() {
        assertEquals(HotkeyButtonAction.CANCEL_RECORDING, hotkeyButtonAction(true, true))
        assertEquals(HotkeyButtonAction.CANCEL_RECORDING, hotkeyButtonAction(true, false))
    }
}
