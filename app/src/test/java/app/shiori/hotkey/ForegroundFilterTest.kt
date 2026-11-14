package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFilterTest {
    private var calls = 0
    private val filter = ForegroundFilter("app.shiori") { _, cls -> calls++; cls.endsWith("Activity") }

    @Test fun `accepts a real app activity`() =
        assertTrue(filter.accept("com.retroarch", "com.retroarch.browser.RetroActivity"))

    @Test fun `ignores our package and system ui`() {
        assertFalse(filter.accept("app.shiori", "app.shiori.MainActivity"))
        assertFalse(filter.accept("com.android.systemui", "com.android.systemui.SomeActivity"))
    }

    @Test fun `ignores non activity classes and missing names`() {
        assertFalse(filter.accept("com.game", "android.app.AlertDialog"))
        assertFalse(filter.accept("com.game", null))
        assertFalse(filter.accept(null, "X.Activity"))
    }

    @Test fun `caches the activity check`() {
        filter.accept("com.a", "com.a.MainActivity")
        filter.accept("com.a", "com.a.MainActivity")
        assertEquals(1, calls)
    }
}
