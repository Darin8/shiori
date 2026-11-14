package app.shiori.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Yomitan's `formatGlossary` for Anki, reproduced without a DOM: StructuredContentGenerator builds an element
 * tree, the CssStyleApplier turns `gloss-*` classes into inline styles, data attributes other than `data-sc-*`
 * are dropped and newlines in text become `<br>` (ext/js/display/structured-content-generator.js,
 * ext/js/templates/anki-template-renderer.js and ext/js/dom/css-style-applier.js, tag 25.2.18).
 * The output is meant to equal Chrome's `innerHTML` of that tree, attribute order included.
 */

// ext/data/structured-content-style.json rules that can match a generated element, in file order.
private val CLASS_RULES: List<Pair<List<String>, String>> = listOf(
    listOf("gloss-link-external-icon") to "display:none;",
    listOf("gloss-sc-table-container") to "display:block;",
    listOf("gloss-sc-table") to "table-layout:auto;border-collapse:collapse;",
    listOf("gloss-sc-thead", "gloss-sc-tfoot", "gloss-sc-th") to "font-weight:bold;",
    listOf("gloss-sc-th", "gloss-sc-td") to "border-style:solid;padding:0.25em;vertical-align:top;border-width:1px;border-color:currentColor;",
)

private val VOID_TAGS = setOf("br", "img")

private class El(val tag: String, val className: String) {
    /** Attributes in the order the DOM would hold them (style is added last, as Chrome synchronizes it late). */
    val attrs = LinkedHashMap<String, String>()
    val style = LinkedHashMap<String, String>()
    val children = ArrayList<Any>() // El or String
}

private fun classRules(className: String): String {
    val classes = className.split(Regex("[\\t\\r\\n\\u000c ]+")).filter { it.isNotEmpty() }
    val sb = StringBuilder()
    for ((selectors, css) in CLASS_RULES) if (selectors.any { it in classes }) sb.append(css)
    return sb.toString()
}

private fun cssText(style: Map<String, String>) = style.entries.joinToString(" ") { (k, v) -> "$k: $v;" }

/** Renders one structured-content glossary item (the `content` of a `structured-content` object). */
internal fun renderStructuredContent(content: JsonElement?): String {
    val root = El("span", "structured-content")
    append(root, content, null)
    val sb = StringBuilder()
    serialize(root, sb)
    return sb.toString()
}

private fun append(container: El, content: JsonElement?, language: String?) {
    when (content) {
        null, JsonNull -> Unit
        is JsonPrimitive -> if (content.isString && content.content.isNotEmpty()) {
            container.children += content.content
            if (language == null && isStringPartiallyJapanese(content.content)) container.attrs["lang"] = "ja"
        }
        is JsonArray -> content.forEach { append(container, it, language) }
        is JsonObject -> element(content, language)?.let { container.children += it }
    }
}

private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun element(c: JsonObject, language: String?): El? {
    return when (val tag = str(c, "tag")) {
        "br" -> generic(tag, c, language, "simple", hasChildren = false, hasStyle = false)
        "ruby", "rt", "rp" -> generic(tag, c, language, "simple", hasChildren = true, hasStyle = false)
        "table" -> {
            val container = El("div", "gloss-sc-table-container")
            container.children += generic(tag, c, language, "table", hasChildren = true, hasStyle = false)
            container
        }
        "thead", "tbody", "tfoot", "tr" -> generic(tag, c, language, "table", hasChildren = true, hasStyle = false)
        "th", "td" -> generic(tag, c, language, "table-cell", hasChildren = true, hasStyle = true)
        "div", "span", "ol", "ul", "li", "details", "summary" ->
            generic(tag, c, language, "simple", hasChildren = true, hasStyle = true)
        "a" -> link(c, language)
        // Images need the dictionary's media files, which only Yomitan's media provider can export.
        else -> null
    }
}

