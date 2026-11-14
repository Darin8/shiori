package app.shiori.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MeikiOCR (detection and recognition ONNX models, LGPL-3.0), ported from the Python reference
 * https://github.com/rtr46/meikiocr (meikiocr/ocr.py at commit 52aa607ecdc1038c7bf390f37cd9a8b306e8a08a).
 * Like the reference, it feeds BGR pixels scaled to 0..1 with no mean/std. Runs on CPU only, 4 threads.
 */
class MeikiOcrEngine(private val context: Context) : OcrEngine {
    private class Sessions(val det: OrtSession, val rec: OrtSession, val vrec: OrtSession)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions: Sessions by lazy {
        fun opts() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
        fun load(name: String) =
            env.createSession(context.assets.open("meiki/$name").use { it.readBytes() }, opts())
        Sessions(load(DET_MODEL), load(REC_MODEL), load(VREC_MODEL))
    }

    private class Det(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private class Meta(val line: Int, val x1: Int, val y1: Int, val x2: Int, val y2: Int, val effW: Int, val effH: Int)
    private class Cand(val cp: Int, val box: IntArray, val conf: Float, val lo: Int, val hi: Int)

    private val cache = LineCache()
    @Volatile private var stats: PassStats? = null

    override val pending: Boolean get() = cache.hasPending
    override val lastStats: PassStats? get() = stats
    override fun reset() = cache.reset()

    /** Uncached: detects and recognises everything. */
    override suspend fun read(frame: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        val img = software(frame)
        recognizeLines(img, detectLines(img)).filterNotNull()
    }

    /** Change-aware pass: detection always runs, recognition only for boxes [LineCache] has not seen unchanged. */
    override suspend fun read(frame: Bitmap, ctx: ReadContext): List<OcrLine> = withContext(Dispatchers.Default) {
        val img = software(frame)
        val t0 = System.nanoTime()
        val dets = detectLines(img).filter { d ->
            ctx.regions.isEmpty() || ctx.regions.any { it.contains(d.box.centerX, d.box.centerY) }
        }
        val t1 = System.nanoTime()
        val luma = bitmapLuma(img)
        var recogNs = 0L
        val out = cache.process(dets, { LineSig.of(luma, it.box) }, settle = ctx.live) { boxes ->
            val s = System.nanoTime()
            val r = recognizeLines(img, boxes)
            recogNs = System.nanoTime() - s
            r
        }
        stats = PassStats((t1 - t0) / 1_000_000, recogNs / 1_000_000, out.reused, out.recognized)
        out.lines
    }

    private fun software(frame: Bitmap): Bitmap =
        if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame

    /** Detection only: text line boxes sorted top to bottom. */
    internal fun detectLines(img: Bitmap): List<LineDet> =
        detect(img, DET_THRESHOLD).mapNotNull { d ->
            val w = d.x2 - d.x1
            val h = d.y2 - d.y1
            if (w <= 0 || h <= 0) null
            else LineDet(Box(d.x1.toFloat(), d.y1.toFloat(), d.x2.toFloat(), d.y2.toFloat()), h > w)
        }

    /** Recognition only: one result per box, null when nothing was read. Boxes are batched per orientation. */
    internal fun recognizeLines(img: Bitmap, boxes: List<LineDet>): List<OcrLine?> {
        if (boxes.isEmpty()) return emptyList()
        val dets = boxes.map {
            Det(it.box.left.toInt(), it.box.top.toInt(), it.box.right.toInt(), it.box.bottom.toInt())
        }
        val hIdx = boxes.indices.filter { !boxes[it].vertical }
        val vIdx = boxes.indices.filter { boxes[it].vertical }
        val chars = arrayOfNulls<List<Cand>>(dets.size)
        recognise(img, dets, hIdx, false, chars)
        recognise(img, dets, vIdx, true, chars)
        return dets.indices.map { i ->
            val cs = chars[i]
            if (cs.isNullOrEmpty()) return@map null
            val d = dets[i]
            val ocrChars = cs.map {
                OcrChar(
                    String(Character.toChars(it.cp)),
                    Box(it.box[0].toFloat(), it.box[1].toFloat(), it.box[2].toFloat(), it.box[3].toFloat()),
                )
            }
            OcrLine(
                ocrChars.joinToString("") { it.text },
                Box(d.x1.toFloat(), d.y1.toFloat(), d.x2.toFloat(), d.y2.toFloat()),
                ocrChars,
                boxes[i].vertical,
            )
        }
    }

    fun close() {
        sessions.det.close(); sessions.rec.close(); sessions.vrec.close()
    }

    // ---- detection (reference: _preprocess_for_detection, _run_detection_inference,
    // _postprocess_detection_results)

    private fun detect(img: Bitmap, confThreshold: Float): List<Det> {
        val wo = img.width
        val ho = img.height
        val scale = min(DET_W.toDouble() / wo, DET_H.toDouble() / ho)
        val wr = max(1, (wo * scale).toInt())
        val hr = max(1, (ho * scale).toInt())
        val resized = Bitmap.createScaledBitmap(img, wr, hr, true)
        val data = FloatArray(3 * DET_H * DET_W)
        fillBgr(resized, data, DET_W, DET_H)
        if (resized !== img) resized.recycle()
        val s = sessions.det
        val names = s.inputNames.toList()
        val dims = longArrayOf(1, 3, DET_H.toLong(), DET_W.toLong())
        val target = longArrayOf((DET_W / scale).toLong(), (DET_H / scale).toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), dims).use { t ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(target), longArrayOf(1, 2)).use { sz ->
                s.run(mapOf(names[0] to t, names[1] to sz)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val boxes = (res[1].value as Array<Array<FloatArray>>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val scores = (res[2].value as Array<FloatArray>)[0]
                    val out = ArrayList<Det>()
                    for (i in boxes.indices) {
                        if (scores[i] <= confThreshold) continue
                        val b = boxes[i]
                        out += Det(
                            b[0].coerceIn(0f, wo.toFloat()).toInt(), b[1].coerceIn(0f, ho.toFloat()).toInt(),
                            b[2].coerceIn(0f, wo.toFloat()).toInt(), b[3].coerceIn(0f, ho.toFloat()).toInt(),
                        )
                    }
                    out.sortBy { it.y1 }
                    return out
                }
            }
        }
    }

