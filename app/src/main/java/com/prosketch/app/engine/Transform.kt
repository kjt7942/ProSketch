package com.prosketch.app.engine

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class TransformMode(val label: String) { FREEFORM(L.t("자유형태")), UNIFORM(L.t("균등")), DISTORT(L.t("왜곡")), WARP(L.t("뒤틀기")) }
enum class Interpolation(val label: String) { NEAREST(L.t("최근접 이웃")), BILINEAR(L.t("쌍선형")), BICUBIC(L.t("쌍입방")) }

/**
 * Floating content being transformed. The quad corners (TL, TR, BR, BL) are the single source of truth
 * for Freeform / Uniform / Distort; Warp adds a 4×4 Bézier control net.
 */
class TransformSession(
    val layer: Layer,
    val floating: Bitmap,
    val origin: Rect,                 // where the floating content came from
    val beforeLayer: Bitmap,          // full copy for undo
    val beforeSelection: Bitmap?,
    val floatingMask: Bitmap?,        // selection crop (moves with the content)
    val fromInsert: Boolean = false,  // created by "insert image/text" → undo removes layer too
) {
    var mode = TransformMode.FREEFORM
    var interpolation = Interpolation.BILINEAR
    var magnetics = false
    val corners = FloatArray(8)
    private val warp = FloatArray(32)
    private var warpInit = false

    init { reset() }

    fun reset() {
        corners[0] = origin.left.toFloat(); corners[1] = origin.top.toFloat()
        corners[2] = origin.right.toFloat(); corners[3] = origin.top.toFloat()
        corners[4] = origin.right.toFloat(); corners[5] = origin.bottom.toFloat()
        corners[6] = origin.left.toFloat(); corners[7] = origin.bottom.toFloat()
        warpInit = false
    }

    fun center(): PointF = PointF((corners[0] + corners[2] + corners[4] + corners[6]) / 4f, (corners[1] + corners[3] + corners[5] + corners[7]) / 4f)

    fun corner(i: Int) = PointF(corners[i * 2], corners[i * 2 + 1])
    fun setCorner(i: Int, p: PointF) { corners[i * 2] = p.x; corners[i * 2 + 1] = p.y; warpInit = false }

    fun translate(dx: Float, dy: Float) {
        for (i in 0 until 4) { corners[i * 2] += dx; corners[i * 2 + 1] += dy }
        if (warpInit) for (i in 0 until 16) { warp[i * 2] += dx; warp[i * 2 + 1] += dy }
    }

    fun rotate(deg: Float, pivot: PointF = center()) {
        val m = Matrix().apply { setRotate(deg, pivot.x, pivot.y) }
        m.mapPoints(corners)
        if (warpInit) m.mapPoints(warp)
    }

    fun scaleAbout(s: Float, pivot: PointF = center()) {
        val m = Matrix().apply { setScale(s, s, pivot.x, pivot.y) }
        m.mapPoints(corners)
        if (warpInit) m.mapPoints(warp)
    }

    fun flip(horizontal: Boolean) {
        val c = center()
        val ang = angle()
        val m = Matrix().apply {
            postRotate(-ang, c.x, c.y)
            if (horizontal) postScale(-1f, 1f, c.x, c.y) else postScale(1f, -1f, c.x, c.y)
            postRotate(ang, c.x, c.y)
        }
        m.mapPoints(corners)
        if (warpInit) m.mapPoints(warp)
    }

    fun angle(): Float = Math.toDegrees(atan2((corners[3] - corners[1]).toDouble(), (corners[2] - corners[0]).toDouble())).toFloat()

    fun widthPx() = hypot(corners[2] - corners[0], corners[3] - corners[1])
    fun heightPx() = hypot(corners[6] - corners[0], corners[7] - corners[1])

    /** Drag of corner [i] (0..3) to [p] honouring the current mode. */
    fun dragCorner(i: Int, p: PointF) {
        when (mode) {
            TransformMode.DISTORT, TransformMode.WARP -> setCorner(i, p)
            TransformMode.FREEFORM, TransformMode.UNIFORM -> {
                val opp = corner((i + 2) % 4)
                val ux0 = corners[2] - corners[0]; val uy0 = corners[3] - corners[1]
                val vx0 = corners[6] - corners[0]; val vy0 = corners[7] - corners[1]
                val ul = hypot(ux0, uy0).coerceAtLeast(1e-3f); val vl = hypot(vx0, vy0).coerceAtLeast(1e-3f)
                val ux = ux0 / ul; val uy = uy0 / ul; val vx = vx0 / vl; val vy = vy0 / vl
                // signs: which direction from the opposite corner this corner lies
                val su = if (i == 1 || i == 2) 1f else -1f
                val sv = if (i == 2 || i == 3) 1f else -1f
                val dx = p.x - opp.x; val dy = p.y - opp.y
                var w = (dx * ux + dy * uy) * su
                var h = (dx * vx + dy * vy) * sv
                if (mode == TransformMode.UNIFORM) {
                    val s = max((w / ul), (h / vl))
                    w = ul * s; h = vl * s
                }
                if (kotlin.math.abs(w) < 2f) w = 2f * (if (w < 0) -1 else 1)
                if (kotlin.math.abs(h) < 2f) h = 2f * (if (h < 0) -1 else 1)
                rebuild(opp, (i + 2) % 4, ux * su * w, uy * su * w, vx * sv * h, vy * sv * h)
            }
        }
    }

    /** Rebuilds the parallelogram from a fixed corner [fixedIdx] at [fixed] and the vectors to the dragged corner. */
    private fun rebuild(fixed: PointF, fixedIdx: Int, ax: Float, ay: Float, bx: Float, by: Float) {
        // dragged corner = fixed + a + b ; neighbours = fixed + a, fixed + b
        val dragged = (fixedIdx + 2) % 4
        val pts = arrayOfNulls<PointF>(4)
        pts[fixedIdx] = fixed
        pts[dragged] = PointF(fixed.x + ax + bx, fixed.y + ay + by)
        // neighbours of fixed along u (TL→TR) or v (TL→BL)
        val uNeighbour = when (fixedIdx) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
        val vNeighbour = when (fixedIdx) { 0 -> 3; 3 -> 0; 1 -> 2; else -> 1 }
        pts[uNeighbour] = PointF(fixed.x + ax, fixed.y + ay)
        pts[vNeighbour] = PointF(fixed.x + bx, fixed.y + by)
        for (k in 0 until 4) { corners[k * 2] = pts[k]!!.x; corners[k * 2 + 1] = pts[k]!!.y }
        warpInit = false
    }

    /** Drag of edge midpoint [e] (0 = top, 1 = right, 2 = bottom, 3 = left). */
    fun dragEdge(e: Int, p: PointF) {
        val a = e; val b = (e + 1) % 4
        when (mode) {
            TransformMode.DISTORT, TransformMode.WARP -> {
                val mid = PointF((corners[a * 2] + corners[b * 2]) / 2, (corners[a * 2 + 1] + corners[b * 2 + 1]) / 2)
                val dx = p.x - mid.x; val dy = p.y - mid.y
                corners[a * 2] += dx; corners[a * 2 + 1] += dy
                corners[b * 2] += dx; corners[b * 2 + 1] += dy
                warpInit = false
            }
            else -> {
                // move edge along its normal; uniform scales both axes about the opposite edge
                val oa = (e + 3) % 4; val ob = (e + 2) % 4 // opposite edge corners (by index pairing)
                val oppMid = PointF((corners[oa * 2] + corners[ob * 2]) / 2, (corners[oa * 2 + 1] + corners[ob * 2 + 1]) / 2)
                val mid = PointF((corners[a * 2] + corners[b * 2]) / 2, (corners[a * 2 + 1] + corners[b * 2 + 1]) / 2)
                val nx0 = mid.x - oppMid.x; val ny0 = mid.y - oppMid.y
                val nl = hypot(nx0, ny0).coerceAtLeast(1e-3f)
                val nx = nx0 / nl; val ny = ny0 / nl
                val newLen = ((p.x - oppMid.x) * nx + (p.y - oppMid.y) * ny).coerceAtLeast(2f)
                val s = newLen / nl
                if (mode == TransformMode.UNIFORM) {
                    scaleAbout(s, oppMid)
                } else {
                    for (k in intArrayOf(a, b)) {
                        val px = corners[k * 2] - oppMid.x; val py = corners[k * 2 + 1] - oppMid.y
                        val along = px * nx + py * ny
                        corners[k * 2] += nx * along * (s - 1f); corners[k * 2 + 1] += ny * along * (s - 1f)
                    }
                    warpInit = false
                }
            }
        }
    }

    // ---- warp ----
    private fun ensureWarp() {
        if (warpInit) return
        for (j in 0 until 4) for (i in 0 until 4) {
            val u = i / 3f; val v = j / 3f
            val p = bilinear(u, v)
            warp[(j * 4 + i) * 2] = p.x; warp[(j * 4 + i) * 2 + 1] = p.y
        }
        warpInit = true
    }

    private fun bilinear(u: Float, v: Float): PointF {
        val tx = corners[0] + (corners[2] - corners[0]) * u; val ty = corners[1] + (corners[3] - corners[1]) * u
        val bx = corners[6] + (corners[4] - corners[6]) * u; val by = corners[7] + (corners[5] - corners[7]) * u
        return PointF(tx + (bx - tx) * v, ty + (by - ty) * v)
    }

    fun warpPoints(): FloatArray { ensureWarp(); return warp }

    /** Moves the net around [at] by (dx, dy). Grabbing a control point moves it fully; elsewhere uses a falloff. */
    fun warpDrag(at: PointF, dx: Float, dy: Float, grabbed: Int) {
        ensureWarp()
        if (grabbed >= 0) {
            warp[grabbed * 2] += dx; warp[grabbed * 2 + 1] += dy
        } else {
            val rad = max(widthPx(), heightPx()) * 0.45f
            for (k in 0 until 16) {
                val d = hypot(warp[k * 2] - at.x, warp[k * 2 + 1] - at.y)
                val t = (1f - d / rad).coerceIn(0f, 1f)
                val w = t * t * (3 - 2 * t)
                warp[k * 2] += dx * w; warp[k * 2 + 1] += dy * w
            }
        }
        // keep the outer corners in sync with the net
        corners[0] = warp[0]; corners[1] = warp[1]
        corners[2] = warp[6]; corners[3] = warp[7]
        corners[4] = warp[30]; corners[5] = warp[31]
        corners[6] = warp[24]; corners[7] = warp[25]
    }

    private fun bern(i: Int, t: Float): Float {
        val it = 1 - t
        return when (i) { 0 -> it * it * it; 1 -> 3 * t * it * it; 2 -> 3 * t * t * it; else -> t * t * t }
    }

    fun meshVerts(n: Int): FloatArray {
        ensureWarp()
        val out = FloatArray((n + 1) * (n + 1) * 2)
        for (y in 0..n) for (x in 0..n) {
            val u = x / n.toFloat(); val v = y / n.toFloat()
            var px = 0f; var py = 0f
            for (j in 0 until 4) {
                val bv = bern(j, v)
                for (i in 0 until 4) {
                    val w = bern(i, u) * bv
                    px += warp[(j * 4 + i) * 2] * w; py += warp[(j * 4 + i) * 2 + 1] * w
                }
            }
            out[(y * (n + 1) + x) * 2] = px; out[(y * (n + 1) + x) * 2 + 1] = py
        }
        return out
    }

    fun matrix(): Matrix {
        val src = floatArrayOf(0f, 0f, floating.width.toFloat(), 0f, floating.width.toFloat(), floating.height.toFloat(), 0f, floating.height.toFloat())
        return Matrix().apply { setPolyToPoly(src, 0, corners, 0, 4) }
    }

    fun paint(): Paint = Paint().apply {
        isFilterBitmap = interpolation != Interpolation.NEAREST
        isAntiAlias = interpolation != Interpolation.NEAREST
    }

    fun drawFloating(canvas: Canvas, bmp: Bitmap = floating, p: Paint = paint()) {
        if (mode == TransformMode.WARP && warpInit) {
            canvas.drawBitmapMesh(bmp, 24, 24, meshVerts(24), 0, null, 0, p)
        } else {
            canvas.drawBitmap(bmp, matrix(), p)
        }
    }
}

