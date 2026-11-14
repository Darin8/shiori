package app.shiori

data class SetupStatus(
    val hotkeyService: Boolean,
    val hotkeyKeys: Set<Int>,
    val dictionaries: List<String>,
)

fun SetupStatus.missing(): List<String> = buildList {
    if (!hotkeyService) add("Accessibility")
    if (hotkeyKeys.isEmpty()) add("Hotkey")
    if (dictionaries.isEmpty()) add("Dictionaries")
}
