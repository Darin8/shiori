package app.shiori.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaTest {
    @Test fun verbOkuriganaStaysPlain() {
        assertEquals(listOf("終" to "お", "わる" to null), furiganaSegments("終わる", "おわる"))
    }

    @Test fun phraseWithTwoKanjiGroups() {
        assertEquals(
            listOf("逆" to "ぎゃく", "に" to null, "言" to "い", "えば" to null),
            furiganaSegments("逆に言えば", "ぎゃくにいえば"),
        )
    }

    @Test fun jukugoGetsOneReading() {
        assertEquals(listOf("助" to "たす", "かる" to null), furiganaSegments("助かる", "たすかる"))
        assertEquals(listOf("日本語" to "にほんご"), furiganaSegments("日本語", "にほんご"))
    }

    @Test fun kanaOnlyHasNoFurigana() {
        assertEquals(listOf("タッグ" to null), furiganaSegments("タッグ", "タッグ"))
        assertEquals(listOf("タッグ" to null), furiganaSegments("タッグ", "たっぐ"))
        assertEquals(listOf("ああ" to null), furiganaSegments("ああ", "ああ"))
    }

    @Test fun missingReadingHasNoFurigana() {
        assertEquals(listOf("終わる" to null), furiganaSegments("終わる", ""))
    }
}