enum class LiquifyMode(val label: String) {
    PUSH(L.t("밀기")), TWIRL_RIGHT(L.t("오른쪽 비틀기")), TWIRL_LEFT(L.t("왼쪽 비틀기")), PINCH(L.t("오목")), EXPAND(L.t("볼록")),
    CRYSTALS(L.t("크리스털")), EDGE(L.t("가장자리")), RECONSTRUCT(L.t("재구성"))
}

/** Mesh-based liquify. The mesh is drawn with drawBitmapMesh (GPU accelerated on screen). */
class LiquifySession(val layer: Layer, val source: Bitmap) {
    val cell = max(12, max(source.width, source.height) / 170)
    val cols = (source.width + cell - 1) / cell
    val rows = (source.height + cell - 1) / cell
    val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val orig = FloatArray(verts.size)
    private val rnd = Random(42)
    private val jitter = FloatArray((cols + 1) * (rows + 1)) { rnd.nextFloat() * 2f - 1f }
    var mode = LiquifyMode.PUSH
    var size = 0.25f        // 0..1 → radius
    var pressure = 0.6f
    var distortion = 0f
    var momentum = 0f
    var amount = 1f
    var changed = false
        private set

    init {
        for (y in 0..rows) for (x in 0..cols) {
            val i = (y * (cols + 1) + x) * 2
            // drawBitmapMesh spreads texture coordinates evenly over the bitmap
            orig[i] = x * source.width / cols.toFloat()
            orig[i + 1] = y * source.height / rows.toFloat()
        }
        orig.copyInto(verts)
    }

