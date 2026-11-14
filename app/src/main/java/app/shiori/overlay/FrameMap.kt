package app.shiori.overlay

import app.shiori.ocr.Box

/**
 * Maps OCR frame pixels to the overlay view's pixels and back.
 * The frame is the whole display ([displayW] x [displayH] screen px, possibly captured at another size);
 * the view's top-left sits at ([originX], [originY]) on the screen (non-zero when system bars or a cutout
 * push the window down or sideways). A view that covers the display at the frame's size is the identity.
 */
class FrameMap(
    frameW: Int,
    frameH: Int,
    displayW: Int = frameW,
    displayH: Int = frameH,
    private val originX: Float = 0f,
    private val originY: Float = 0f,
) {
    /** Screen px per frame px. */
    private val kx = if (frameW > 0 && displayW > 0) displayW.toFloat() / frameW else 1f
    private val ky = if (frameH > 0 && displayH > 0) displayH.toFloat() / frameH else 1f

    fun toViewX(x: Float) = x * kx - originX
    fun toViewY(y: Float) = y * ky - originY
    fun toFrameX(x: Float) = (x + originX) / kx
    fun toFrameY(y: Float) = (y + originY) / ky

    fun toView(b: Box) = Box(toViewX(b.left), toViewY(b.top), toViewX(b.right), toViewY(b.bottom))
}
