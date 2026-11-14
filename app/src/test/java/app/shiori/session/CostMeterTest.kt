package app.shiori.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostMeterTest {
    private val out = mutableListOf<String>()
    private var wall = 0L
    private var cpu = 0L
    private val meter = CostMeter({ out += it }, { cpu }, { wall }, { "battery=50% current=-300.0mA" })

    @Test
    fun `every pass logs capture detect recog counts and total`() {
        meter.onPass(40, 300, 120, 2, 1, 500)
        assertEquals(
            listOf("cost pass capture=40ms detect=300ms recog=120ms reused=2 recognized=1 total=500ms"),
            out,
        )
    }

    @Test
    fun `a summary comes after 30 s with cpu against wall, skips and the battery`() {
        meter.onPass(10, 50, 40, 1, 0, 100)
        wall = 15_000; cpu = 1_000
        meter.onSkip("unchanged"); meter.onSkip("unchanged"); meter.onSkip("busy")
        meter.onPass(10, 50, 40, 0, 2, 200)
        assertEquals(2, out.size)
        wall = 30_000; cpu = 3_000
        meter.onPass(10, 50, 40, 3, 0, 300)
        assertEquals(4, out.size)
        val s = out.last()
        assertTrue(s, s.startsWith("cost summary passes=3 avgTotal=200ms reused=4 recognized=2 skipunchanged=2 skipaway=0 skippopup=0 skipbusy=1 skipnoregion=0 cpu=3000ms wall=30000ms cpuOfOneCore=10.0%"))
        assertTrue(s, s.endsWith("battery=50% current=-300.0mA"))
    }

    @Test
    fun `a window of only skips still gets a summary`() {
        meter.onSkip("unchanged")
        wall = 30_000
        meter.onSkip("unchanged")
        assertTrue(out.last(), out.last().contains("passes=0 avgTotal=0ms") && out.last().contains("skipunchanged=2"))
    }

    @Test
    fun `the window restarts after a summary and after reset`() {
        meter.onPass(1, 1, 1, 0, 0, 2)
        wall = 30_000; meter.onPass(1, 1, 1, 0, 0, 2)
        out.clear()
        wall = 40_000; meter.onPass(1, 1, 1, 0, 0, 2)
        assertEquals(1, out.size)
        meter.reset()
        wall = 100_000; meter.onPass(1, 1, 1, 0, 0, 2)
        wall = 110_000; meter.onPass(1, 1, 1, 0, 0, 2)
        assertTrue(out.none { it.startsWith("cost summary") })
    }
}
