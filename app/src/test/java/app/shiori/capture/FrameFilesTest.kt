package app.shiori.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun frame(name: String, age: Long) = File(tmp.root, name).apply {
        writeText("x"); setLastModified(1_000_000L - age * 1000)
    }

    @Test fun `empty or missing directory has no frames`() {
        assertFalse(FrameFiles.any(tmp.root))
        assertNull(FrameFiles.newest(tmp.root))
        assertFalse(FrameFiles.any(File(tmp.root, "nope")))
        assertEquals(0, FrameFiles.prune(File(tmp.root, "nope")))
    }

    @Test fun `newest is the most recently modified png and other files are ignored`() {
        frame("a.png", 5); val b = frame("b.png", 1); frame("c.png", 9); frame("note.txt", 0)
        assertTrue(FrameFiles.any(tmp.root))
        assertEquals(b, FrameFiles.newest(tmp.root))
    }

    @Test fun `prune keeps only the newest N pngs`() {
        (1..55).forEach { frame("f$it.png", it.toLong()) }
        val txt = frame("keep.txt", 100)
        assertEquals(5, FrameFiles.prune(tmp.root))
        val left = tmp.root.listFiles { f -> f.extension == "png" }!!.map { it.name }.toSet()
        assertEquals(50, left.size)
        assertTrue("f1.png" in left && "f50.png" in left)
        assertFalse("f51.png" in left || "f55.png" in left)
        assertTrue(txt.exists())
        assertEquals(0, FrameFiles.prune(tmp.root))
    }
}

class FrameAvailableTest {
    @Test fun `a frame in memory enables the region button even with nothing saved (live only)`() {
        assertTrue(frameAvailable(inMemory = true, onDisk = false))
        assertTrue(frameAvailable(inMemory = true, onDisk = null))
    }

    @Test fun `a saved frame enables it and nothing disables it`() {
        assertTrue(frameAvailable(inMemory = false, onDisk = true))
        assertFalse(frameAvailable(inMemory = false, onDisk = false))
        assertFalse(frameAvailable(inMemory = false, onDisk = null))
    }
}
