package app.shiori.overlay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json

private val BLOCK_TAGS = setOf(
    "ul", "ol", "li", "div", "p", "tr", "table", "details", "summary", "br", "h1", "h2", "h3", "h4", "dl", "dt", "dd",
)
private val SKIPPED_TAGS = setOf("img", "rt", "rp")

// Jitendex parts that are metadata or very long; the popup keeps the definitions.
private val SKIPPED_CONTENT = setOf("extra-info", "attribution", "forms", "example-sentence")

/** Flattens a Yomitan glossary JSON array (strings and structured-content) into plain text lines. */
fun glossaryPlainLines(glossaryJson: String): List<String> {
    val root = try {
        Json.parseToJsonElement(glossaryJson)
    } catch (e: Exception) {
        return emptyList()
    }
    val out = StringBuilder()
    val items = if (root is JsonArray) root else JsonArray(listOf(root))
    for (item in items) {
        out.append('\n')
        when {
            item is JsonPrimitive -> out.append(item.contentOrNull.orEmpty())
            item is JsonObject && (item["type"] as? JsonPrimitive)?.contentOrNull == "structured-content" ->
                item["content"]?.let { walk(it, out) }
            else -> Unit // image and unknown items
        }
    }
    return out.lines().map { it.replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
}

private fun walk(node: JsonElement, out: StringBuilder) {
    when (node) {
        is JsonPrimitive -> out.append(node.contentOrNull.orEmpty())
        is JsonArray -> node.forEach { walk(it, out) }
        is JsonObject -> {
            val tag = (node["tag"] as? JsonPrimitive)?.contentOrNull
            if (tag in SKIPPED_TAGS) return
            val data = node["data"] as? JsonObject
            val kind = (data?.get("content") as? JsonPrimitive)?.contentOrNull
            if (kind in SKIPPED_CONTENT) return
            val block = tag in BLOCK_TAGS
            if (block) out.append('\n')
            node["content"]?.let { walk(it, out) }
            if (block) out.append('\n')
            else if (data?.containsKey("code") == true) out.append(' ') // part-of-speech chips
        }
    }
}

/** One numbered sense: misc labels (e.g. "abbr.", "wrestling") kept apart from the gloss lines. */
data class GlossSense(val tags: List<String>, val glosses: List<String>)

/** Glossary split for display: part-of-speech labels (for chips) and senses. */
data class GlossaryView(val partsOfSpeech: List<String>, val senses: List<GlossSense>)

private class SenseBuilder { val tags = ArrayList<String>(); val glosses = ArrayList<String>() }

private fun flatText(node: JsonElement): String {
    val sb = StringBuilder()
    walk(node, sb)
    return sb.toString().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
}

/** Splits a glossary into POS labels and senses; Jitendex structure is parsed, anything else becomes plain lines. */
fun glossarySenses(glossaryJson: String): GlossaryView {
    val root = try {
        Json.parseToJsonElement(glossaryJson)
    } catch (e: Exception) {
        return GlossaryView(emptyList(), emptyList())
    }
    val pos = LinkedHashSet<String>()
    val senses = ArrayList<SenseBuilder>()
    val implicit = SenseBuilder()

    fun visit(node: JsonElement, target: SenseBuilder, inSense: Boolean) {
        when (node) {
            is JsonArray -> node.forEach { visit(it, target, inSense) }
            is JsonObject -> {
                val tag = (node["tag"] as? JsonPrimitive)?.contentOrNull
                if (tag in SKIPPED_TAGS) return
                val data = node["data"] as? JsonObject
                val kind = (data?.get("content") as? JsonPrimitive)?.contentOrNull
                val content = node["content"]
                when {
                    kind in SKIPPED_CONTENT -> Unit
                    data?.containsKey("sense-number") == true -> {
                        val b = SenseBuilder()
                        content?.let { visit(it, b, true) }
                        if (b.glosses.isNotEmpty()) senses += b
                    }
                    kind == "glossary" -> {
                        val items = if (content is JsonArray) content.toList() else listOfNotNull(content)
                        for (li in items) flatText(li).takeIf { it.isNotEmpty() }?.let { target.glosses += it }
                    }
                    data?.containsKey("code") == true -> {
                        val label = content?.let { flatText(it) }.orEmpty()
                        if (label.isNotEmpty()) if (inSense) target.tags += label else pos += label
                    }
                    else -> content?.let { visit(it, target, inSense) }
                }
            }
            else -> Unit
        }
    }

    val items = if (root is JsonArray) root.toList() else listOf(root)
    for (item in items) {
        when {
            item is JsonPrimitive ->
                item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { implicit.glosses += it }
            item is JsonObject && (item["type"] as? JsonPrimitive)?.contentOrNull == "structured-content" ->
                item["content"]?.let { visit(it, implicit, false) }
            else -> Unit
        }
    }
    if (implicit.glosses.isNotEmpty()) senses += implicit
    if (senses.isEmpty()) {
        val lines = glossaryPlainLines(glossaryJson)
        return GlossaryView(emptyList(), lines.map { GlossSense(emptyList(), listOf(it)) })
    }
    // Plain-string glossaries (no structure): one sense per string.
    val plain = root is JsonArray && root.all { it is JsonPrimitive }
    if (plain) return GlossaryView(emptyList(), implicit.glosses.map { GlossSense(emptyList(), listOf(it)) })
    return GlossaryView(pos.toList(), senses.map { GlossSense(it.tags, it.glosses) })
}
