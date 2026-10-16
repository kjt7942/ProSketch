package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Lets the editor substitute live content (stroke in progress, transform, filter preview, animation). */
interface RenderHook {
    /** Draw [layer] with [paint] (blend + opacity) including live modifications. Return false to use the bitmap. */
    fun drawLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean = false
    fun isVisible(layer: Layer): Boolean = layer.visible
    fun extraAlpha(layer: Layer): Float = 1f
    fun colorFilter(layer: Layer): ColorFilter? = null
}

/**
 * Renders the layer tree. Siblings are drawn bottom → top as [ClipItem]s; groups either pass through
 * (children blend with what is below the group) or are isolated in an offscreen layer when they have
 * their own blend mode, opacity or mask. Masks multiply the alpha by their luminance.
 */
object Compositor {
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }

    /** Luminance → alpha, so an opaque grey mask can be applied with DST_IN. */
    val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = BlendMode.DST_IN
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        )))
    }

    fun layerPaint(layer: Layer, alphaMul: Float = 1f, filter: ColorFilter? = null, filterBitmap: Boolean = true): Paint =
        Paint(if (filterBitmap) Paint.FILTER_BITMAP_FLAG else 0).apply {
            blendMode = layer.blend.nativeOrNormal
            alpha = (layer.opacity * alphaMul * 255f + 0.5f).toInt().coerceIn(0, 255)
            colorFilter = filter
        }

    fun drawBackground(canvas: Canvas, doc: Document) {
        if (doc.backgroundVisible) {
            canvas.save()
            canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
            canvas.drawColor(doc.backgroundColor or (0xFF shl 24))
            canvas.restore()
        }
    }

    fun drawLayerContent(canvas: Canvas, layer: Layer, paint: Paint?, hook: RenderHook?) {
        if (hook != null && hook.drawLive(canvas, layer, paint)) return
        canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
    }

    private fun visible(l: Layer, hook: RenderHook?) = hook?.isVisible(l) ?: l.visible

    private fun isPlain(p: Paint?) = p == null || (p.alpha == 255 && p.colorFilter == null && (p.blendMode == null || p.blendMode == BlendMode.SRC_OVER))

    /**
     * Draws one layer or group with [paint] applied to its composite. [paint] carries the blend mode,
     * opacity and colour filter to composite with (null = plain source-over).
     */
    fun drawNode(canvas: Canvas, doc: Document, layer: Layer, paint: Paint?, hook: RenderHook?, filterBitmap: Boolean) {
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        val mask = layer.mask
        if (layer.isGroup) {
            val items = doc.items(layer.id)
            if (mask == null && isPlain(paint)) {
                drawItems(canvas, doc, items, hook, filterBitmap)
            } else {
                canvas.saveLayer(bounds, paint)
                drawItems(canvas, doc, items, hook, filterBitmap)
                if (mask != null) drawLayerContent(canvas, mask, maskPaint, hook)
                canvas.restore()
            }
            return
        }
        if (mask == null) { drawLayerContent(canvas, layer, paint, hook); return }
        canvas.saveLayer(bounds, paint)
        drawLayerContent(canvas, layer, null, hook)
        drawLayerContent(canvas, mask, maskPaint, hook)
        canvas.restore()
    }

    /** The paint a sibling is composited with. Pass-through groups at 100% stay transparent to blending. */
    private fun nodePaint(l: Layer, hook: RenderHook?, filterBitmap: Boolean, blend: Boolean = true): Paint? {
        val a = hook?.extraAlpha(l) ?: 1f
        val f = hook?.colorFilter(l)
        if (l.isGroup && l.passThrough && l.opacity >= 0.999f && a >= 0.999f && f == null) return null
        return Paint(if (filterBitmap) Paint.FILTER_BITMAP_FLAG else 0).apply {
            blendMode = if (blend && !(l.isGroup && l.passThrough)) l.blend.nativeOrNormal else BlendMode.SRC_OVER
            alpha = (l.opacity * a * 255f + 0.5f).toInt().coerceIn(0, 255)
            colorFilter = f
        }
    }

    fun drawItem(canvas: Canvas, doc: Document, item: ClipItem, hook: RenderHook?, filterBitmap: Boolean = true) {
        val base = item.base
        if (!visible(base, hook)) return
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        val kids = item.clips.filter { visible(it, hook) }
        if (kids.isEmpty()) { drawNode(canvas, doc, base, nodePaint(base, hook, filterBitmap), hook, filterBitmap); return }
        // base composited with its blend mode; clips are confined to the base's alpha
        canvas.saveLayer(bounds, Paint().apply { blendMode = if (base.isGroup && base.passThrough) BlendMode.SRC_OVER else base.blend.nativeOrNormal })
        drawNode(canvas, doc, base, nodePaint(base, hook, filterBitmap, blend = false), hook, filterBitmap)
        for (k in kids) {
            canvas.saveLayer(bounds, nodePaint(k, hook, filterBitmap) ?: Paint())
            drawNode(canvas, doc, k, null, hook, filterBitmap)
            drawNode(canvas, doc, base, dstIn, hook, filterBitmap)
            canvas.restore()
        }
        canvas.restore()
    }

    /**
     * Draws siblings in order. Items using a CPU-only blend mode need the pixels below them, so from
     * the first such item on, the context is rendered into a software bitmap and blended exactly.
     */
    fun drawItems(canvas: Canvas, doc: Document, items: List<ClipItem>, hook: RenderHook?, filterBitmap: Boolean = true, backdrop: ((Canvas) -> Unit)? = null) {
        val firstCustom = items.indexOfFirst { visible(it.base, hook) && doc.usesCustomBlend(it) }
        if (firstCustom < 0) {
            backdrop?.invoke(canvas)
            for (it in items) drawItem(canvas, doc, it, hook, filterBitmap)
            return
        }
        val buf = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val bc = Canvas(buf)
        backdrop?.invoke(bc)
        for (it in items) {
            val needsSoft = visible(it.base, hook) && it.clips.isEmpty() && it.base.blend.isCustom && !(it.base.isGroup && it.base.passThrough)
            if (!needsSoft) {
                if (doc.usesCustomBlend(it) && it.clips.isNotEmpty() && it.base.blend.isCustom) {
                    // clipped stack with a custom base mode: render the stack, then blend it
                    // (drawItem composites the base as normal, opacity included, into the empty buffer)
                    val tmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                    drawItem(Canvas(tmp), doc, it, hook, filterBitmap)
                    SoftBlend.blend(buf, tmp, it.base.blend, 1f)
                    tmp.recycle()
                } else drawItem(bc, doc, it, hook, filterBitmap)
                continue
            }
            val l = it.base
            val tmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            val tc = Canvas(tmp)
            val p = nodePaint(l, hook, filterBitmap)?.apply { blendMode = BlendMode.SRC_OVER; alpha = 255 }
            drawNode(tc, doc, l, p, hook, filterBitmap)
            SoftBlend.blend(buf, tmp, l.blend, (l.opacity * (hook?.extraAlpha(l) ?: 1f)).coerceIn(0f, 1f))
            tmp.recycle()
        }
        canvas.drawBitmap(buf, 0f, 0f, if (filterBitmap) Paint(Paint.FILTER_BITMAP_FLAG) else null)
        // a GPU canvas only records the bitmap; recycling it before the frame renders would crash
        if (!canvas.isHardwareAccelerated) buf.recycle()
    }

    /** True when [item] can be recorded on its own and composited with source-over without changing the result. */
    fun isolatable(doc: Document, item: ClipItem): Boolean {
        val b = item.base
        if (b.isGroup && b.passThrough) return doc.items(b.id).all { isolatable(doc, it) }
        return b.blend == LayerBlend.NORMAL
    }

    /** Full artwork (background + every visible layer). */
    fun drawAll(canvas: Canvas, doc: Document, hook: RenderHook? = null, background: Boolean = true, filterBitmap: Boolean = true) {
        val items = doc.items()
        if (items.any { doc.usesCustomBlend(it) }) {
            // custom modes blend against the background too
            drawItems(canvas, doc, items, hook, filterBitmap, backdrop = if (background) { c -> drawBackground(c, doc) } else null)
        } else {
            if (background) drawBackground(canvas, doc)
            drawItems(canvas, doc, items, hook, filterBitmap)
        }
    }

    /** Software-flattened copy of the artwork, optionally scaled. */
    fun flatten(doc: Document, scale: Float = 1f, hook: RenderHook? = null, background: Boolean = true): Bitmap {
        val w = maxOf(1, (doc.width * scale).toInt())
        val h = maxOf(1, (doc.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (scale == 1f) { drawAll(c, doc, hook, background); return out }
        if (doc.items().any { doc.usesCustomBlend(it) }) {
            val full = flatten(doc, 1f, hook, background)
            c.drawBitmap(full, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            full.recycle()
            return out
        }
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        drawAll(c, doc, hook, background)
        return out
    }

    /** One layer or group rendered alone at full size (used for merging, copying and export). */
    fun renderNode(doc: Document, l: Layer, hook: RenderHook? = null): Bitmap {
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (l.isGroup) {
            drawItems(c, doc, doc.items(l.id), hook)
            l.mask?.let { c.drawBitmap(it.bitmap, 0f, 0f, maskPaint) }
        } else drawNode(c, doc, l, null, hook, true)
        return out
    }

    /** Composite colour under one document pixel (unpremultiplied ARGB). */
    fun sampleComposite(doc: Document, x: Int, y: Int, hook: RenderHook? = null): Int {
        if (doc.items().any { doc.usesCustomBlend(it) }) {
            val full = flatten(doc, 1f, hook, true)
            val p = full.getPixel(x, y); full.recycle(); return p
        }
        val out = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-x.toFloat(), -y.toFloat())
        drawAll(c, doc, hook, true)
        val p = out.getPixel(0, 0)
        out.recycle()
        return p
    }
}

/** Exact CPU compositing (W3C formula) for the blend modes Skia does not provide. */
object SoftBlend {
    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))

    /** Blends premultiplied [src] onto [dst] in place with [mode] and [opacity]. */
    fun blend(dst: Bitmap, src: Bitmap, mode: LayerBlend, opacity: Float) {
        val w = dst.width; val h = dst.height
        val bands = 8
        val jobs = ArrayList<Future<*>>()
        for (b in 0 until bands) {
            val y0 = h * b / bands; val y1 = h * (b + 1) / bands
            jobs.add(pool.submit {
                val d = IntArray(w); val s = IntArray(w)
                for (y in y0 until y1) {
                    dst.getPixels(d, 0, w, 0, y, w, 1)
                    src.getPixels(s, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val sc = s[x]
                        if (sc ushr 24 == 0) continue
                        d[x] = blendPixel(d[x], sc, mode, opacity)
                    }
                    dst.setPixels(d, 0, w, 0, y, w, 1)
                }
            })
        }
        jobs.forEach { it.get() }
    }

    /** Both colours unpremultiplied ARGB (as returned by Bitmap.getPixels). */
    fun blendPixel(dc: Int, sc: Int, mode: LayerBlend, opacity: Float): Int {
        val sa = ((sc ushr 24) / 255f) * opacity
        if (sa <= 0f) return dc
        val da = (dc ushr 24) / 255f
        val sr = ((sc shr 16) and 255) / 255f; val sg = ((sc shr 8) and 255) / 255f; val sb = (sc and 255) / 255f
        val dr = ((dc shr 16) and 255) / 255f; val dg = ((dc shr 8) and 255) / 255f; val db = (dc and 255) / 255f
        var br: Float; var bg: Float; var bb: Float
        when (mode) {
            LayerBlend.DARKER_COLOR, LayerBlend.LIGHTER_COLOR -> {
                val ls = 0.3f * sr + 0.59f * sg + 0.11f * sb
                val ld = 0.3f * dr + 0.59f * dg + 0.11f * db
                val takeSrc = if (mode == LayerBlend.DARKER_COLOR) ls < ld else ls > ld
                if (takeSrc) { br = sr; bg = sg; bb = sb } else { br = dr; bg = dg; bb = db }
            }
            else -> { br = ch(dr, sr, mode); bg = ch(dg, sg, mode); bb = ch(db, sb, mode) }
        }
        // co = cs·(1−ab) + cb·(1−as) + as·ab·B ; ao = as + ab·(1−as)
        val ao = sa + da * (1f - sa)
        if (ao <= 0f) return 0
        fun comp(cs: Float, cb: Float, b: Float) = ((cs * sa * (1f - da) + cb * da * (1f - sa) + sa * da * b) / ao).coerceIn(0f, 1f)
        val r = comp(sr, dr, br); val g = comp(sg, dg, bg); val bl = comp(sb, db, bb)
        return ((ao * 255f + 0.5f).toInt() shl 24) or ((r * 255f + 0.5f).toInt() shl 16) or ((g * 255f + 0.5f).toInt() shl 8) or (bl * 255f + 0.5f).toInt()
    }

    private fun burn(b: Float, s: Float) = if (b >= 1f) 1f else if (s <= 0f) 0f else 1f - minOf(1f, (1f - b) / s)
    private fun dodge(b: Float, s: Float) = if (b <= 0f) 0f else if (s >= 1f) 1f else minOf(1f, b / (1f - s))

    private fun ch(b: Float, s: Float, mode: LayerBlend): Float = when (mode) {
        LayerBlend.LINEAR_BURN -> maxOf(0f, b + s - 1f)
        LayerBlend.SUBTRACT -> maxOf(0f, b - s)
        LayerBlend.DIVIDE -> if (s <= 0f) (if (b <= 0f) 0f else 1f) else minOf(1f, b / s)
        LayerBlend.VIVID_LIGHT -> if (s <= 0.5f) burn(b, 2f * s) else dodge(b, 2f * (s - 0.5f))
        LayerBlend.LINEAR_LIGHT -> (b + 2f * s - 1f).coerceIn(0f, 1f)
        LayerBlend.PIN_LIGHT -> if (s <= 0.5f) minOf(b, 2f * s) else maxOf(b, 2f * s - 1f)
        LayerBlend.HARD_MIX -> if (b + s >= 1f) 1f else 0f
        else -> s
    }
}
