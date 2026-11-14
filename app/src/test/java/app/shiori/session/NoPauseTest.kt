package app.shiori.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game is never paused: nothing in the app may send RetroArch's pause command any more. */
class NoPauseTest {
    @Test
    fun `no main source mentions the pause command`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main") }.first { it.isDirectory }
        val needle = "PAUSE" + "_TOGGLE"
        val hits = root.walkTopDown().filter { it.extension == "kt" && needle in it.readText() }.toList()
        assertTrue("found in $hits", hits.isEmpty())
    }
}
