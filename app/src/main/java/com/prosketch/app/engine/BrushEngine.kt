package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** A raw input sample in document coordinates. tilt = radians from perpendicular, orientation = azimuth radians. */
data class InputPoint(
    val x: Float, val y: Float, val pressure: Float,
    val tilt: Float = 0f, val orientation: Float = 0f, val time: Long = 0L,
)

/** One stamp of the brush tip. */
class Dab(
    val x: Float, val y: Float, var size: Float, var alpha: Float, val angle: Float,
    val roundness: Float, val color: Int, val flipX: Boolean, val flipY: Boolean,
    val dist: Float,
)

interface DabTarget {
    fun drawDab(bounds: RectF, draw: (Canvas) -> Unit)
    fun clear()
}

/** Sparse stroke buffer split into tiles, so only touched tiles get uploaded to the GPU. */
class TiledBuffer(val width: Int, val height: Int, val tileSize: Int = 256) : DabTarget {
    val cols = (width + tileSize - 1) / tileSize
    val rows = (height + tileSize - 1) / tileSize
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val canvases = arrayOfNulls<Canvas>(cols * rows)
    private val used = BooleanArray(cols * rows)
    val dirty = RectF()
    var isEmpty = true
        private set

    override fun drawDab(bounds: RectF, draw: (Canvas) -> Unit) {
        if (bounds.right < 0 || bounds.bottom < 0 || bounds.left >= width || bounds.top >= height) return
        val c0 = (max(0f, bounds.left) / tileSize).toInt()
        val c1 = (min(width - 1f, bounds.right) / tileSize).toInt()
        val r0 = (max(0f, bounds.top) / tileSize).toInt()
        val r1 = (min(height - 1f, bounds.bottom) / tileSize).toInt()
        for (r in r0..r1) for (c in c0..c1) {
            val i = r * cols + c
            var cv = canvases[i]
            if (cv == null) {
                val b = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                tiles[i] = b
                cv = Canvas(b)
                canvases[i] = cv
            }
            used[i] = true
            cv.save()
            cv.translate(-(c * tileSize).toFloat(), -(r * tileSize).toFloat())
            draw(cv)
            cv.restore()
        }
        if (isEmpty) { dirty.set(bounds); isEmpty = false } else dirty.union(bounds)
    }

    override fun clear() {
        for (i in tiles.indices) if (used[i]) { tiles[i]?.eraseColor(0); used[i] = false }
        dirty.setEmpty()
        isEmpty = true
    }

    /** Draws all used tiles at document coordinates. */
    fun drawTo(canvas: Canvas, paint: Paint?) {
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            if (used[i]) canvas.drawBitmap(tiles[i]!!, (c * tileSize).toFloat(), (r * tileSize).toFloat(), paint)
        }
    }

    fun dirtyRect(pad: Int = 2): Rect? {
        if (isEmpty) return null
        val r = Rect(
            (dirty.left - pad).toInt().coerceAtLeast(0), (dirty.top - pad).toInt().coerceAtLeast(0),
            ceil(dirty.right + pad).toInt().coerceAtMost(width), ceil(dirty.bottom + pad).toInt().coerceAtMost(height),
        )
        return if (r.isEmpty) null else r
    }

    fun release() {
        for (i in tiles.indices) { tiles[i]?.recycle(); tiles[i] = null; canvases[i] = null; used[i] = false }
        isEmpty = true
    }
}

/** Simple full-bitmap target, used for brush previews. */
class BitmapDabTarget(val bitmap: Bitmap) : DabTarget {
    private val canvas = Canvas(bitmap)
    override fun drawDab(bounds: RectF, draw: (Canvas) -> Unit) = draw(canvas)
    override fun clear() = bitmap.eraseColor(0)
}

/**
 * StreamLine (time-based exponential smoothing) + Stabilization (moving average).
 * Smoothing strength is expressed in screen space so it feels the same at every zoom.
 */
class Stabilizer(streamline: Float, stabilization: Float, private val screenToDoc: Float) {
    private val tau = 140f * streamline.coerceIn(0f, 1f).pow(1.6f)    // ms
    private val window = 1 + (stabilization.coerceIn(0f, 1f) * 14).toInt()
    private var sx = 0f; private var sy = 0f; private var sp = 0f
    private var lastT = 0L
    private var started = false
    private val hist = ArrayDeque<InputPoint>()
    private var last: InputPoint? = null