    /** Writes [bmp] into a planar BGR float tensor (CHW, 0..1), top-left aligned, zero padded to tw x th. */
    private fun fillBgr(bmp: Bitmap, dst: FloatArray, tw: Int, th: Int) {
        val w = min(bmp.width, tw)
        val h = min(bmp.height, th)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val plane = tw * th
        for (y in 0 until h) {
            val row = y * bmp.width
            for (x in 0 until w) {
                val c = px[row + x]
                val o = y * tw + x
                dst[o] = (c and 0xFF) / 255f
                dst[o + plane] = ((c shr 8) and 0xFF) / 255f
                dst[o + 2 * plane] = ((c shr 16) and 0xFF) / 255f
            }
        }
    }

    // ---- recognition (reference: _process_recognition_pipeline, _preprocess_for_recognition,
    // _postprocess_recognition_results)

    /** Recognises the boxes at [indices] in one orientation and stores the accepted characters in [results]. */
    private fun recognise(
        img: Bitmap, dets: List<Det>, indices: List<Int>, vertical: Boolean, results: Array<List<Cand>?>,
    ) {
        if (indices.isEmpty()) return
        val tw = if (vertical) VREC_W else REC_W
        val th = if (vertical) VREC_H else REC_H
        val tensors = ArrayList<FloatArray>()
        val metas = ArrayList<Meta>()
        for (i in indices) {
            val d = dets[i]
            if (d.x2 - d.x1 <= 0 || d.y2 - d.y1 <= 0) continue
            if (vertical) addVerticalCrops(img, i, d, tw, th, tensors, metas)
            else addHorizontalCrop(img, i, d, tw, th, tensors, metas)
        }
        if (tensors.isEmpty()) return

        val session = if (vertical) sessions.vrec else sessions.rec
        val cands = runRecognition(session, tensors, metas, tw, th, vertical)
        val overlapLimit = if (vertical) Y_OVERLAP else X_OVERLAP
        for ((line, list) in cands) results[line] = nms(list, overlapLimit)
    }

    /** One crop scaled to the model height (and shrunk to fit the model width when too long). */
    private fun addHorizontalCrop(
        img: Bitmap, line: Int, d: Det, tw: Int, th: Int, tensors: MutableList<FloatArray>, metas: MutableList<Meta>,
    ) {
        val w = d.x2 - d.x1
        val h = d.y2 - d.y1
        var newH = REC_H
        val scale = newH.toDouble() / h
        var newW = (w * scale).roundToInt()
        if (newW > REC_W) {
            val shrink = REC_W.toDouble() / newW
            newW = REC_W
            newH = (newH * shrink).roundToInt()
        }
        newW = max(1, newW)
        newH = max(1, newH)
        val t = FloatArray(3 * th * tw)
        cropResizeInto(img, d.x1, d.y1, d.x2, d.y2, newW, newH, t, tw, th)
        tensors += t
        metas += Meta(line, d.x1, d.y1, d.x2, d.y2, newW, newH)
    }

