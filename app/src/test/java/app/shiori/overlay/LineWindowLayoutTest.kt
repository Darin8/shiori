package app.shiori.overlay

import app.shiori.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineWindowLayoutTest {
    @Test fun `identity map grows the box by the slop`() {
        val r = lineWindowRect(Box(100f, 200f, 300f, 240f), FrameMap(1280, 960), 12f, 1280, 960)
        assertEquals(WinRect(88, 188, 312, 252), r)
    }

    @Test fun `scaled frame maps to screen pixels`() {
        val r = lineWindowRect(Box(100f, 100f, 200f, 150f), FrameMap(640, 480, 1280, 960), 10f, 1280, 960)
        assertEquals(WinRect(180, 180, 420, 320), r)
    }

    @Test fun `clamped to the screen`() {
        val r = lineWindowRect(Box(-5f, 950f, 1300f, 970f), FrameMap(1280, 960), 12f, 1280, 960)
        assertEquals(WinRect(0, 938, 1280, 960), r)
    }

    @Test fun `fully off screen gives no window`() {
        assertNull(lineWindowRect(Box(2000f, 10f, 2100f, 40f), FrameMap(1280, 960), 12f, 1280, 960))
    }
}
