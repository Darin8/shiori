package app.shiori.anki

sealed interface AnkiTarget {
    data class Ok(val modelId: Long, val deckId: Long, val fieldNames: List<String>) : AnkiTarget
    data class Unavailable(val label: String) : AnkiTarget
}

const val MODEL_NAME = "JP Mining Note"
const val DECK_NAME = "Default"
const val ANKI_ERROR_LABEL = "AnkiDroid error"

/** Checks in order: installed, permitted, note type, deck. Names match exactly. */
fun resolveTarget(
    installed: Boolean,
    permitted: Boolean,
    models: Map<Long, String>,
    decks: Map<Long, String>,
    fieldNames: (Long) -> List<String>,
): AnkiTarget {
    if (!installed) return AnkiTarget.Unavailable("AnkiDroid missing")
    if (!permitted) return AnkiTarget.Unavailable("Allow AnkiDroid access")
    val modelId = models.entries.firstOrNull { it.value == MODEL_NAME }?.key
        ?: return AnkiTarget.Unavailable("Note type missing")
    val deckId = decks.entries.firstOrNull { it.value == DECK_NAME }?.key
        ?: return AnkiTarget.Unavailable("Deck missing")
    return AnkiTarget.Ok(modelId, deckId, fieldNames(modelId))
}

/**
 * AnkiDroid's addMediaFromUri returns `<img src="name" />` (older/newer versions may return just the name);
 * returns the bare file name, or null when nothing usable came back.
 */
fun parseMediaFileName(returned: String): String? {
    val m = Regex("""src\s*=\s*["']([^"']+)["']""").find(returned)
    val name = (m?.groupValues?.get(1) ?: returned).trim()
    return name.ifEmpty { null }
}
