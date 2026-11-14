package app.shiori.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrSettingsTest {
    @Test
    fun `mode defaults to hotkey for anything unknown`() {
        assertEquals(OcrMode.HOTKEY, OcrMode.fromPref(null))
        assertEquals(OcrMode.HOTKEY, OcrMode.fromPref("nonsense"))
        assertEquals(OcrMode.LIVE, OcrMode.fromPref("live"))
    }

    @Test
    fun `interval keeps the four choices and defaults to 3`() {
        assertEquals(listOf(1, 2, 3, 5), LiveInterval.choices)
        assertEquals(3, LiveInterval.fromPref(0))
        assertEquals(3, LiveInterval.fromPref(4))
        assertEquals(5, LiveInterval.fromPref(5))
    }
}