private fun generic(tag: String, c: JsonObject, language0: String?, type: String, hasChildren: Boolean, hasStyle: Boolean): El {
    var language = language0
    val node = El(tag, "gloss-sc-$tag")
    (c["data"] as? JsonObject)?.let { setDataset(node, it) }
    str(c, "lang")?.let {
        node.attrs["lang"] = it
        language = it
    }
    if (type == "table-cell") {
        (c["colSpan"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { node.attrs["colspan"] = it.toInt().coerceIn(1, 1000).toString() }
        (c["rowSpan"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { node.attrs["rowspan"] = it.toInt().coerceIn(0, 65534).toString() }
    }
    if (hasStyle) {
        (c["style"] as? JsonObject)?.let { setStyle(node, it) }
        str(c, "title")?.let { node.attrs["title"] = it }
        if ((c["open"] as? JsonPrimitive)?.booleanOrNull == true) node.attrs["open"] = ""
    }
    if (hasChildren) append(node, c["content"], language)
    return node
}

private fun link(c: JsonObject, language0: String?): El {
    var language = language0
    val href0 = str(c, "href") ?: ""
    val internal = href0.startsWith("?")
    val node = El("a", "gloss-link")
    val text = El("span", "gloss-link-text")
    node.children += text
    str(c, "lang")?.let {
        node.attrs["lang"] = it
        language = it
    }
    append(text, c["content"], language)
    if (!internal) node.children += El("span", "gloss-link-external-icon icon")
    node.attrs["href"] = if (internal) "#" else href0
    return node
}

/** `element.dataset[key] = value` with key "sc" + capitalised key; keys the DOM rejects are skipped. */
private fun setDataset(node: El, data: JsonObject) {
    for ((k, v) in data) {
        if (k.isEmpty()) continue
        val key = "sc" + k[0].uppercaseChar() + k.substring(1)
        // A '-' followed by a lowercase ASCII letter is a DOMException (SyntaxError) and is swallowed.
        if (Regex("-[a-z]").containsMatchIn(key)) continue
        val value = (v as? JsonPrimitive)?.contentOrNull ?: continue
        val attr = "data-" + key.map { if (it in 'A'..'Z') "-" + it.lowercaseChar() else it.toString() }.joinToString("")
        node.attrs[attr] = value
    }
}

private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun setStyle(node: El, st: JsonObject) {
    fun put(prop: String, value: String) {
        node.style[prop] = normalizeCssValue(prop, value)
    }
    fun str(key: String, prop: String) = st.s(key)?.let { put(prop, it) }
    fun lengthOrString(key: String, prop: String) {
        val p = st[key] as? JsonPrimitive ?: return
        if (p.isString) put(prop, p.content)
        else p.contentOrNull?.toDoubleOrNull()?.let { put(prop, formatNumber(it) + "em") }
    }
    str("fontStyle", "font-style")
    str("fontWeight", "font-weight")
    str("fontSize", "font-size")
    str("color", "color")
    str("background", "background")
    str("backgroundColor", "background-color")
    str("verticalAlign", "vertical-align")
    str("textAlign", "text-align")
    str("textEmphasis", "text-emphasis")
    str("textShadow", "text-shadow")
    when (val d = st["textDecorationLine"]) {
        is JsonPrimitive -> if (d.isString) put("text-decoration", d.content)
        is JsonArray -> put("text-decoration", d.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull ?: "" })
        else -> Unit
    }
    str("textDecorationStyle", "text-decoration-style")
    str("textDecorationColor", "text-decoration-color")
    str("borderColor", "border-color")
    str("borderStyle", "border-style")
    str("borderRadius", "border-radius")
    str("borderWidth", "border-width")
    str("clipPath", "clip-path")
    str("margin", "margin")
    lengthOrString("marginTop", "margin-top")
    lengthOrString("marginLeft", "margin-left")
    lengthOrString("marginRight", "margin-right")
    lengthOrString("marginBottom", "margin-bottom")
    str("padding", "padding")
    str("paddingTop", "padding-top")
    str("paddingLeft", "padding-left")
    str("paddingRight", "padding-right")
    str("paddingBottom", "padding-bottom")
    str("wordBreak", "word-break")
    str("whiteSpace", "white-space")
    str("cursor", "cursor")
    str("listStyleType", "list-style-type")
}

private fun formatNumber(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

private val ZERO_LENGTH_PROPS = setOf(
    "margin", "margin-top", "margin-left", "margin-right", "margin-bottom",
    "padding", "padding-top", "padding-left", "padding-right", "padding-bottom",
    "border-width", "border-radius", "font-size",
)

/** The few ways Chrome's CSSOM rewrites a value on assignment that Jitendex's styles hit. */
internal fun normalizeCssValue(prop: String, raw: String): String {
    var depth = 0
    val tokens = ArrayList<String>()
    val cur = StringBuilder()
    for (ch in raw.trim()) {
        when {
            ch == '(' -> { depth++; cur.append(ch) }
            ch == ')' -> { depth--; cur.append(ch) }
            ch.isWhitespace() && depth == 0 -> if (cur.isNotEmpty()) { tokens += cur.toString(); cur.setLength(0) }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty()) tokens += cur.toString()
    return tokens.joinToString(" ") { t ->
        when {
            t.startsWith("#") && Regex("#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(t) -> hexToRgb(t)
            t.startsWith("color-mix(") -> hexInColorMix(t)
            t == "0" && prop in ZERO_LENGTH_PROPS -> "0px"
            t.length >= 2 && t.first() == '\'' && t.last() == '\'' -> "\"" + t.substring(1, t.length - 1) + "\""
            else -> t
        }
    }
}

/** Chrome serialises hex colours inside color-mix() as rgb(); fallbacks inside var() are kept verbatim. */
private fun hexInColorMix(t: String): String {
    val out = StringBuilder()
    var varDepth = 0
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (varDepth == 0 && t.startsWith("var(", i)) {
            varDepth = 1
            out.append("var(")
            i += 4
            continue
        }
        if (varDepth > 0) {
            if (c == '(') varDepth++ else if (c == ')') varDepth--
        } else if (c == '#') {
            val m = Regex("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})(?![0-9a-zA-Z])").matchAt(t, i)
            if (m != null) {
                out.append(hexToRgb(m.value))
                i += m.value.length
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun hexToRgb(hex: String): String {
    var h = hex.substring(1)
    if (h.length <= 4) h = h.map { "$it$it" }.joinToString("")
    val r = h.substring(0, 2).toInt(16)
    val g = h.substring(2, 4).toInt(16)
    val b = h.substring(4, 6).toInt(16)
    if (h.length == 6) return "rgb($r, $g, $b)"
    val a = h.substring(6, 8).toInt(16)
    val alpha = Math.round(a / 255.0 * 100) / 100.0
    return "rgba($r, $g, $b, ${formatNumber(alpha)})"
}

private fun escapeText(s: String, sb: StringBuilder) {
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        ' ' -> sb.append("&nbsp;")
        else -> sb.append(c)
    }
}

private fun escapeAttr(s: String, sb: StringBuilder) {
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '"' -> sb.append("&quot;")
        ' ' -> sb.append("&nbsp;")
        else -> sb.append(c)
    }
}

private fun serialize(el: El, sb: StringBuilder) {
    val styleText = classRules(el.className) + cssText(el.style)
    sb.append('<').append(el.tag)
    for ((k, v) in el.attrs) {
        sb.append(' ').append(k).append("=\"")
        escapeAttr(v, sb)
        sb.append('"')
    }
    if (styleText.isNotEmpty()) {
        sb.append(" style=\"")
        escapeAttr(styleText, sb)
        sb.append('"')
    }
    sb.append('>')
    if (el.tag in VOID_TAGS) return
    for (child in el.children) {
        when (child) {
            is El -> serialize(child, sb)
            is String -> {
                val parts = child.split('\n')
                for ((i, p) in parts.withIndex()) {
                    if (i > 0) sb.append("<br>")
                    escapeText(p, sb)
                }
            }
        }
    }
    sb.append("</").append(el.tag).append('>')
}

/** One glossary array item as Yomitan's `formatGlossary` renders it: text/structured content/image. */
internal fun formatGlossaryItem(item: JsonElement): String {
    if (item is JsonPrimitive && item.isString) return item.content.split('\n').joinToString("<br>")
    val obj = item as? JsonObject ?: return ""
    return when (obj.s("type")) {
        "structured-content" -> renderStructuredContent(obj["content"])
        "text" -> (obj.s("text") ?: "").split('\n').joinToString("<br>")
        else -> "" // "image" glossary items need media export
    }
}

/** Parses the raw glossary JSON stored with a term row and formats every item, joined as Yomitan's templates do. */
internal fun formatGlossary(glossaryJson: String): String {
    val root = try {
        Json.parseToJsonElement(glossaryJson)
    } catch (e: Exception) {
        return ""
    }
    val items = if (root is JsonArray) root else JsonArray(listOf(root))
    return items.joinToString(" | ") { formatGlossaryItem(it) }
}
