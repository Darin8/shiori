package app.shiori.overlay


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPlacementTest {
    private val fw = 1280
    private val fh = 960
    private val pw = 560
    private val ph = 400

    private fun inside(p: PopupPos) {
        assertTrue("left ${p.left}", p.left >= 0 && p.left + pw <= fw)
        assertTrue("top ${p.top}", p.top >= 0 && p.top + ph <= fh)
    }

    private fun overlaps(p: PopupPos, w: Box) =
        p.left < w.right && p.left + pw > w.left && p.top < w.bottom && p.top + ph > w.top

    @Test fun widthIsFortyPercentButNotBelowMinimum() {
        assertEquals(512, popupWidth(1280, 2f, minDp = 200))
        assertEquals(600, popupWidth(1280, 2f, minDp = 300))
    }

    @Test fun widthNeverExceedsFrameMinusMargin() {
        assertEquals(400 - 32, popupWidth(400, 2f, minDp = 300))
    }

    @Test fun centerWordGoesBelow() {
        val w = Box(500f, 300f, 600f, 340f)
        val p = placePopup(fw, fh, w, pw, ph)
        inside(p)
        assertTrue(p.top >= w.bottom)
    }

    @Test fun bottomWordGoesAbove() {
        val w = Box(500f, 880f, 600f, 920f)
        val p = placePopup(fw, fh, w, pw, ph)
        inside(p)
        assertTrue(p.top + ph <= w.top)
    }

    @Test fun leftEdgeWord() {
        val w = Box(0f, 300f, 60f, 340f)
        val p = placePopup(fw, fh, w, pw, ph)
        inside(p)
        assertTrue(!overlaps(p, w))
    }

    @Test fun rightEdgeWord() {
        val w = Box(1220f, 300f, 1280f, 340f)
        val p = placePopup(fw, fh, w, pw, ph)
        inside(p)
        assertTrue(!overlaps(p, w))
    }

    @Test fun topEdgeWord() {
        val w = Box(500f, 0f, 600f, 40f)
        val p = placePopup(fw, fh, w, pw, ph)
        inside(p)
        assertTrue(p.top >= w.bottom)
    }

    @Test fun cornersStayOnScreen() {
        for (w in listOf(
            Box(0f, 0f, 60f, 40f), Box(1220f, 0f, 1280f, 40f),
            Box(0f, 920f, 60f, 960f), Box(1220f, 920f, 1280f, 960f),
        )) {
            val p = placePopup(fw, fh, w, pw, ph)
            inside(p)
            assertTrue("corner $w", !overlaps(p, w))
        }
    }

    @Test fun tallPopupAndMiddleWordUsesSide() {
        // Neither above nor below fits a 700 px popup around a word in the middle.
        val w = Box(300f, 450f, 400f, 490f)
        val p = placePopup(fw, fh, w, pw, 700)
        assertTrue(p.top >= 0 && p.top + 700 <= fh)
        assertTrue(p.left >= w.right)
    }

    @Test fun popupLargerThanFrameIsPinnedToOrigin() {
        val p = placePopup(500, 400, Box(100f, 100f, 150f, 130f), 600, 500)
        assertEquals(0, p.left)
        assertEquals(0, p.top)
    }

    @Test fun leaderFromPopupTopToWordBottomWhenPopupIsBelow() {
        val word = Box(500f, 750f, 682f, 802f)
        val popup = Box(380f, 836f, 800f, 1100f)
        assertEquals(Leader(591f, 836f, 591f, 802f), leaderLine(popup, word))
    }

    @Test fun leaderFromPopupBottomToWordTopWhenPopupIsAbove() {
        val word = Box(500f, 750f, 682f, 802f)
        val popup = Box(380f, 506f, 800f, 716f)
        assertEquals(Leader(591f, 716f, 591f, 750f), leaderLine(popup, word))
    }

    @Test fun leaderStaysInsidePopupCornersWhenWordIsFarToTheSide() {
        val word = Box(0f, 100f, 40f, 130f)
        val popup = Box(300f, 200f, 700f, 400f)
        val l = leaderLine(popup, word, inset = 14f)!!
        assertEquals(314f, l.x1, 0.01f)
        assertEquals(200f, l.y1, 0.01f)
        assertEquals(130f, l.y2, 0.01f)
    }

    @Test fun leaderIsHorizontalForSidePlacementAndNullWhenOverlapping() {
        val word = Box(100f, 100f, 200f, 140f)
        assertEquals(Leader(220f, 120f, 200f, 120f), leaderLine(Box(220f, 90f, 600f, 150f), word))
        assertEquals(null, leaderLine(Box(150f, 120f, 600f, 300f), word))
    }

    @Test fun textAtBottomPutsPopupInUpperArea() {
        val text = Box(90f, 760f, 1190f, 930f)
        val word = Box(300f, 800f, 380f, 840f)
        val r = placePopupClear(fw, fh, word, text, pw, ph)
        assertEquals(null, r.maxHeight)
        assertTrue(r.pos.top + ph <= text.top)
        assertTrue(r.pos.top >= 0)
        assertEquals(340, r.pos.left + pw / 2)
    }

    @Test fun textAtTopPutsPopupBelow() {
        val text = Box(90f, 30f, 1190f, 200f)
        val r = placePopupClear(fw, fh, Box(300f, 40f, 380f, 80f), text, pw, ph)
        assertEquals(null, r.maxHeight)
        assertTrue(r.pos.top >= text.bottom)
        assertTrue(r.pos.top + ph <= fh)
    }

    @Test fun popupIsClampedHorizontally() {
        val text = Box(0f, 760f, 1280f, 930f)
        assertEquals(16, placePopupClear(fw, fh, Box(0f, 800f, 60f, 840f), text, pw, ph).pos.left)
        assertEquals(fw - pw - 16, placePopupClear(fw, fh, Box(1220f, 800f, 1280f, 840f), text, pw, ph).pos.left)
    }

    @Test fun tallPopupShrinksIntoTheLargerBandAndScrolls() {
        val text = Box(90f, 560f, 1190f, 930f) // band above: 560-8-16 = 536
        val r = placePopupClear(fw, fh, Box(300f, 600f, 380f, 640f), text, pw, 900)
        assertEquals(536, r.maxHeight)
        assertEquals(16, r.pos.top)
        assertTrue(r.pos.top + 536 <= text.top)
    }

    @Test fun noRoomAnywhereFallsBackToBesideTheWord() {
        val text = Box(0f, 20f, 1280f, 940f)
        val r = placePopupClear(fw, fh, Box(300f, 450f, 380f, 490f), text, pw, ph)
        assertEquals(null, r.maxHeight)
    }

    // Star Ocean 3 on the Nova: a stray line at the top of a whole-screen read pushed the popup under the dialogue.
    @Test fun textAroundIgnoresLinesFarFromTheTappedLine() {
        val stray = Box(4f, 9f, 871f, 49f)
        val name = Box(193f, 573f, 778f, 647f)
        val speech = Box(200f, 626f, 693f, 697f)
        val word = Box(472f, 636f, 575f, 688f)
        assertEquals(Box(193f, 573f, 778f, 697f), textAround(listOf(stray, name, speech), word))
    }

    @Test fun textAroundFollowsAChainOfCloseLines() {
        val lines = listOf(Box(100f, 700f, 900f, 740f), Box(100f, 750f, 900f, 790f), Box(100f, 800f, 900f, 840f))
        assertEquals(Box(100f, 700f, 900f, 840f), textAround(lines, Box(150f, 805f, 200f, 835f)))
    }

    @Test fun textAroundSkipsSideTextOnTheSameRows() {
        val dialogue = Box(100f, 700f, 600f, 740f)
        val hud = Box(1000f, 690f, 1270f, 730f)
        assertEquals(dialogue, textAround(listOf(dialogue, hud), Box(150f, 705f, 200f, 735f)))
    }

    @Test fun textAroundIsNullWithoutLines() {
        assertEquals(null, textAround(emptyList(), Box(0f, 0f, 10f, 10f)))
    }

    @Test fun commonnessFollowsRank() {
        assertEquals(1f, commonness(1), 0.001f)
        assertEquals(0.05f, commonness(1_000_000), 0.001f)
        assertEquals(0.19f, commonness(11058), 0.01f)
    }
}
