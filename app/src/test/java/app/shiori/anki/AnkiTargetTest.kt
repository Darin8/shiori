package app.shiori.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiTargetTest {
    private val models = mapOf(1L to "Basic", 2L to "JP Mining Note")
    private val decks = mapOf(10L to "Default", 11L to "Other")
    private val fields = { id: Long -> if (id == 2L) listOf("Key", "Word") else emptyList() }

    @Test
    fun `resolveTarget reports each missing piece`() {
        assertEquals(AnkiTarget.Unavailable("AnkiDroid missing"), resolveTarget(false, false, models, decks, fields))
        assertEquals(AnkiTarget.Unavailable("Allow AnkiDroid access"), resolveTarget(true, false, models, decks, fields))
        assertEquals(
            AnkiTarget.Unavailable("Note type missing"),
            resolveTarget(true, true, mapOf(1L to "jp mining note"), decks, fields),
        )
        assertEquals(
            AnkiTarget.Unavailable("Deck missing"),
            resolveTarget(true, true, models, mapOf(11L to "default"), fields),
        )
        assertEquals(AnkiTarget.Ok(2L, 10L, listOf("Key", "Word")), resolveTarget(true, true, models, decks, fields))
    }

    @Test
    fun `pictureFileName parses the name out of what AnkiDroid returns`() {
        assertEquals("a b.webp", parseMediaFileName("<img src=\"a b.webp\" />"))
        assertEquals("x.webp", parseMediaFileName("<img src='x.webp'>"))
        assertEquals("x.webp", parseMediaFileName("x.webp"))
        assertEquals(null, parseMediaFileName(""))
    }

    @Test
    fun `provider error label differs from missing`() {
        assertEquals("AnkiDroid error", ANKI_ERROR_LABEL)
        assert(ANKI_ERROR_LABEL != "AnkiDroid missing")
    }
}
