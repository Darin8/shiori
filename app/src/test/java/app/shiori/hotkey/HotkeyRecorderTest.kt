package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotkeyRecorderTest {
    @Test fun `single press records one key`() {
        val r = HotkeyRecorder()
        assertNull(r.onKey(ACTION_DOWN, 96))
        assertEquals(setOf(96), r.onKey(ACTION_UP, 96))
    }

    @Test fun `held chord records both keys`() {
        val r = HotkeyRecorder()
        r.onKey(ACTION_DOWN, 109)
        r.onKey(ACTION_DOWN, 107)
        r.onKey(ACTION_UP, 107)
        assertEquals(setOf(109, 107), r.onKey(ACTION_UP, 109))
    }

    @Test fun `recording finishes only when all buttons are released`() {
        val r = HotkeyRecorder()
        r.onKey(ACTION_DOWN, 109)
        r.onKey(ACTION_DOWN, 107)
        assertNull(r.onKey(ACTION_UP, 109))
        assertEquals(setOf(109, 107), r.onKey(ACTION_UP, 107))
    }

    @Test fun `reset clears a half-recorded chord`() {
        val r = HotkeyRecorder()
        r.onKey(ACTION_DOWN, 109)
        r.reset()
        r.onKey(ACTION_DOWN, 96)
        assertEquals(setOf(96), r.onKey(ACTION_UP, 96))
    }
}
