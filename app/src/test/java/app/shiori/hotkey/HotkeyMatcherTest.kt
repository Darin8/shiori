package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Test

class HotkeyMatcherTest {
    private val sel = 109
    private val r3 = 107
    private val a = 96

    @Test fun `single button down triggers`() {
        val m = HotkeyMatcher(setOf(a))
        assertEquals(Decision.TRIGGER, m.onKey(ACTION_DOWN, a, 0))
    }

    @Test fun `single button up and repeats are consumed`() {
        val m = HotkeyMatcher(setOf(a))
        m.onKey(ACTION_DOWN, a, 0)
        assertEquals(Decision.CONSUME, m.onKey(ACTION_DOWN, a, 1))
        assertEquals(Decision.CONSUME, m.onKey(ACTION_UP, a, 0))
    }

    @Test fun `combo triggers on the completing press, in either order`() {
        val m = HotkeyMatcher(setOf(sel, r3))
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, sel, 0))
        assertEquals(Decision.TRIGGER, m.onKey(ACTION_DOWN, r3, 0))
        m.onKey(ACTION_UP, r3, 0)
        m.onKey(ACTION_UP, sel, 0)
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, r3, 0))
        assertEquals(Decision.TRIGGER, m.onKey(ACTION_DOWN, sel, 0))
    }

    @Test fun `combo passes the first buttons down and up`() {
        val m = HotkeyMatcher(setOf(sel, r3))
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, sel, 0))
        m.onKey(ACTION_DOWN, r3, 0)
        assertEquals(Decision.CONSUME, m.onKey(ACTION_DOWN, r3, 1))
        assertEquals(Decision.CONSUME, m.onKey(ACTION_UP, r3, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_UP, sel, 0))
    }

    @Test fun `combo does not trigger when buttons are pressed one after another without overlap`() {
        val m = HotkeyMatcher(setOf(sel, r3))
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, sel, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_UP, sel, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, r3, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_UP, r3, 0))
    }

    @Test fun `other keys pass`() {
        val m = HotkeyMatcher(setOf(a))
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, 97, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_UP, 97, 0))
    }

    @Test fun `empty set passes everything`() {
        val m = HotkeyMatcher(emptySet())
        assertEquals(Decision.PASS, m.onKey(ACTION_DOWN, a, 0))
        assertEquals(Decision.PASS, m.onKey(ACTION_UP, a, 0))
    }
}
