package app.shiori.session

/**
 * Cost log for OCR passes (prefix "cost"): one line per pass, plus a summary every [summaryEveryMs] with CPU time
 * against wall time, skip counts and battery. Probes are injected so tests can fake them.
 */
class CostMeter(
    private val log: (String) -> Unit,
    private val cpuMs: () -> Long,
    private val wallMs: () -> Long,
    private val battery: () -> String,
    private val summaryEveryMs: Long = 30_000,
) {
    private var windowWall = -1L
    private var windowCpu = 0L
    private var passes = 0
    private var totalSum = 0L
    private var reusedSum = 0
    private var recognizedSum = 0
    private val skips = LinkedHashMap<String, Int>()

    @Synchronized
    fun onPass(captureMs: Long, detectMs: Long, recogMs: Long, reused: Int, recognized: Int, totalMs: Long) {
        log(
            "cost pass capture=${captureMs}ms detect=${detectMs}ms recog=${recogMs}ms " +
                "reused=$reused recognized=$recognized total=${totalMs}ms"
        )
        open()
        passes++
        totalSum += totalMs
        reusedSum += reused
        recognizedSum += recognized
        roll()
    }

    /** A tick that did no pass ([kind] such as "away", "popup", "busy"). Counted for the summary; the caller logs it. */
    @Synchronized
    fun onSkip(kind: String) {
        open()
        skips[kind] = (skips[kind] ?: 0) + 1
        roll()
    }

    /** Starts a window at the first event after a reset. */
    private fun open() {
        if (windowWall < 0) { windowWall = wallMs(); windowCpu = cpuMs() }
    }

    private fun roll() {
        val wall = wallMs()
        val span = wall - windowWall
        if (span < summaryEveryMs) return
        val cpu = cpuMs() - windowCpu
        val pct = if (span > 0) cpu * 100.0 / span else 0.0
        val avg = if (passes > 0) totalSum / passes else 0
        val skipCounts = listOf("unchanged", "away", "popup", "busy", "noregion")
            .joinToString(" ") { "skip$it=${skips[it] ?: 0}" }
        log(
            "cost summary passes=$passes avgTotal=${avg}ms reused=$reusedSum recognized=$recognizedSum $skipCounts " +
                "cpu=${cpu}ms wall=${span}ms cpuOfOneCore=${"%.1f".format(pct)}% ${battery()}"
        )
        windowWall = wall
        windowCpu = cpuMs()
        passes = 0; totalSum = 0; reusedSum = 0; recognizedSum = 0
        skips.clear()
    }

    /** The next pass starts a fresh window (live was off for a while). */
    @Synchronized
    fun reset() {
        windowWall = -1
        passes = 0; totalSum = 0; reusedSum = 0; recognizedSum = 0
        skips.clear()
    }
}