    fun radius(): Float = (max(source.width, source.height) * (0.01f + size * size * 0.35f))

    fun reset() { orig.copyInto(verts); changed = false }

    fun stroke(x: Float, y: Float, dx: Float, dy: Float, pen: Float) {
        val r = radius()
        val strength = pressure * pen * amount
        val x0 = ((x - r) / cell).toInt().coerceIn(0, cols); val x1 = ((x + r) / cell).toInt().coerceIn(0, cols)
        val y0 = ((y - r) / cell).toInt().coerceIn(0, rows); val y1 = ((y + r) / cell).toInt().coerceIn(0, rows)
        val dl = hypot(dx, dy)
        for (gy in (y0 - 2).coerceAtLeast(0)..(y1 + 2).coerceAtMost(rows)) for (gx in (x0 - 2).coerceAtLeast(0)..(x1 + 2).coerceAtMost(cols)) {
            val k = gy * (cols + 1) + gx
            val i = k * 2
            val vx = verts[i]; val vy = verts[i + 1]
            val d = hypot(vx - x, vy - y)
            if (d >= r) continue
            val t = 1f - d / r
            var w = t * t * (3 - 2 * t) * strength
            if (distortion > 0f) w *= (1f + distortion * jitter[k])
            when (mode) {
                LiquifyMode.PUSH -> { verts[i] += dx * w; verts[i + 1] += dy * w }
                LiquifyMode.TWIRL_RIGHT, LiquifyMode.TWIRL_LEFT -> {
                    val a = (if (mode == LiquifyMode.TWIRL_RIGHT) 1f else -1f) * 0.06f * w * (1f + dl * 0.02f)
                    val c = cos(a); val s = sin(a)
                    val px = vx - x; val py = vy - y
                    verts[i] = x + px * c - py * s; verts[i + 1] = y + px * s + py * c
                }
                LiquifyMode.PINCH -> { verts[i] += (x - vx) * 0.06f * w; verts[i + 1] += (y - vy) * 0.06f * w }
                LiquifyMode.EXPAND -> {
                    val f = 0.06f * w * t
                    verts[i] -= (x - vx) * f; verts[i + 1] -= (y - vy) * f
                }
                LiquifyMode.CRYSTALS -> {
                    val ang = jitter[k] * 3.14159f
                    val px = vx - x; val py = vy - y
                    val l = sqrt(px * px + py * py).coerceAtLeast(1f)
                    val f = 0.05f * w * r
                    verts[i] += (px / l * cos(ang) - py / l * sin(ang)) * f * 0.2f
                    verts[i + 1] += (px / l * sin(ang) + py / l * cos(ang)) * f * 0.2f
                }
                LiquifyMode.EDGE -> {
                    if (dl > 0.01f) {
                        // pull towards the line through (x,y) along the stroke direction
                        val ux = dx / dl; val uy = dy / dl
                        val px = vx - x; val py = vy - y
                        val along = px * ux + py * uy
                        val tx = x + ux * along; val ty = y + uy * along
                        verts[i] += (tx - vx) * 0.08f * w; verts[i + 1] += (ty - vy) * 0.08f * w
                    }
                }
                LiquifyMode.RECONSTRUCT -> {
                    verts[i] += (orig[i] - vx) * 0.15f * w; verts[i + 1] += (orig[i + 1] - vy) * 0.15f * w
                }
            }
        }
        changed = true
    }

    fun draw(canvas: Canvas, paint: Paint?) {
        canvas.drawBitmapMesh(source, cols, rows, verts, 0, null, 0, paint)
    }

    /** Renders the final result into the layer bitmap. */
    fun render(): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        draw(c, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
