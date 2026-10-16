package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter

/**
 * Animation Assist: every top-level layer (or group) is a frame, bottom = first. The first frame can
 * be a fixed background and the last a fixed foreground; frames can be held for extra beats.
 */
object Anim {
    fun background(doc: Document): Layer? = doc.topLevel().let { f -> if (doc.animation.firstFrameBackground && f.size > 1) f.first() else null }
    fun foreground(doc: Document): Layer? = doc.topLevel().let { f -> if (doc.animation.lastFrameForeground && f.size > 1) f.last() else null }

    /** The animated frames (without background/foreground). */
    fun frames(doc: Document): List<Layer> {
        val bg = background(doc); val fg = foreground(doc)
        return doc.topLevel().filter { it !== bg && it !== fg }
    }

    /** One loop of playback: holds expanded, ping-pong mirrored. */
    fun sequence(doc: Document): List<Layer> {
        val base = frames(doc).flatMap { f -> List(1 + f.holdFrames.coerceIn(0, 120)) { f } }
        return when (doc.animation.loop) {
            AnimLoop.PINGPONG -> if (base.size > 2) base + base.reversed().drop(1).dropLast(1) else base
            else -> base
        }
    }

    fun totalFrames(doc: Document) = sequence(doc).size

    /** The frame shown while editing: the top-level ancestor of the selected layer. */
    fun currentFrame(doc: Document): Layer? = doc.activeLayer?.let { doc.topAncestor(it) }

    private fun drawFrame(canvas: Canvas, doc: Document, frame: Layer, hook: RenderHook?, alpha: Float = 1f, tint: Int? = null) {
        if (!frame.visible) return
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = if (frame.isGroup && frame.passThrough) android.graphics.BlendMode.SRC_OVER else frame.blend.nativeOrNormal
            this.alpha = (frame.opacity * alpha * 255f).toInt().coerceIn(0, 255)
            if (tint != null) colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
        }
        Compositor.drawNode(canvas, doc, frame, p, hook, true)
    }

    /**
     * Editor view: background, onion skins, the current frame and foreground. With "blend primary
     * frame" off, the current frame is drawn last so it sits solid on top of the onion skins.
     */
    fun drawEditing(canvas: Canvas, doc: Document, current: Layer?, playing: Boolean, hook: RenderHook?) {
        Compositor.drawBackground(canvas, doc)
        val a = doc.animation
        background(doc)?.let { drawFrame(canvas, doc, it, hook) }
        val frames = frames(doc)
        val ci = frames.indexOf(current)
        if (playing || ci < 0) {
            // background/foreground are already drawn; any other frame is drawn alone
            if (current != null && current !== background(doc) && current !== foreground(doc)) drawFrame(canvas, doc, current, hook)
        } else {
            val onion = (1..a.onionFrames).flatMap { d -> listOfNotNull(frames.getOrNull(ci - d)?.let { it to -d }, frames.getOrNull(ci + d)?.let { it to d }) }
            fun onionAlpha(d: Int) = a.onionOpacity * (1f - (kotlin.math.abs(d) - 1f) / (a.onionFrames + 1f))
            fun tintFor(d: Int) = if (!a.colorOnion) null else if (d < 0) a.onionBefore else a.onionAfter
            if (a.blendPrimary) {
                for ((f, d) in onion.filter { it.second < 0 }.sortedBy { it.second }) drawFrame(canvas, doc, f, hook, onionAlpha(d), tintFor(d))
                drawFrame(canvas, doc, current!!, hook)
                for ((f, d) in onion.filter { it.second > 0 }.sortedBy { it.second }) drawFrame(canvas, doc, f, hook, onionAlpha(d), tintFor(d))
            } else {
                for ((f, d) in onion.sortedByDescending { kotlin.math.abs(it.second) }) drawFrame(canvas, doc, f, hook, onionAlpha(d), tintFor(d))
                drawFrame(canvas, doc, current!!, hook)
            }
        }
        foreground(doc)?.let { drawFrame(canvas, doc, it, hook) }
    }

    /** Export frame: background colour (or transparent), background frame, the frame, foreground. */
    fun renderFrame(doc: Document, frame: Layer, scale: Float, transparent: Boolean = false): Bitmap {
        val w = maxOf(2, (doc.width * scale).toInt()); val h = maxOf(2, (doc.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (!transparent) c.drawColor(if (doc.backgroundVisible) doc.backgroundColor or (0xFF shl 24) else Color.WHITE)
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        background(doc)?.takeIf { it !== frame }?.let { drawFrame(c, doc, it, null) }
        drawFrame(c, doc, frame, null)
        foreground(doc)?.takeIf { it !== frame }?.let { drawFrame(c, doc, it, null) }
        return out
    }
}
