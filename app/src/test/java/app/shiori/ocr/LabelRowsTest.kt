package app.shiori.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LabelRowsTest {
    @Test
    fun `int32 and int64 label outputs both read as longs`() {
        assertEquals(listOf(listOf(12354L, 3L)), labelRows(arrayOf(intArrayOf(12354, 3))).map { it.toList() })
        assertEquals(listOf(listOf(12354L, 3L)), labelRows(arrayOf(longArrayOf(12354, 3))).map { it.toList() })
    }

    @Test
    fun `unknown label types fail loudly`() {
        assertThrows(IllegalStateException::class.java) { labelRows(arrayOf(floatArrayOf(1f))) }
    }
}
