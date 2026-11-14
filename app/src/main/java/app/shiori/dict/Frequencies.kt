package app.shiori.dict

/**
 * One (dictionary, joined values) pair per frequency dictionary, like Yomitan's frequency tags: values are joined
 * with ", " in the dictionary's own order, dictionaries keep the order of their first appearance.
 */
fun groupFrequencies(frequencies: List<Frequency>): List<Pair<String, String>> {
    val order = LinkedHashMap<String, MutableList<String>>()
    for (f in frequencies) order.getOrPut(f.dictionary) { ArrayList() }.add(f.display)
    return order.map { (dictionary, values) -> dictionary to values.joinToString(", ") }
}
