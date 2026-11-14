package app.shiori.dict

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinflectorTest {
    private val d = Deinflector(File("src/main/assets/japanese-transforms.json").readText())

    /** The expected text appears with rules that fit an entry carrying [entryRules]. */
    private fun assertFits(source: String, text: String, vararg entryRules: String) {
        val hits = d.deinflect(source).filter { it.text == text && d.rulesMatch(it.rules, entryRules.toSet()) }
        assertTrue("$source -> $text (${entryRules.toList()}) not found", hits.isNotEmpty())
    }

    @Test fun sourceIsIncludedWithEmptyRules() {
        val first = d.deinflect("食べる").first()
        assertEquals("食べる", first.text)
        assertTrue(first.rules.isEmpty())
        assertTrue(first.reasons.isEmpty())
    }

    @Test fun ichidanNegativePast() = assertFits("食べなかった", "食べる", "v1")
    @Test fun godanIku() = assertFits("行って", "行く", "v5k-s")
    @Test fun godanPassive() = assertFits("書かれた", "書く", "v5")
    @Test fun godanTe() = assertFits("終わって", "終わる", "v5")
    @Test fun adjINegative() = assertFits("高くない", "高い", "adj-i")
    @Test fun suruProgressive() = assertFits("している", "する", "vs")
    @Test fun kuruPotential() = assertFits("来られる", "来る", "vk")
    @Test fun causativePassive() = assertFits("読ませられた", "読む", "v5")
    @Test fun tai() = assertFits("食べたい", "食べる", "v1")

    // Yomitan has no な-adjective transform: 静かな reaches 静か by shortening the scanned text, not here.
    @Test fun naAdjectiveHasNoTransformButSourceFitsAnyEntry() {
        assertTrue(d.deinflect("静かな").none { it.text == "静か" })
        assertTrue(d.rulesMatch(d.deinflect("静かな").first().rules, setOf("adj-na")))
    }

    @Test fun reasonsAreRecorded() {
        val r = d.deinflect("食べなかった").first { it.text == "食べる" }
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test fun v5kSEntryFitsV5() = assertTrue(d.rulesMatch(setOf("v5"), setOf("v5k-s")))
    @Test fun v1EntryDoesNotFitV5() = assertFalse(d.rulesMatch(setOf("v5"), setOf("v1")))
    @Test fun emptyDeinflectionRulesFitAnyEntry() {
        assertTrue(d.rulesMatch(emptySet(), setOf("v1")))
        assertTrue(d.rulesMatch(emptySet(), emptySet()))
    }
    @Test fun nonEmptyRulesNeverFitEntryWithoutRules() = assertFalse(d.rulesMatch(setOf("v1"), emptySet()))
    @Test fun verbConditionCoversAllVerbKinds() {
        assertTrue(d.rulesMatch(setOf("v"), setOf("vs")))
        assertTrue(d.rulesMatch(setOf("v"), setOf("v5r")))
        assertFalse(d.rulesMatch(setOf("v"), setOf("adj-i")))
    }
    @Test fun intermediateFormsFitNoEntry() = assertFalse(d.rulesMatch(setOf("-て"), setOf("v1")))

    @Test fun terminates() {
        assertTrue(d.deinflect("させられさせられさせられさせられた").isNotEmpty())
    }
}
