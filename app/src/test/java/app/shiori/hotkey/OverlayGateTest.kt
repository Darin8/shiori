package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGateTest {
    private val gate = OverlayGate()

    private fun app(pkg: String?, active: Boolean = true, focused: Boolean = active) =
        WinSnap(WinKind.APP, pkg, active, focused, covers = true)
    private fun sys(active: Boolean = false, covers: Boolean = false) =
        WinSnap(WinKind.SYSTEM, "com.android.systemui", active, active, covers)
    private val bar = WinSnap(WinKind.SYSTEM, "com.android.systemui", active = false, focused = false, covers = false)
    private val ime = WinSnap(WinKind.OTHER, "ime", active = false, focused = false, covers = false)

    private fun inGame() = gate.also { it.setGame("game"); it.refresh(listOf(app("game"), bar)) }

    @Test fun `never hidden before a game is known`() {
        assertFalse(gate.refresh(listOf(app("launcher"))))
        assertFalse(gate.hidden)
    }

    @Test fun `game active with status bar and ime stays visible`() {
        inGame()
        assertFalse(gate.refresh(listOf(app("game"), bar, ime)))
        assertFalse(gate.hidden)
    }

    @Test fun `another app active hides then game back shows`() {
        inGame()
        assertTrue(gate.refresh(listOf(app("launcher"), app("game", active = false), bar)))
        assertTrue(gate.hidden)
        assertFalse(gate.refresh(listOf(app("game"), bar)))
        assertFalse(gate.hidden)
    }

    @Test fun `our own screen hides`() {
        inGame()
        assertTrue(gate.refresh(listOf(app("app.shiori"), app("game", active = false))))
    }

    @Test fun `active system window hides even over the game`() {
        inGame()
        assertTrue(gate.refresh(listOf(sys(active = true), app("game", active = false))))
    }

    @Test fun `covering system window hides even when game stays active`() {
        inGame()
        assertTrue(gate.refresh(listOf(sys(covers = true), app("game"))))
        assertFalse(gate.refresh(listOf(bar, app("game"))).also { assertFalse(gate.hidden) })
    }

    @Test fun `no active application window hides`() {
        inGame()
        assertTrue(gate.refresh(listOf(bar, app("game", active = false))))
        assertTrue(gate.refresh(emptyList()).not() && gate.hidden)
    }

    @Test fun `unreadable package keeps the previous decision`() {
        inGame()
        assertFalse(gate.refresh(listOf(app(null), bar)))
        assertFalse(gate.hidden)
        gate.refresh(listOf(app("other")))
        assertTrue(gate.hidden)
        gate.refresh(listOf(app(null)))
        assertTrue(gate.hidden)
    }

    @Test fun `focused counts as active`() {
        inGame()
        assertFalse(gate.refresh(listOf(app("game", active = false, focused = true))))
        assertFalse(gate.hidden)
    }

    @Test fun `only the first hiding snapshot reports a change`() {
        inGame()
        assertTrue(gate.refresh(listOf(app("launcher"))))
        assertFalse(gate.refresh(listOf(sys(active = true))))
        assertTrue(gate.hidden)
    }

    @Test fun `a pass on a new game while it is on top is not hidden`() {
        inGame()
        gate.refresh(listOf(app("game2")))
        assertTrue(gate.hidden)
        gate.setGame("game2")
        gate.refresh(listOf(app("game2")))
        assertFalse(gate.hidden)
    }

    @Test fun `setGame to the active app unhides immediately`() {
        gate.setGame("settings"); gate.refresh(listOf(app("dusk"), bar))
        assertTrue(gate.hidden)
        gate.setGame("dusk")
        assertFalse(gate.hidden)
    }

    @Test fun `setGame to a background app hides immediately`() {
        inGame()
        gate.refresh(listOf(app("game"), bar))
        assertFalse(gate.hidden)
        gate.setGame("other")
        assertTrue(gate.hidden)
    }

    @Test fun `stale game from earlier session does not block a new pass`() {
        gate.setGame("old"); gate.refresh(listOf(app("dusk"), bar))
        assertTrue(gate.hidden)
        gate.setGame("dusk") // a new pass starts
        assertFalse(gate.hidden)
    }

    // ---- focusedGame: which package a pass may run on, independent of the gate's old binding

    private val homes = setOf("launcher")
    private fun fg(vararg w: WinSnap) = focusedGame(w.toList(), "app.shiori", homes)

    @Test fun `pass skips while launcher recents or our app is active`() {
        assertEquals(null, fg(app("launcher"), bar))
        assertEquals(null, fg(app("app.shiori"), bar))
        assertEquals(null, fg(sys(active = true), app("game", active = false)))
        assertEquals(null, fg(sys(covers = true), app("game")))
        assertEquals(null, fg(bar, app("game", active = false)))
        assertEquals(null, fg(app(null), bar))
    }

    @Test fun `pass runs and binds to the newly focused game after being away`() {
        inGame()
        gate.refresh(listOf(app("launcher"), bar)) // away: gate hidden
        assertTrue(gate.hidden)
        gate.refresh(listOf(app("other"), bar)) // still bound to "game", hidden forever without a pass
        assertTrue(gate.hidden)
        val pkg = fg(app("other"), bar)
        assertEquals("other", pkg)
        gate.setGame(pkg)
        assertFalse(gate.hidden)
    }

    @Test fun `hotkey after returning to a game shows the layer`() {
        gate.setGame("old.game"); gate.refresh(listOf(app("launcher"), bar))
        assertTrue(gate.hidden)
        gate.refresh(listOf(app("dev.game"), bar))
        val pkg = fg(app("dev.game"), bar)
        assertEquals("dev.game", pkg)
        gate.setGame(pkg)
        assertFalse(gate.hidden)
    }
}