    fun push(p: InputPoint): InputPoint {
        last = p
        if (!started) {
            started = true; sx = p.x; sy = p.y; sp = p.pressure; lastT = p.time
            return p
        }
        val dt = (p.time - lastT).coerceIn(1L, 50L).toFloat()
        lastT = p.time
        if (tau > 0.5f) {
            val k = 1f - exp(-dt / tau)
            sx += (p.x - sx) * k; sy += (p.y - sy) * k
        } else { sx = p.x; sy = p.y }
        sp += (p.pressure - sp) * min(1f, 0.55f + 0.45f * (1f - tau / 140f))
        val e = InputPoint(sx, sy, sp, p.tilt, p.orientation, p.time)
        if (window <= 1) return e
        hist.addLast(e)
        while (hist.size > window) hist.removeFirst()
        var ax = 0f; var ay = 0f
        for (h in hist) { ax += h.x; ay += h.y }
        return e.copy(x = ax / hist.size, y = ay / hist.size)
    }

    /** Catch-up points from the smoothed position to the final input point. */
    fun finish(): List<InputPoint> {
        val l = last ?: return emptyList()
        val cur = if (hist.isNotEmpty()) {
            var ax = 0f; var ay = 0f
            for (h in hist) { ax += h.x; ay += h.y }
            PointF(ax / hist.size, ay / hist.size)
        } else PointF(sx, sy)
        val d = hypot(l.x - cur.x, l.y - cur.y)
        if (d < 0.5f || (tau <= 0.5f && window <= 1)) return emptyList()
        val n = max(2, (d / 3f).toInt()).coerceAtMost(40)
        return (1..n).map { i ->
            val t = i / n.toFloat()
            InputPoint(cur.x + (l.x - cur.x) * t, cur.y + (l.y - cur.y) * t, sp + (l.pressure - sp) * t, l.tilt, l.orientation, l.time)
        }
    }
}

enum class StrokeKind { PAINT, ERASE }

/**
 * Turns a stream of input points into dabs and renders them into a [DabTarget].
 * All stroke state (taper, wet mix colour, jitter) lives here.
 */
