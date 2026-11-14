package app.shiori.dict

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequenciesTest {
    @Test fun `one entry per dictionary with values joined in order`() {
        val grouped = groupFrequencies(
            listOf(
                Frequency("JPDBv2㋕", 11058, "11058"),
                Frequency("Other", 5, "5"),
                Frequency("JPDBv2㋕", 264028, "264028㋕"),
            )
        )
        assertEquals(listOf("JPDBv2㋕" to "11058, 264028㋕", "Other" to "5"), grouped)
    }

    @Test fun `empty input gives empty output`() = assertEquals(emptyList<Pair<String, String>>(), groupFrequencies(emptyList()))
}
