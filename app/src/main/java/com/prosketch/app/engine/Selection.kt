package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SelectionMode(val label: String) { AUTOMATIC("자동"), FREEHAND("올가미"), RECTANGLE("직사각형"), ELLIPSE("타원") }
enum class SelectionOp { REPLACE, ADD, SUBTRACT }

/** Selection masks are ALPHA_8 bitmaps the size of the document. */
object SelectionOps {

    fun newMask(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    fun toBytes(mask: Bitmap): ByteArray {
        val w = mask.width; val h = mask.height
        val raw = ByteArray(mask.rowBytes * h)
        mask.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        if (mask.rowBytes == w) return raw
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * mask.rowBytes, out, y * w, w)
        return out
    }

    fun fromBytes(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = newMask(w, h)
        if (bmp.rowBytes == w) bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        else {
            val raw = ByteArray(bmp.rowBytes * h)
            for (y in 0 until h) System.arraycopy(bytes, y * w, raw, y * bmp.rowBytes, w)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
        }
        return bmp
    }

    /** Combines [shape] (drawn by [draw] on an empty A8 canvas) into [current]. */
    fun combine(current: Bitmap?, w: Int, h: Int, op: SelectionOp, draw: (Canvas, Paint) -> Unit): Bitmap? {
        val shape = newMask(w, h)
        draw(Canvas(shape), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        return combineMask(current, shape, op)
    }

    fun combineMask(current: Bitmap?, shape: Bitmap, op: SelectionOp): Bitmap? {
        return when {
            op == SelectionOp.REPLACE || current == null -> if (op == SelectionOp.SUBTRACT) null else shape
            op == SelectionOp.ADD -> {
                val out = current.copy(Bitmap.Config.ALPHA_8, true)
                Canvas(out).drawBitmap(shape, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC_OVER })
                out
            }
            else -> {
                val out = current.copy(Bitmap.Config.ALPHA_8, true)
                Canvas(out).drawBitmap(shape, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
                out
            }
        }
    }

    fun pathMask(current: Bitmap?, w: Int, h: Int, path: Path, op: SelectionOp) =
        combine(current, w, h, op) { c, p -> c.drawPath(path, p) }

    fun invert(mask: Bitmap?, w: Int, h: Int): Bitmap {
        val out = newMask(w, h)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        if (mask != null) c.drawBitmap(mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        return out
    }

    fun feather(mask: Bitmap, radius: Float): Bitmap {
        if (radius < 0.5f) return mask
        val w = mask.width; val h = mask.height
        val b = toBytes(mask)
        val plane = FloatArray(w * h) { (b[it].toInt() and 0xFF) / 255f }
        Filters.blurPlanes(arrayOf(plane), w, h, radius / 2f)
        val out = ByteArray(w * h) { (plane[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        return fromBytes(out, w, h)
    }

    fun isEmpty(mask: Bitmap): Boolean = Bitmaps.maskBounds(mask) == null

    /** Mask from the alpha of a layer (Layer → Select). */
    fun fromAlpha(layer: Bitmap): Bitmap {
        val out = newMask(layer.width, layer.height)
        Canvas(out).drawBitmap(layer, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        return out
    }

    /**
     * Display overlay: the unselected area is shaded with diagonal stripes, the selection edge
     * is outlined with a dashed black/white line. Built at reduced resolution for big canvases.
     */
    fun buildOverlay(mask: Bitmap, maxDim: Int = 2048): Pair<Bitmap, Float> {
        val w0 = mask.width; val h0 = mask.height
        val scale = min(1f, maxDim.toFloat() / max(w0, h0))
        val src = if (scale < 1f) Bitmap.createScaledBitmap(mask, max(1, (w0 * scale).toInt()), max(1, (h0 * scale).toInt()), true) else mask
        val w = src.width; val h = src.height
        val m = toBytes(src)
        val px = IntArray(w * h)
        Filters.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val v = m[i].toInt() and 0xFF
                val inside = v >= 128
                var edge = false
                if (x > 0 && ((m[i - 1].toInt() and 0xFF) >= 128) != inside) edge = true
                else if (y > 0 && ((m[i - w].toInt() and 0xFF) >= 128) != inside) edge = true
                else if (x < w - 1 && ((m[i + 1].toInt() and 0xFF) >= 128) != inside) edge = true
                else if (y < h - 1 && ((m[i + w].toInt() and 0xFF) >= 128) != inside) edge = true
                px[i] = when {
                    edge -> if (((x + y) / 6) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    v < 250 -> {
                        val stripe = ((x + y) / 10) % 2 == 0
                        val a = ((255 - v) / 255f * (if (stripe) 110 else 70)).toInt()
                        Color.argb(a, 40, 40, 48)
                    }
                    else -> 0
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out to (w0.toFloat() / w)
    }
}

/** Region growing used by ColorDrop, Fill and Automatic selection. */
object FloodFill {
    class Region(val mask: ByteArray, val bounds: Rect)   // 255 = inside, 128 = anti-aliased fringe

    private fun premulDiff(c1: Int, c2: Int): Int {
        val a1 = c1 ushr 24; val a2 = c2 ushr 24
        val r1 = ((c1 shr 16) and 0xFF) * a1 / 255; val r2 = ((c2 shr 16) and 0xFF) * a2 / 255
        val g1 = ((c1 shr 8) and 0xFF) * a1 / 255; val g2 = ((c2 shr 8) and 0xFF) * a2 / 255
        val b1 = (c1 and 0xFF) * a1 / 255; val b2 = (c2 and 0xFF) * a2 / 255
        return max(max(abs(a1 - a2), abs(r1 - r2)), max(abs(g1 - g2), abs(b1 - b2)))
    }

    /**
     * Two-pass chamfer (3-4) distance to the nearest "wall" pixel, in 1/3 px units, capped at [cap] px.
     */
    private fun wallDistance(wall: BooleanArray, w: Int, h: Int, cap: Int): IntArray {
        val inf = (cap + 2) * 3
        val d = IntArray(w * h) { if (wall[it]) 0 else inf }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (v == 0) continue
            if (x > 0) v = min(v, d[i - 1] + 3)
            if (y > 0) {
                v = min(v, d[i - w] + 3)
                if (x > 0) v = min(v, d[i - w - 1] + 4)
                if (x < w - 1) v = min(v, d[i - w + 1] + 4)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (v == 0) continue
            if (x < w - 1) v = min(v, d[i + 1] + 3)
            if (y < h - 1) {
                v = min(v, d[i + w] + 3)
                if (x < w - 1) v = min(v, d[i + w + 1] + 4)
                if (x > 0) v = min(v, d[i + w - 1] + 4)
            }
            d[i] = v
        }
        return d
    }

    /**
     * Flood region from (sx, sy). [gap] > 0 closes gaps in line art up to that many pixels: walls are
     * thickened by half the gap, the area is filled, then grown back so it still reaches the lines.
     */
    fun region(ref: IntArray, w: Int, h: Int, sx: Int, sy: Int, threshold: Float, fringe: Int = 2, gap: Int = 0): Region? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        val seed = ref[sy * w + sx]
        val limit = (threshold.coerceIn(0f, 0.976f) * 255f).toInt()
        if (gap > 1) {
            val half = (gap + 1) / 2   // thickening every wall by half the gap closes gaps up to [gap]
            val wall = BooleanArray(w * h) { premulDiff(ref[it], seed) > limit }
            val dist = wallDistance(wall, w, h, half)
            val blocked = half * 3
            if (dist[sy * w + sx] > blocked) {
                // fill the thinned area (walls + gap band blocked)
                // blocked pixels get a value whose alpha differs from the seed by ≥ 128
                val other = if ((seed ushr 24) > 127) 0 else 0xFF000000.toInt()
                val thin = IntArray(w * h) { if (dist[it] > blocked) seed else other }
                val core = region(thin, w, h, sx, sy, 0f, 0) ?: return null
                // grow back by gap px, but never into the original walls
                val inRegion = BooleanArray(w * h) { core.mask[it].toInt() != 0 }
                // √2·half reaches back into right-angle corners that the thinning rounded off
                val reach = (half * 1.45f).toInt() + 1
                val back = wallDistance(inRegion, w, h, reach)
                val grow = reach * 3
                val mask = ByteArray(w * h)
                var minX = w; var minY = h; var maxX = -1; var maxY = -1
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (back[i] <= grow && !wall[i]) {
                        mask[i] = 255.toByte()
                        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
                if (maxX < 0) return null
                return grow(mask, w, h, minX, minY, maxX, maxY, fringe)
            }
        }
        val mask = ByteArray(w * h)
        val stack = IntArray(w * h / 4 + 1024)
        var sp = 0
        var stackArr = stack
        fun push(v: Int) {
            if (sp >= stackArr.size) stackArr = stackArr.copyOf(stackArr.size * 2)
            stackArr[sp++] = v
        }
        fun ok(i: Int) = mask[i].toInt() == 0 && premulDiff(ref[i], seed) <= limit
        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        push(sy * w + sx)
        while (sp > 0) {
            val p = stackArr[--sp]
            val y = p / w
            var x = p % w
            if (!ok(p)) continue
            while (x > 0 && ok(y * w + x - 1)) x--
            var spanUp = false; var spanDown = false
            while (x < w && ok(y * w + x)) {
                val i = y * w + x
                mask[i] = 255.toByte()
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y > 0) {
                    val up = ok(i - w)
                    if (!spanUp && up) { push(i - w); spanUp = true } else if (spanUp && !up) spanUp = false
                }
                if (y < h - 1) {
                    val dn = ok(i + w)
                    if (!spanDown && dn) { push(i + w); spanDown = true } else if (spanDown && !dn) spanDown = false
                }
                x++
            }
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return grow(mask, w, h, minX, minY, maxX, maxY, fringe)
    }

    /** Fringe: grows the region by [fringe] px (marked 128) to fill under anti-aliased line edges. */
    private fun grow(mask: ByteArray, w: Int, h: Int, minX0: Int, minY0: Int, maxX0: Int, maxY0: Int, fringe: Int): Region {
        var minX = minX0; var minY = minY0; var maxX = maxX0; var maxY = maxY0
        // each ring grows from the previous one (ring 0 grows from the core, marked -1 == 255)
        var from = -1
        for (pass in 0 until fringe) {
            val y0 = max(0, minY - 1); val y1 = min(h - 1, maxY + 1)
            val x0 = max(0, minX - 1); val x1 = min(w - 1, maxX + 1)
            val marker = 10 + pass
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                if (mask[i].toInt() != 0) continue
                val n = (x > 0 && mask[i - 1].toInt() == from) || (x < w - 1 && mask[i + 1].toInt() == from) ||
                    (y > 0 && mask[i - w].toInt() == from) || (y < h - 1 && mask[i + w].toInt() == from)
                if (n) mask[i] = marker.toByte()
            }
            from = marker
            minX = x0; maxX = x1; minY = y0; maxY = y1
        }
        if (fringe > 0) for (i in mask.indices) { val v = mask[i].toInt(); if (v in 10 until 10 + fringe) mask[i] = 128.toByte() }
        return Region(mask, Rect(minX, minY, maxX + 1, maxY + 1))
    }

    /**
     * Applies a fill colour to [px] (unpremultiplied ARGB) inside [r].
     * Core pixels are replaced, fringe pixels receive the colour underneath the existing paint.
     */
    fun applyFill(px: IntArray, w: Int, r: Region, color: Int, sel: ByteArray?, alphaLock: Boolean) {
        val ca = color ushr 24
        for (y in r.bounds.top until r.bounds.bottom) for (x in r.bounds.left until r.bounds.right) {
            val i = y * w + x
            val m = r.mask[i].toInt() and 0xFF
            if (m == 0) continue
            val cur = px[i]
            var out = if (m == 255) srcOver(color, cur) else srcOver(cur, color)
            if (ca < 255 && m == 255) out = srcOver(color, cur)
            if (alphaLock) out = (out and 0x00FFFFFF) or (cur and 0xFF000000.toInt())
            if (sel != null) {
                val s = sel[i].toInt() and 0xFF
                if (s == 0) continue
                if (s < 255) out = lerp(cur, out, s / 255f)
            }
            px[i] = out
        }
    }

    fun srcOver(src: Int, dst: Int): Int {
        val sa = (src ushr 24) / 255f; val da = (dst ushr 24) / 255f
        val oa = sa + da * (1 - sa)
        if (oa <= 0f) return 0
        fun ch(s: Int, d: Int) = ((s * sa + d * da * (1 - sa)) / oa).toInt().coerceIn(0, 255)
        return ((oa * 255f + 0.5f).toInt() shl 24) or (ch((src shr 16) and 0xFF, (dst shr 16) and 0xFF) shl 16) or
            (ch((src shr 8) and 0xFF, (dst shr 8) and 0xFF) shl 8) or ch(src and 0xFF, dst and 0xFF)
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        fun c(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt()
        return (c(24) shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}