class BrushStroke(
    val brush: Brush,
    val kind: StrokeKind,
    private val baseColor: Int,
    private val baseSize: Float,           // document pixels
    val baseOpacity: Float,
    private val target: DabTarget,
    private val symmetry: List<Matrix>,
    private val stylus: Boolean,
    private val screenToDoc: Float,
    private val sampler: ((Float, Float) -> Int)? = null,   // wet-mix colour sampler (unpremultiplied ARGB)
    seed: Int = Random.nextInt(),
) {
    private val rnd = Random(seed)
    private val tips = BrushTextures.tip(brush)
    private val grain: Bitmap? = BrushTextures.grain(brush)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mtx = Matrix()
    private val tmpRect = RectF()
    private val stabilizer = Stabilizer(brush.streamline, brush.stabilization, screenToDoc)
    val dabs = ArrayList<Dab>(1024)

    private var strokeColor = jitterStrokeColor(baseColor)
    private var wetColor = strokeColor
    private var prev: InputPoint? = null
    private var p0: InputPoint? = null
    private var p1: InputPoint? = null
    private var distance = 0f
    private var nextDabAt = 0f
    private var lastDirAngle = 0f
    private var lastSpeed = 0f
    private var curvePrev: InputPoint? = null
    private var minSpacing = 0f
    private var useTaper = stylus || brush.taperFinger
    private val taperStartLen = brush.taperStart * (baseSize * 5f + 120f)
    private val taperEndLen = brush.taperEnd * (baseSize * 5f + 120f)

    /** Opacity used when compositing the stroke buffer to the layer. */
    val compositeOpacity: Float get() = if (brush.glaze) baseOpacity else 1f

    fun begin(p: InputPoint) {
        val s = stabilizer.push(p)
        prev = s; p0 = s; p1 = s; curvePrev = s
        emitAlong(s, s, true)
    }

    fun add(p: InputPoint) {
        val s = stabilizer.push(p)
        feedSmoothed(s)
    }

    private fun feedSmoothed(s: InputPoint) {
        val a = p0 ?: return
        val b = p1 ?: return
        if (hypot(s.x - b.x, s.y - b.y) < 0.35f) return
        // quadratic curve through midpoints, control point = previous sample
        val m0 = mid(a, b)
        val m1 = mid(b, s)
        val len = hypot(b.x - m0.x, b.y - m0.y) + hypot(m1.x - b.x, m1.y - b.y)
        val steps = max(1, (len / 1.5f).toInt()).coerceAtMost(256)
        var last = curvePrev ?: m0
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val it = 1 - t
            val x = it * it * m0.x + 2 * it * t * b.x + t * t * m1.x
            val y = it * it * m0.y + 2 * it * t * b.y + t * t * m1.y
            val q = InputPoint(x, y, lerp(m0.pressure, m1.pressure, t), lerp(m0.tilt, m1.tilt, t),
                lerpAngle(m0.orientation, m1.orientation, t), (m0.time + (m1.time - m0.time) * t).toLong())
            emitAlong(last, q, false)
            last = q
        }
        curvePrev = last
        p0 = b; p1 = s
    }

    fun end() {
        for (q in stabilizer.finish()) feedSmoothed(q)
        val b = p1
        val c = curvePrev
        if (b != null && c != null) emitAlong(c, b, false)
        if (useTaper && taperEndLen > 0f && dabs.size > 2) applyEndTaper()
    }

    /**
     * Re-renders the stroke along a fixed path (QuickShape). [maxStamps] caps the work for a live
     * preview by spreading dabs further apart; pass no cap for the final render.
     */
    fun replacePath(points: List<PointF>, pressure: Float, maxStamps: Int = Int.MAX_VALUE) {
        useTaper = false   // geometric shapes are drawn with an even line
        target.clear()
        dabs.clear()
        distance = 0f; nextDabAt = 0f
        if (points.isEmpty()) return
        minSpacing = if (maxStamps == Int.MAX_VALUE) 0f else {
            var len = 0f
            for (i in 1 until points.size) len += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            len * symmetry.size * max(1, brush.count) / maxStamps
        }
        val t = System.currentTimeMillis()
        var last = InputPoint(points[0].x, points[0].y, pressure, 0f, 0f, t)
        emitAlong(last, last, true)
        for (i in 1 until points.size) {
            val q = InputPoint(points[i].x, points[i].y, pressure, 0f, 0f, t + i)
            emitAlong(last, q, false)
            last = q
        }
    }

    private fun applyEndTaper() {
        val total = dabs.last().dist
        var changed = false
        for (d in dabs) {
            val fromEnd = total - d.dist
            if (fromEnd < taperEndLen) {
                val t = smooth(fromEnd / taperEndLen)
                d.size *= lerp(brush.taperSize.coerceAtLeast(0.02f), 1f, t)
                d.alpha *= lerp(1f - brush.taperOpacity, 1f, t)
                changed = true
            }
        }
        if (changed) {
            target.clear()
            for (d in dabs) render(d)
        }
    }

    private fun emitAlong(a: InputPoint, b: InputPoint, first: Boolean) {
        val segLen = hypot(b.x - a.x, b.y - a.y)
        if (!first && segLen <= 0f) return
        if (segLen > 0.01f) {
            lastDirAngle = atan2(b.y - a.y, b.x - a.x)
            val dt = (b.time - a.time).coerceAtLeast(1L)
            val sp = segLen / dt / screenToDoc.coerceAtLeast(0.01f)
            lastSpeed += (sp - lastSpeed) * 0.15f
        }
        if (first) {
            placeDab(a, 0f)
            nextDabAt = spacingFor(a)
            return
        }
        var walked = 0f
        while (distance + segLen - walked >= nextDabAt) {
            val need = nextDabAt - distance
            walked += need
            distance = nextDabAt
            val t = (walked / segLen).coerceIn(0f, 1f)
            val q = InputPoint(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.pressure, b.pressure, t),
                lerp(a.tilt, b.tilt, t), lerpAngle(a.orientation, b.orientation, t), a.time)
            placeDab(q, distance)
            nextDabAt = distance + spacingFor(q)
            if (dabs.size > 400_000) return
        }
        distance += segLen - walked
    }

    private fun pressureOf(p: InputPoint) = if (stylus) p.pressure.coerceIn(0f, 1f) else 1f

    private fun sizeAt(p: InputPoint): Float {
        val pr = pressureOf(p)
        var s = baseSize * (1f - brush.pressureSize * (1f - pr))
        val tf = tiltFactor(p)
        s *= 1f + brush.tiltSize * tf * 1.5f
        val sf = (lastSpeed / 2.5f).coerceIn(0f, 1f)
        s *= max(0.08f, 1f + brush.speedSize * sf)
        return s.coerceAtLeast(0.3f)
    }

    private fun spacingFor(p: InputPoint): Float {
        val sp = max(max(0.35f, minSpacing), brush.spacing * sizeAt(p))
        return if (brush.spacingJitter > 0f) sp * (1f + brush.spacingJitter * (rnd.nextFloat() * 2f - 0.5f)) else sp
    }

    private fun tiltFactor(p: InputPoint): Float {
        if (!stylus) return 0f
        val deg = Math.toDegrees(p.tilt.toDouble()).toFloat()
        val trig = brush.tiltAngleDeg.coerceIn(0f, 85f)
        return ((deg - trig) / (90f - trig)).coerceIn(0f, 1f)
    }

    private fun placeDab(p: InputPoint, dist: Float) {
        val pr = pressureOf(p)
        var size = sizeAt(p)
        var opac = 1f - brush.pressureOpacity * (1f - pr)
        var flow = brush.flow * (1f - brush.pressureFlow * (1f - pr))
        val tf = tiltFactor(p)
        opac *= 1f - brush.tiltOpacity * tf * 0.85f
        val sf = (lastSpeed / 2.5f).coerceIn(0f, 1f)
        opac *= max(0.05f, 1f + brush.speedOpacity * sf)
        if (brush.jitterSize > 0f) size *= 1f - brush.jitterSize * rnd.nextFloat() * 0.9f
        if (brush.jitterOpacity > 0f) opac *= 1f - brush.jitterOpacity * rnd.nextFloat()
        if (brush.fallOff > 0f) opac *= max(0f, 1f - brush.fallOff * dist / (baseSize * 25f + 600f))
        if (useTaper && taperStartLen > 0f && dist < taperStartLen) {
            val t = smooth(dist / taperStartLen)
            size *= lerp(brush.taperSize.coerceAtLeast(0.02f), 1f, t)
            opac *= lerp(1f - brush.taperOpacity, 1f, t)
        }
        if (brush.dilution > 0f) opac *= 1f - brush.dilution * 0.75f
        if (!brush.glaze) opac *= baseOpacity

        // Convert per-pixel target opacity into a per-dab alpha, accounting for dab overlap so that
        // pressure→opacity stays meaningful even with tight spacing.
        val spacingPx = max(0.35f, brush.spacing * size)
        val overlap = (size / spacingPx * 0.55f).coerceIn(1f, 60f)
        val targetA = (opac * flow).coerceIn(0f, 1f)
        val dabA = if (brush.pressureOpacity > 0f || brush.tiltOpacity > 0f || brush.fallOff > 0f || useTaper && brush.taperOpacity > 0f)
            1f - (1f - targetA).pow(1f / overlap) else targetA

        var angle = brush.tipAngle
        if (brush.rotation != 0f) angle += Math.toDegrees(lastDirAngle.toDouble()).toFloat() * brush.rotation
        if (brush.azimuth && stylus) angle += Math.toDegrees(p.orientation.toDouble()).toFloat()
        if (brush.scatter > 0f) angle += (rnd.nextFloat() * 2f - 1f) * 180f * brush.scatter
        var round = brush.roundness
        round *= 1f - brush.pressureRoundness * (1f - pr) * 0.9f
        round *= 1f - brush.tiltRoundness * tf * 0.85f
        round = round.coerceIn(0.03f, 1f)

        var color = if (kind == StrokeKind.ERASE) Color.BLACK else strokeColor
        if (kind == StrokeKind.PAINT && brush.pull > 0f && sampler != null) {
            val s = sampler.invoke(p.x, p.y)
            val sa = Color.alpha(s) / 255f
            if (sa > 0.02f) wetColor = mixColor(wetColor, s or (0xFF shl 24), brush.pull * 0.35f * sa)
            wetColor = mixColor(wetColor, strokeColor, brush.charge * 0.06f)
            color = wetColor
        }
        if (kind == StrokeKind.PAINT && (brush.stampHue > 0f || brush.stampSat > 0f || brush.stampBri > 0f)) color = jitterStamp(color)

        val count = if (brush.count <= 1) 1 else {
            val jitter = (brush.countJitter * rnd.nextFloat() * brush.count).toInt()
            max(1, brush.count - jitter)
        }
        for (k in 0 until count) {
            var x = p.x; var y = p.y
            if (brush.jitterLateral > 0f || count > 1) {
                val off = (rnd.nextFloat() * 2f - 1f) * brush.jitterLateral * size * (if (count > 1) 1.5f else 1f)
                x += -kotlin.math.sin(lastDirAngle) * off
                y += kotlin.math.cos(lastDirAngle) * off
            }
            if (brush.jitterLinear > 0f) {
                val off = (rnd.nextFloat() * 2f - 1f) * brush.jitterLinear * size
                x += kotlin.math.cos(lastDirAngle) * off
                y += kotlin.math.sin(lastDirAngle) * off
            }
            val fx = brush.flipX || (brush.randomized && rnd.nextBoolean())
            val fy = brush.flipY || (brush.randomized && rnd.nextBoolean())
            val d = Dab(x, y, size, dabA, angle + (if (k > 0) rnd.nextFloat() * 360f * brush.scatter else 0f), round, color, fx, fy, dist)
            dabs.add(d)
            render(d)
        }
    }

    private fun render(d: Dab) {
        if (d.alpha <= 0.001f || d.size <= 0.05f) return
        val tip = tips.forSize(d.size)
        val tw = tip.width.toFloat()
        val sx = d.size / tw * (if (d.flipX) -1f else 1f)
        val sy = d.size * d.roundness / tw * (if (d.flipY) -1f else 1f)
        val small = d.size < 1.2f
        val a = if (small) d.alpha * (d.size / 1.2f) else d.alpha
        paint.color = d.color
        paint.alpha = (a * 255f).toInt().coerceIn(1, 255)
        paint.shader = grain?.let { grainShaderFor(d.color) }
        for (sym in symmetry) {
            mtx.reset()
            mtx.postTranslate(-tw / 2f, -tw / 2f)
            mtx.postScale(if (small) 1.2f / tw else sx, if (small) 1.2f * d.roundness / tw else sy)
            mtx.postRotate(d.angle)
            mtx.postTranslate(d.x, d.y)
            mtx.postConcat(sym)
            tmpRect.set(0f, 0f, tw, tw)
            mtx.mapRect(tmpRect)
            target.drawDab(tmpRect) { c -> c.drawBitmap(tip, mtx, paint) }
        }
    }

    private var cachedShaderColor = 0
    private var cachedShader: Shader? = null

    /** Opaque colour × grain alpha; the paint alpha then scales the whole dab. */
    private fun grainShaderFor(color: Int): Shader {
        if (cachedShader != null && cachedShaderColor == color) return cachedShader!!
        val c = color or (0xFF shl 24)
        val solid = LinearGradient(0f, 0f, 1f, 0f, c, c, Shader.TileMode.CLAMP)
        val gs = BitmapShader(grain!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(brush.grainScale, brush.grainScale) })
        }
        val sh = ComposeShader(solid, gs, BlendMode.DST_IN)
        cachedShader = sh; cachedShaderColor = color
        return sh
    }

    private fun jitterStrokeColor(c: Int): Int {
        if (kind == StrokeKind.ERASE || (brush.strokeHue <= 0f && brush.strokeSat <= 0f && brush.strokeBri <= 0f)) return c
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = ((hsv[0] + (rnd.nextFloat() * 2 - 1) * 180f * brush.strokeHue) % 360f + 360f) % 360f
        if (brush.strokeHue > 0f && hsv[1] < 0.05f) hsv[1] = 0.8f
        hsv[1] = (hsv[1] + (rnd.nextFloat() * 2 - 1) * brush.strokeSat).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + (rnd.nextFloat() * 2 - 1) * brush.strokeBri).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    private val hsvTmp = FloatArray(3)
    private fun jitterStamp(c: Int): Int {
        Color.colorToHSV(c, hsvTmp)
        hsvTmp[0] = ((hsvTmp[0] + (rnd.nextFloat() * 2 - 1) * 180f * brush.stampHue) % 360f + 360f) % 360f
        hsvTmp[1] = (hsvTmp[1] + (rnd.nextFloat() * 2 - 1) * brush.stampSat).coerceIn(0f, 1f)
        hsvTmp[2] = (hsvTmp[2] + (rnd.nextFloat() * 2 - 1) * brush.stampBri).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(c), hsvTmp)
    }

    companion object {
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        fun lerpAngle(a: Float, b: Float, t: Float): Float {
            var d = b - a
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return a + d * t
        }
        fun mid(a: InputPoint, b: InputPoint) = InputPoint(
            (a.x + b.x) / 2, (a.y + b.y) / 2, (a.pressure + b.pressure) / 2,
            (a.tilt + b.tilt) / 2, lerpAngle(a.orientation, b.orientation, 0.5f), (a.time + b.time) / 2,
        )
        fun mixColor(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
        }
    }
}

