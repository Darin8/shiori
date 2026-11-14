package app.shiori

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.shiori.dict.DictInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lays the setup screen out at the Nova's 1280x960 (xhdpi) and writes PNGs for eyeballing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-xhdpi")
class SetupSnapshotTest {
    private val infos = listOf(
        DictInfo("JPDBv2㋕", "Frequency, 2024-10-13", true, 0),
        DictInfo("Jitendex.org [2025-02-11]", "Terms, 2025.02.11.0", true, 1),
        DictInfo("JMdict [2026-11-11]", "Terms, 2026-11-11", false, 2),
    )

    private fun render(name: String, mode: String, region: Boolean, arm: String? = null) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val frames = File(ctx.getExternalFilesDir(null), "frames").apply { mkdirs() }
        File(frames, "a.png").writeBytes(ByteArray(8))
        ctx.getSharedPreferences("retromine", Context.MODE_PRIVATE).edit()
            .putString("ocrMode", mode)
            .putString("last_frame_package", "g.game")
            .apply { if (region) putString("region:g.game", "0.1,0.1,0.9,0.5") else remove("region:g.game") }
            .commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        val panel = MainActivity::class.java.getDeclaredField("panel").apply { isAccessible = true }.get(a) as DictionaryPanel
        panel.show(infos)
        if (arm != null) panel.armDelete(arm)
        val v = a.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 960)
        val bmp = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("popup.out") ?: "build/popup")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(dir, "$name.png").length() > 1000)
    }

    @Test fun hotkeyMode() = render("setup-v3-hotkey", "hotkey", false)
    @Test fun liveNoRegion() = render("setup-v3-live-noregion", "live", false)
    @Test fun liveWithRegion() = render("setup-v3-live-region", "live", true)
    @Test fun deleteConfirm() = render("setup-v3-delete-confirm", "hotkey", false, arm = "JMdict [2026-11-11]")

    @Test fun metaIsShortAndWholeWords() {
        assertEquals("Terms 2025-02-11", dictMeta("Terms, 2025.02.11.0"))
        assertEquals("Frequency 2024-10-13", dictMeta("Frequency, 2024-10-13"))
        assertEquals("", dictMeta(null))
        assertTrue(dictMeta("Some Very Long Revision Name 1.2").length <= 24)
    }
}
