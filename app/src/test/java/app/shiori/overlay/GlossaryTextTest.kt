package app.shiori.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryTextTest {
    @Test fun plainStringsAreLines() {
        assertEquals(listOf("to end", "to finish"), glossaryPlainLines("""["to end","to finish"]"""))
    }

    @Test fun listItemsBecomeLines() {
        val json = """[{"type":"structured-content","content":{"tag":"ul","content":[
            {"tag":"li","content":"one"},{"tag":"li","content":"two"}]}}]"""
        assertEquals(listOf("one", "two"), glossaryPlainLines(json))
    }

    @Test fun nestedSpansConcatenate() {
        val json = """[{"type":"structured-content","content":{"tag":"div","content":
            ["a",{"tag":"span","content":["b",{"tag":"span","content":"c"}]},"d"]}}]"""
        assertEquals(listOf("abcd"), glossaryPlainLines(json))
    }

    @Test fun imagesAndDataAttributesIgnored() {
        val json = """[{"type":"image","path":"x.png"},{"type":"structured-content","content":
            {"tag":"div","data":{"foo":"bar"},"content":[{"tag":"img","path":"a.png"},"text"]}}]"""
        assertEquals(listOf("text"), glossaryPlainLines(json))
    }

    @Test fun jitendexOwaruReadsAsDefinitions() {
        val json = """[{"type":"structured-content","content":[{"tag":"ul","lang":"ja","content":[
          {"tag":"li","content":[
            {"tag":"span","title":"Godan verb","data":{"code":"v5r"},"content":"5-dan"},
            {"tag":"span","title":"intransitive verb","data":{"code":"vi"},"content":"intransitive"},
            {"tag":"ol","content":{"tag":"li","data":{"sense-number":"1"},"content":[
              {"tag":"ul","data":{"content":"glossary"},"content":[
                {"tag":"li","content":"to end"},{"tag":"li","content":"to come to an end"}]},
              {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","data":{"content":"example-sentence"},"content":[
                {"tag":"div","content":[{"tag":"ruby","content":["終",{"tag":"rt","content":"お"}]},"わった"]},
                {"tag":"div","content":["The game ended."]}]}}]}}]}]}]}]"""
        assertEquals(listOf("5-dan intransitive", "to end", "to come to an end"), glossaryPlainLines(json))
    }

    @Test fun badJsonGivesNothing() {
        assertEquals(emptyList<String>(), glossaryPlainLines("not json"))
    }
}

class GlossarySensesTest {
    private val taggu = """[{"type":"structured-content","content":[{"tag":"ul","lang":"ja","content":[{"tag":"li","content":[
      {"tag":"span","data":{"code":"n"},"content":"noun"},
      {"tag":"ol","content":[
        {"tag":"li","data":{"sense-number":"1"},"content":[{"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"tag"},{"tag":"li","content":"label"}]},
          {"tag":"div","data":{"content":"extra-info"},"content":"example"}]},
        {"tag":"li","data":{"sense-number":"2"},"content":{"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"game of tag"}}},
        {"tag":"li","data":{"sense-number":"3"},"content":[{"tag":"span","data":{"code":"abbr"},"content":"abbr."},{"tag":"span","data":{"code":"prowres"},"content":"wrestling"},
          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"tag-team match"}}]}]}
      ]},{"tag":"li","data":{"content":"forms"},"content":"forms"}]}]}]"""

    @Test fun sensesSplitPosFromMiscAndGloss() {
        val g = glossarySenses(taggu)
        assertEquals(listOf("noun"), g.partsOfSpeech)
        assertEquals(
            listOf(
                GlossSense(emptyList(), listOf("tag", "label")),
                GlossSense(emptyList(), listOf("game of tag")),
                GlossSense(listOf("abbr.", "wrestling"), listOf("tag-team match")),
            ),
            g.senses,
        )
    }

    @Test fun singleSenseWithoutNumber() {
        val json = """[{"type":"structured-content","content":{"tag":"div","content":[
          {"tag":"span","data":{"code":"unc"},"content":"unclass"},
          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"repetition mark"}}]}}]"""
        val g = glossarySenses(json)
        assertEquals(listOf("unclass"), g.partsOfSpeech)
        assertEquals(listOf(GlossSense(emptyList(), listOf("repetition mark"))), g.senses)
    }

    @Test fun plainStringsFallBackToOneSenseEach() {
        val g = glossarySenses("""["to end","to finish"]""")
        assertEquals(emptyList<String>(), g.partsOfSpeech)
        assertEquals(listOf(GlossSense(emptyList(), listOf("to end")), GlossSense(emptyList(), listOf("to finish"))), g.senses)
    }

    @Test fun badJson() {
        assertEquals(GlossaryView(emptyList(), emptyList()), glossarySenses("not json"))
    }
}
