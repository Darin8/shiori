package app.shiori.ocr

import android.graphics.Bitmap

/** Luma of [src] at 1/[ChangeTuning.REDUCE] size (two exact 2x halvings, then one read of the small bitmap). */
fun bitmapLuma(src: Bitmap): LumaFrame {
    var cur = src
    var steps = 0
    var r = ChangeTuning.REDUCE
    while (r > 1) {
        val next = Bitmap.createScaledBitmap(cur, maxOf(1, cur.width / 2), maxOf(1, cur.height / 2), true)
        if (cur !== src && cur !== next) cur.recycle()
        cur = next
        r /= 2; steps++
    }
    val px = IntArray(cur.width * cur.height)
    cur.getPixels(px, 0, cur.width, 0, 0, cur.width, cur.height)
    val out = ByteArray(px.size) { LumaFrame.lumaOf(px[it]).toByte() }
    val f = LumaFrame(src.width, src.height, cur.width, cur.height, out)
    if (cur !== src) cur.recycle()
    return f
}