/**
 * Smudge: drags pixels of the layer along the stroke using a colour "carrier".
 * Operates directly on the layer bitmap in premultiplied integer math.
 */
class SmudgeStroke(
    private val brush: Brush,
    private val layer: Bitmap,
    private val baseSize: Float,
    private val strength: Float,
    private val stylus: Boolean,
    private val screenToDoc: Float,
    private val selection: Bitmap?,
    private val alphaLock: Boolean,
) {
    private val stabilizer = Stabilizer(brush.streamline, brush.stabilization, screenToDoc)
    private var carrier: IntArray? = null   // premultiplied ARGB
    private var carrierSize = 0
    private var last: InputPoint? = null
    private var distAcc = 0f
    val dirty = Rect()
    private var hasDirty = false
    private var buf = IntArray(0)
    private var selBuf = ByteArray(0)

    fun begin(p: InputPoint) { val s = stabilizer.push(p); last = s; dab(s) }

    fun add(p: InputPoint) {
        val s = stabilizer.push(p)
        walk(s)
    }

    fun end() { for (q in stabilizer.finish()) walk(q) }

    private fun walk(s: InputPoint) {
        val a = last ?: run { last = s; return }
        val len = hypot(s.x - a.x, s.y - a.y)
        if (len < 0.3f) return
        val size = sizeAt(s)
        val step = max(1f, size * max(0.05f, brush.spacing * 0.6f))
        var t = (step - distAcc)
        while (t <= len) {
            val k = t / len
            dab(InputPoint(a.x + (s.x - a.x) * k, a.y + (s.y - a.y) * k, a.pressure + (s.pressure - a.pressure) * k, s.tilt, s.orientation, s.time))
            t += step
        }
        distAcc = len - (t - step)
        last = s
    }

    private fun sizeAt(p: InputPoint): Float {
        val pr = if (stylus) p.pressure else 1f
        return (baseSize * (1f - brush.pressureSize * (1f - pr))).coerceAtLeast(2f)
    }

    private fun dab(p: InputPoint) {
        val size = sizeAt(p)
        val n = (size.toInt() or 1).coerceIn(3, 767)
        val pr = if (stylus) p.pressure else 1f
        val str = (strength * (0.35f + 0.65f * pr)).coerceIn(0f, 1f)
        val left = (p.x - n / 2f).toInt(); val top = (p.y - n / 2f).toInt()
        val x0 = max(0, left); val y0 = max(0, top)
        val x1 = min(layer.width, left + n); val y1 = min(layer.height, top + n)
        if (x1 <= x0 || y1 <= y0) return
        val w = x1 - x0; val h = y1 - y0
        if (buf.size < w * h) buf = IntArray(n * n)
        layer.getPixels(buf, 0, w, x0, y0, w, h)
        if (selection != null) {
            if (selBuf.size < w * h) selBuf = ByteArray(n * n)
            readMask(selection, x0, y0, w, h, selBuf)
        }
        val mask = BrushTextures.tipMask(brush, n)
        var car = carrier
        if (car == null || carrierSize != n) {
            // (re)load carrier from the canvas under the dab
            val nc = IntArray(n * n)
            for (yy in 0 until h) for (xx in 0 until w) {
                nc[(yy + y0 - top) * n + (xx + x0 - left)] = premul(buf[yy * w + xx])
            }
            if (car != null) {
                // resample old carrier to the new size (nearest) to keep colour continuity
                for (yy in 0 until n) for (xx in 0 until n) {
                    val ox = xx * carrierSize / n; val oy = yy * carrierSize / n
                    nc[yy * n + xx] = car[oy * carrierSize + ox]
                }
            }
            carrier = nc; carrierSize = n; car = nc
        }
        val pull = brush.smudgePull.coerceIn(0f, 1f)
        for (yy in 0 until h) {
            val cy = yy + y0 - top
            for (xx in 0 until w) {
                val cx = xx + x0 - left
                val m = (mask[cy * n + cx].toInt() and 0xFF) / 255f
                if (m <= 0.003f) continue
                var amt = m * str
                if (selection != null) amt *= (selBuf[yy * w + xx].toInt() and 0xFF) / 255f
                if (amt <= 0f) continue
                val i = yy * w + xx
                val dst = premul(buf[i])
                val c = car[cy * n + cx]
                var out = lerpPremul(dst, c, amt)
                if (alphaLock) out = withAlphaOf(out, dst)
                buf[i] = unpremul(out)
                // carrier picks up paint from the canvas
                car[cy * n + cx] = lerpPremul(c, out, (1f - pull) * m)
            }
        }
        layer.setPixels(buf, 0, w, x0, y0, w, h)
        if (!hasDirty) { dirty.set(x0, y0, x1, y1); hasDirty = true } else dirty.union(x0, y0, x1, y1)
    }

    fun dirtyRect(): Rect? = if (hasDirty) Rect(dirty) else null

    companion object {
        fun premul(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val r = ((c shr 16) and 0xFF) * a / 255
            val g = ((c shr 8) and 0xFF) * a / 255
            val b = (c and 0xFF) * a / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        fun unpremul(c: Int): Int {
            val a = c ushr 24
            if (a == 255 || a == 0) return if (a == 0) 0 else c
            val r = min(255, ((c shr 16) and 0xFF) * 255 / a)
            val g = min(255, ((c shr 8) and 0xFF) * 255 / a)
            val b = min(255, (c and 0xFF) * 255 / a)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        fun lerpPremul(a: Int, b: Int, t: Float): Int {
            val it = 1f - t
            val aa = ((a ushr 24) * it + (b ushr 24) * t + 0.5f).toInt()
            val r = (((a shr 16) and 0xFF) * it + ((b shr 16) and 0xFF) * t + 0.5f).toInt()
            val g = (((a shr 8) and 0xFF) * it + ((b shr 8) and 0xFF) * t + 0.5f).toInt()
            val bb = ((a and 0xFF) * it + (b and 0xFF) * t + 0.5f).toInt()
            return (aa shl 24) or (min(r, aa) shl 16) or (min(g, aa) shl 8) or min(bb, aa)
        }
        fun withAlphaOf(c: Int, ref: Int): Int {
            val ca = c ushr 24; val ra = ref ushr 24
            if (ca == 0) return 0
            if (ca == ra) return c
            val k = ra.toFloat() / ca
            val r = min(ra, (((c shr 16) and 0xFF) * k).toInt())
            val g = min(ra, (((c shr 8) and 0xFF) * k).toInt())
            val b = min(ra, ((c and 0xFF) * k).toInt())
            return (ra shl 24) or (r shl 16) or (g shl 8) or b
        }
        fun readMask(mask: Bitmap, x: Int, y: Int, w: Int, h: Int, out: ByteArray) {
            val tmp = Bitmaps.crop(mask, Rect(x, y, x + w, y + h))
            // crop() returns ARGB; alpha channel carries the mask value
            val px = IntArray(w * h)
            tmp.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until w * h) out[i] = (px[i] ushr 24).toByte()
            tmp.recycle()
        }
    }
}

/** Utility shared by tools that need a quick angle in degrees. */
fun angleDeg(dx: Float, dy: Float) = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
fun absf(v: Float) = abs(v)
