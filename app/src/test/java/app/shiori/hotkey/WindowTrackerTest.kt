package app.shiori.hotkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WindowTrackerTest {
    private fun app(pkg: String) = WinSnap(WinKind.APP, pkg, active = true, focused = true, covers = true)

    @Test fun `update publishes the windows and the focused game`() {
        val t = WindowTracker("app.shiori") { setOf("home") }
        assertNull(t.latest.focused)
        val snap = t.update(listOf(app("com.game")))
        assertEquals("com.game", t.latest.focused)
        assertSame(snap, t.latest)
    }

    @Test fun `home, our app and system ui are never the game`() {
        val t = WindowTracker("app.shiori") { setOf("home") }
        t.update(listOf(app("home"))); assertNull(t.latest.focused)
        t.update(listOf(app("app.shiori"))); assertNull(t.latest.focused)
        t.update(listOf(app("com.game"), WinSnap(WinKind.SYSTEM, "com.android.systemui", true, false, true)))
        assertNull(t.latest.focused)
    }

    @Test fun `clear forgets the game`() {
        val t = WindowTracker("app.shiori") { emptySet() }
        t.update(listOf(app("com.game")))
        t.clear()
        assertNull(t.latest.focused)
    }

    @Test fun `another thread reads a complete snapshot`() {
        val t = WindowTracker("app.shiori") { emptySet() }
        t.update(listOf(app("com.game")))
        val seen = AtomicReference<FocusSnapshot>()
        val done = CountDownLatch(1)
        thread { seen.set(t.latest); done.countDown() }
        done.await()
        assertEquals("com.game", seen.get().focused)
        assertEquals(1, seen.get().windows.size)
    }
}

class HomePackageCacheTest {
    @Test fun `computes once within the ttl and again after it`() {
        var calls = 0
        var clock = 0L
        val c = HomePackageCache({ calls++; setOf("home$calls") }, { clock }, ttlMs = 1000)
        assertEquals(setOf("home1"), c.get())
        clock = 999
        assertEquals(setOf("home1"), c.get())
        assertEquals(1, calls)
        clock = 1000
        assertEquals(setOf("home2"), c.get())
    }

    @Test fun `invalidate forces a refresh`() {
        var calls = 0
        val c = HomePackageCache({ calls++; emptySet() }, { 0L })
        c.get(); c.get()
        assertEquals(1, calls)
        c.invalidate(); c.get()
        assertEquals(2, calls)
    }
}
