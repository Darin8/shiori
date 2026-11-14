package app.shiori

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStatusTest {
    @Test
    fun `missing lists unset items in fixed order`() {
        val status = SetupStatus(false, emptySet(), emptyList())
        assertEquals(listOf("Accessibility", "Hotkey", "Dictionaries"), status.missing())
    }

    @Test
    fun `missing is empty when everything is set`() {
        val status = SetupStatus(true, setOf(109), listOf("JMdict"))
        assertEquals(emptyList<String>(), status.missing())
    }

    @Test
    fun `all unset lists all three labels in order`() {
        val status = SetupStatus(false, emptySet(), emptyList())
        assertEquals(listOf("Accessibility", "Hotkey", "Dictionaries"), status.missing())
    }
}
