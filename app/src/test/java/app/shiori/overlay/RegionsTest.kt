package app.shiori.overlay

import app.shiori.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionsTest {
    @Test fun `legacy single value parses as one region`() {
        assertEquals(listOf(Box(1f, 2f, 30f, 40f)), parseRegions("1,2,30,40"))
    }

    @Test fun `round trip and bad input`() {
        val r = listOf(Box(1f, 2f, 3f, 4f), Box(10f, 20f, 30f, 40f))
        assertEquals(r, parseRegions(serializeRegions(r)))
        assertEquals(emptyList<Box>(), parseRegions(null))
        assertEquals(emptyList<Box>(), parseRegions("junk;1,2"))
    }

    @Test fun `regionAt picks topmost and null on empty space`() {
        val r = listOf(Box(0f, 0f, 100f, 100f), Box(50f, 50f, 150f, 150f))
        assertEquals(1, regionAt(r, 60f, 60f))
        assertEquals(0, regionAt(r, 10f, 10f))
        assertNull(regionAt(r, 300f, 300f))
    }

    @Test fun `label`() {
        assertEquals("Whole screen", regionLabel(0))
        assertEquals("3 regions", regionLabel(3))
    }
}

class RegionEditTest {
    private val frame = 1000f
    private val a = Box(100f, 100f, 300f, 300f)

    @Test fun `body hit moves, empty space draws`() {
        val r = listOf(a)
        assertEquals(RegionHit(0, 0), hitTestRegions(r, null, 200f, 200f, 20f))
        assertNull(hitTestRegions(r, null, 600f, 600f, 20f))
    }

    @Test fun `selected box handles win over body`() {
        val r = listOf(a)
        assertEquals(RegionHit(0, EDGE_R or EDGE_B), hitTestRegions(r, 0, 300f, 300f, 20f))
        assertEquals(RegionHit(0, EDGE_L), hitTestRegions(r, 0, 105f, 200f, 20f))
        assertEquals(RegionHit(0, EDGE_T), hitTestRegions(r, 0, 200f, 95f, 20f))
        assertEquals(RegionHit(0, 0), hitTestRegions(r, 0, 200f, 200f, 20f))
        // unselected box has no handles
        assertEquals(RegionHit(0, 0), hitTestRegions(r, null, 299f, 299f, 20f))
    }

    @Test fun `corner target wins over body within its radius`() {
        val r = listOf(a)
        val corner = RegionHit(0, EDGE_L or EDGE_T)
        assertEquals(corner, hitTestRegions(r, 0, 100f + 15f, 100f + 15f, 22f)) // inside the box
        assertEquals(corner, hitTestRegions(r, 0, 100f - 15f, 100f - 15f, 22f)) // just outside
        assertEquals(RegionHit(0, 0), hitTestRegions(r, 0, 100f + 40f, 100f + 40f, 22f))
        assertNull(hitTestRegions(r, 0, 100f - 40f, 100f - 40f, 22f))
    }

    @Test fun `small box keeps a body`() {
        val r = listOf(Box(0f, 0f, 30f, 30f))
        assertEquals(RegionHit(0, 0), hitTestRegions(r, 0, 15f, 15f, 40f))
    }

    @Test fun `move clamps to frame`() {
        assertEquals(Box(150f, 120f, 350f, 320f), moveBox(a, 50f, 20f, frame, frame))
        assertEquals(Box(0f, 0f, 200f, 200f), moveBox(a, -500f, -500f, frame, frame))
        assertEquals(Box(800f, 800f, 1000f, 1000f), moveBox(a, 5000f, 5000f, frame, frame))
    }

    @Test fun `resize clamps and keeps minimum`() {
        assertEquals(Box(100f, 100f, 500f, 400f), resizeBox(a, EDGE_R or EDGE_B, 500f, 400f, frame, frame))
        assertEquals(Box(100f, 100f, 108f, 300f), resizeBox(a, EDGE_R, 0f, 0f, frame, frame))
        assertEquals(Box(0f, 100f, 300f, 300f), resizeBox(a, EDGE_L, -50f, 0f, frame, frame))
        assertEquals(Box(100f, 100f, 1000f, 300f), resizeBox(a, EDGE_R, 5000f, 0f, frame, frame))
    }

    @Test fun `remove only the selected region`() {
        val r = listOf(a, Box(1f, 1f, 50f, 50f), Box(5f, 5f, 60f, 60f))
        assertEquals(listOf(r[0], r[2]), removeRegion(r, 1))
        assertEquals(r, removeRegion(r, null))
        assertEquals(r, removeRegion(r, 9))
    }
}
