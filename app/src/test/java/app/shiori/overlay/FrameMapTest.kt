package app.shiori.overlay

import app.shiori.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMapTest {
    @Test
    fun `identity when the frame is the display and the window starts at the origin`() {
        val m = FrameMap(1280, 960)
        assertEquals(Box(10f, 20f, 30f, 40f), m.toView(Box(10f, 20f, 30f, 40f)))
        assertEquals(640f, m.toFrameX(640f), 0f)
    }

    @Test
    fun `a window pushed down by a status bar shifts boxes up in view space`() {
        val m = FrameMap(1280, 960, originY = 75f)
        assertEquals(Box(100f, 125f, 200f, 175f), m.toView(Box(100f, 200f, 200f, 250f)))
        assertEquals(200f, m.toFrameY(125f), 0f)
    }

    @Test
    fun `a frame captured at half size scales to the display`() {
        val m = FrameMap(640, 480, 1280, 960)
        assertEquals(Box(200f, 100f, 400f, 300f), m.toView(Box(100f, 50f, 200f, 150f)))
        assertEquals(100f, m.toFrameX(200f), 0f)
    }

    @Test
    fun `view to frame inverts frame to view`() {
        val m = FrameMap(1000, 800, 1280, 960, originX = 12f, originY = 30f)
        val b = Box(123f, 456f, 321f, 654f)
        val v = m.toView(b)
        assertEquals(b.left, m.toFrameX(v.left), 0.001f)
        assertEquals(b.bottom, m.toFrameY(v.bottom), 0.001f)
    }

    @Test
    fun `empty frame size falls back to identity scale`() {
        val m = FrameMap(0, 0, 1280, 960)
        assertEquals(5f, m.toViewX(5f), 0f)
    }
}