    /** A tall line is cut into overlapping segments, each scaled to the model width. */
    private fun addVerticalCrops(
        img: Bitmap, line: Int, d: Det, tw: Int, th: Int, tensors: MutableList<FloatArray>, metas: MutableList<Meta>,
    ) {
        val w = d.x2 - d.x1
        val h = d.y2 - d.y1
        val scale = VREC_W.toDouble() / w
        val maxHScaled: Int
        val segH: Double
        val starts = ArrayList<Double>()
        if (h * scale > VREC_H) {
            maxHScaled = VREC_MAX_CONTENT_H
            segH = VREC_MAX_CONTENT_H / scale
            val stride = (VREC_MAX_CONTENT_H - VREC_OVERLAP_PX) / scale
            var cur = d.y1.toDouble()
            while (cur + segH < d.y2) {
                starts += cur
                cur += stride
            }
            val last = d.y2 - segH
            if (starts.isEmpty() || last > starts.last() + 1.0) starts += last
        } else {
            maxHScaled = VREC_H
            segH = h.toDouble()
            starts += d.y1.toDouble()
        }
        for (start in starts) {
            val sy1 = start.roundToInt()
            val sy2 = min((start + segH).roundToInt(), d.y2)
            val segPx = sy2 - sy1
            if (segPx <= 0) continue
            val newH = max(1, min((segPx * scale).roundToInt(), maxHScaled))
            val t = FloatArray(3 * th * tw)
            cropResizeInto(img, d.x1, sy1, d.x2, sy2, VREC_W, newH, t, tw, th)
            tensors += t
            metas += Meta(line, d.x1, sy1, d.x2, sy2, VREC_W, newH)
        }
    }

