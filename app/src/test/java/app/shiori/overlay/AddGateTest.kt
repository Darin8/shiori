package app.shiori.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddGateTest {
    @Test fun `first press from READY starts adding, second is ignored`() {
        val g = AddGate(AddState.READY)
        assertTrue(g.press())
        assertEquals(AddState.ADDING, g.state)
        assertFalse(g.press())
    }

    @Test fun `no press while checking, duplicate, added or unavailable`() {
        for (s in listOf(AddState.CHECKING, AddState.DUPLICATE, AddState.ADDED, AddState.UNAVAILABLE)) {
            val g = AddGate(s)
            assertFalse(g.press())
            assertEquals(s, g.state)
        }
    }
}
