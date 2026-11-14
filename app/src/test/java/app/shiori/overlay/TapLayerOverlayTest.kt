package app.shiori.overlay

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.shiori.ocr.Box
import app.shiori.ocr.OcrChar
import app.shiori.ocr.OcrLine
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A service that is unbound or a view that is already gone must never crash the overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapLayerOverlayTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var failAdd: Exception? = null
    private var failRemove: Exception? = null
    private var failUpdate: Exception? = null
    private var added = 0
    private val logged = mutableListOf<String>()

    private val wm: WindowManager = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, m, args ->
        val a = args ?: emptyArray()
        when (m.name) {
            "addView" -> { failAdd?.let { throw it }; added++; null }
            "removeView" -> { failRemove?.let { throw it }; null }
            "updateViewLayout" -> { failUpdate?.let { throw it }; null }
            else -> try { m.invoke(real, *a) } catch (e: InvocationTargetException) { throw e.targetException }
        }
    } as WindowManager

    private fun overlay() = TapLayerOverlay(ctx, wm) { m, _ -> logged += m }

    private fun line(left: Float): OcrLine {
        val chars = "はい".mapIndexed { i, c -> OcrChar(c.toString(), Box(left + 10f * i, 0f, left + 10f * (i + 1), 10f)) }
        return OcrLine("はい", Box(left, 0f, left + 20f, 10f), chars, false)
    }

    @Test fun `update survives a bad token on add`() {
        failAdd = WindowManager.BadTokenException("unbound")
        overlay().update(100, 100, listOf(line(0f)), {}) {}
        assertTrue(logged.isNotEmpty())
        assertEquals(0, added)
    }

    @Test fun `update survives IllegalStateException on add and recovers when windows work again`() {
        val o = overlay()
        failAdd = IllegalStateException("service gone")
        o.update(100, 100, listOf(line(0f)), {}) {}
        failAdd = null
        o.update(100, 100, listOf(line(0f)), {}) {}
        assertEquals(2, added) // highlight + one line window
    }

    @Test fun `showPopup survives a failed add`() {
        val o = overlay()
        o.update(100, 100, listOf(line(0f)), {}) {}
        failAdd = WindowManager.BadTokenException("unbound")
        o.showPopup(View(ctx), RectF(0f, 0f, 10f, 10f)) {}
        o.closePopup()
    }

    @Test fun `removing views that are already gone does not throw`() {
        val o = overlay()
        o.update(100, 100, listOf(line(0f), line(30f)), {}) {}
        failRemove = IllegalArgumentException("not attached")
        o.dismiss()
        o.closePopup()
        o.dismiss()
        assertTrue(logged.count { it == "removeView failed" } >= 3)
    }

    @Test fun `layout update on a vanished window does not throw`() {
        val o = overlay()
        o.update(100, 100, listOf(line(0f)), {}) {}
        failUpdate = IllegalArgumentException("gone")
        o.update(100, 100, listOf(line(40f)), {}) {}
        assertTrue(logged.contains("updateViewLayout failed"))
    }
}
