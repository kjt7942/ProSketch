package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF

/** Lets the editor substitute live content (stroke in progress, transform, filter preview, animation). */
interface RenderHook {
    /** Draw [layer] with [paint] (blend + opacity) including live modifications. Return false to use the bitmap. */
    fun drawLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean = false
    fun isVisible(index: Int, layer: Layer): Boolean = layer.visible
    fun extraAlpha(index: Int, layer: Layer): Float = 1f
    fun colorFilter(index: Int, layer: Layer): ColorFilter? = null
}

object Compositor {
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }

    fun layerPaint(layer: Layer, alphaMul: Float = 1f, filter: ColorFilter? = null, filterBitmap: Boolean = true): Paint =
        Paint(if (filterBitmap) Paint.FILTER_BITMAP_FLAG else 0).apply {
            blendMode = layer.blend.mode
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

    fun drawGroup(canvas: Canvas, doc: Document, g: LayerGroup, hook: RenderHook?, filterBitmap: Boolean = true) {
        val layers = doc.layers
        val base = layers[g.baseIndex]
        if (!(hook?.isVisible(g.baseIndex, base) ?: base.visible)) return
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        val kids = (g.baseIndex + 1..g.endIndex).filter { i -> hook?.isVisible(i, layers[i]) ?: layers[i].visible }
        val baseAlpha = hook?.extraAlpha(g.baseIndex, base) ?: 1f
        val baseFilter = hook?.colorFilter(g.baseIndex, base)
        if (kids.isEmpty()) {
            drawLayerContent(canvas, base, layerPaint(base, baseAlpha, baseFilter, filterBitmap), hook)
            return
        }
        val groupPaint = Paint().apply { blendMode = base.blend.mode }
        canvas.saveLayer(bounds, groupPaint)
        val inner = Paint(if (filterBitmap) Paint.FILTER_BITMAP_FLAG else 0).apply {
            alpha = (base.opacity * baseAlpha * 255f).toInt().coerceIn(0, 255)
            colorFilter = baseFilter
        }
        drawLayerContent(canvas, base, inner, hook)
        for (k in kids) {
            val child = layers[k]
            canvas.saveLayer(bounds, layerPaint(child, hook?.extraAlpha(k, child) ?: 1f, hook?.colorFilter(k, child), filterBitmap))
            drawLayerContent(canvas, child, null, hook)
            canvas.drawBitmap(base.bitmap, 0f, 0f, dstIn)
            canvas.restore()
        }
        canvas.restore()
    }

    fun drawGroups(canvas: Canvas, doc: Document, groups: List<LayerGroup>, hook: RenderHook?, filterBitmap: Boolean = true) {
        for (g in groups) drawGroup(canvas, doc, g, hook, filterBitmap)
    }

    /** Full artwork (background + every visible layer). */
    fun drawAll(canvas: Canvas, doc: Document, hook: RenderHook? = null, background: Boolean = true) {
        if (background) drawBackground(canvas, doc)
        drawGroups(canvas, doc, doc.groups(), hook)
    }

    /** Software-flattened copy of the artwork, optionally scaled. */
    fun flatten(doc: Document, scale: Float = 1f, hook: RenderHook? = null, background: Boolean = true): Bitmap {
        val w = maxOf(1, (doc.width * scale).toInt())
        val h = maxOf(1, (doc.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        drawAll(c, doc, hook, background)
        return out
    }

    /** Composite colour under one document pixel (unpremultiplied ARGB). */
    fun sampleComposite(doc: Document, x: Int, y: Int, hook: RenderHook? = null): Int {
        val out = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-x.toFloat(), -y.toFloat())
        drawAll(c, doc, hook, true)
        val p = out.getPixel(0, 0)
        out.recycle()
        return p
    }
}