    /** Runs the crops through [session] in batches and groups the candidate characters by source line. */
    private fun runRecognition(
        session: OrtSession, tensors: List<FloatArray>, metas: List<Meta>, tw: Int, th: Int, vertical: Boolean,
    ): Map<Int, List<Cand>> {
        val sizes = longArrayOf(tw.toLong(), th.toLong())
        val cands = LinkedHashMap<Int, ArrayList<Cand>>()
        val perImage = 3 * th * tw
        for (start in 0 until tensors.size step MAX_BATCH) {
            val n = min(MAX_BATCH, tensors.size - start)
            val buf = FloatArray(n * perImage)
            for (k in 0 until n) tensors[start + k].copyInto(buf, k * perImage)
            val dims = longArrayOf(n.toLong(), 3, th.toLong(), tw.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), dims).use { t ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(1, 2)).use { sz ->
                    session.run(mapOf("images" to t, "orig_target_sizes" to sz)).use { res ->
                        val labels = labelRows(res[0].value)
                        @Suppress("UNCHECKED_CAST")
                        val boxes = res[1].value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val scores = res[2].value as Array<FloatArray>
                        for (k in 0 until n) {
                            val meta = metas[start + k]
                            val list = cands.getOrPut(meta.line) { ArrayList() }
                            collect(labels[k], boxes[k], scores[k], meta, vertical, list)
                        }
                    }
                }
            }
        }
        return cands
    }

    private fun cropResizeInto(
        img: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int, newW: Int, newH: Int,
        dst: FloatArray, tw: Int, th: Int,
    ) {
        val crop = Bitmap.createBitmap(img, x1, y1, x2 - x1, y2 - y1)
        val resized = Bitmap.createScaledBitmap(crop, newW, newH, true)
        fillBgr(resized, dst, tw, th)
        if (resized !== crop) resized.recycle()
        if (crop !== img) crop.recycle()
    }

    private fun collect(
        labels: LongArray, boxes: Array<FloatArray>, scores: FloatArray,
        m: Meta, vertical: Boolean, out: MutableList<Cand>,
    ) {
        val cropW = m.x2 - m.x1
        val cropH = m.y2 - m.y1
        for (j in labels.indices) {
            if (scores[j] < REC_THRESHOLD) continue
            val cp = labels[j].toInt()
            var rx1 = boxes[j][0]
            var ry1 = boxes[j][1]
            var rx2 = boxes[j][2]
            var ry2 = boxes[j][3]
            if (!vertical) {
                if (rx1 >= m.effW) continue
                rx1 = min(rx1, m.effW.toFloat())
                rx2 = min(rx2, m.effW.toFloat())
                val cx1 = (rx1 / m.effW) * cropW
                val cx2 = (rx2 / m.effW) * cropW
                val cy1 = (ry1 / REC_H) * cropH
                val cy2 = (ry2 / REC_H) * cropH
                val b = intArrayOf(m.x1 + cx1.toInt(), m.y1 + cy1.toInt(), m.x1 + cx2.toInt(), m.y1 + cy2.toInt())
                out += Cand(cp, b, scores[j], b[0], b[2])
            } else {
                if (ry1 >= m.effH) continue
                ry1 = min(ry1, m.effH.toFloat())
                ry2 = min(ry2, m.effH.toFloat())
                val cx1 = (rx1 / VREC_W) * cropW
                val cx2 = (rx2 / VREC_W) * cropW
                val cy1 = (ry1 / m.effH) * cropH
                val cy2 = (ry2 / m.effH) * cropH
                val b = intArrayOf(m.x1 + cx1.toInt(), m.y1 + cy1.toInt(), m.x1 + cx2.toInt(), m.y1 + cy2.toInt())
                if (b[3] <= b[1]) continue
                out += Cand(cp, b, scores[j], b[1], b[3])
            }
        }
    }

    private fun nms(cands: List<Cand>, overlapThreshold: Double): List<Cand> {
        val accepted = ArrayList<Cand>()
        for (c in cands.sortedByDescending { it.conf }) {
            val lenC = (c.hi - c.lo) + EPS
            var overlap = false
            for (a in accepted) {
                if (c.lo >= a.hi || a.lo >= c.hi) continue
                val inter = max(0, min(c.hi, a.hi) - max(c.lo, a.lo))
                val lenA = (a.hi - a.lo) + EPS
                if (inter / min(lenC, lenA) > overlapThreshold) {
                    overlap = true
                    break
                }
            }
            if (!overlap) accepted += c
        }
        accepted.sortBy { it.lo }
        fixSwappedPairs(accepted)
        return accepted
    }

    /** Reference _fix_swapped_pairs: swap the two characters, keep each box where it is. */
    private fun fixSwappedPairs(chars: MutableList<Cand>) {
        for (wrong in SWAPPED_PAIRS) {
            val text = chars.joinToString("") { String(Character.toChars(it.cp)) }
            val idx = text.indexOf(wrong)
            if (idx != -1 && idx + 1 < chars.size) {
                val a = chars[idx]
                val b = chars[idx + 1]
                chars[idx] = Cand(b.cp, a.box, a.conf, a.lo, a.hi)
                chars[idx + 1] = Cand(a.cp, b.box, b.conf, b.lo, b.hi)
            }
        }
    }

    private companion object {
        const val DET_MODEL = "meiki.text.detect.v0.1.960x544.onnx"
        const val REC_MODEL = "meiki.text.rec.v0.960x32.onnx"
        const val VREC_MODEL = "meiki.text.rec.v0.vertical.32x480.onnx"
        const val DET_W = 960
        const val DET_H = 544
        const val REC_W = 960
        const val REC_H = 32
        const val VREC_W = 32
        const val VREC_H = 480
        const val VREC_MAX_CONTENT_H = 420
        const val VREC_OVERLAP_PX = 64
        const val X_OVERLAP = 0.3
        const val Y_OVERLAP = 0.3
        const val EPS = 1e-6
        const val DET_THRESHOLD = 0.5f
        const val REC_THRESHOLD = 0.1f
        const val MAX_BATCH = 8

        // Known mis-ordered pairs: the reference swaps the two characters when it finds one.
        val SWAPPED_PAIRS = listOf("儡傀", "談冗", "汰淘", "沱滂", "攣痙", "酊酩", "麭麺", "哭慟")
    }
}

/** The recognition model's label output is int64 or int32 depending on the export. */
internal fun labelRows(v: Any?): List<LongArray> = (v as Array<*>).map {
    when (it) {
        is LongArray -> it
        is IntArray -> LongArray(it.size) { i -> it[i].toLong() }
        else -> error("unexpected label type ${it?.javaClass}")
    }
}
