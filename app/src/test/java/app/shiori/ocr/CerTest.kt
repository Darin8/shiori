package app.shiori.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CerTest {
    @Test fun identicalIsZero() {
        assertEquals(0.0, charErrorRate("こんにちは世界", "こんにちは世界"), 1e-9)
    }

    @Test fun oneSubstitutionInTenIsPointOne() {
        assertEquals(0.1, charErrorRate("あいうえおかきくけこ", "あいうえおかきくけコ"), 1e-9)
    }

    @Test fun emptyActualIsOne() {
        assertEquals(1.0, charErrorRate("あいうえお", ""), 1e-9)
    }

    @Test fun insertionsCountAndCanExceedOne() {
        assertEquals(1.0, charErrorRate("ab", "abcd"), 1e-9)
    }

    @Test fun emptyExpectedIsZeroOnlyWhenActualEmpty() {
        assertEquals(0.0, charErrorRate("", ""), 1e-9)
        assertEquals(1.0, charErrorRate("", "あ"), 1e-9)
    }
}
