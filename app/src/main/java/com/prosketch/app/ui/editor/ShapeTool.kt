package com.prosketch.app.ui.editor

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prosketch.app.engine.Bitmaps
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.PixelEntry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Base for tools that place one editable vector item (two control points) over the active layer and
 * rasterise it on commit. Live preview goes through the render hook, so blend mode, opacity, clipping and
 * masks of the layer all apply while editing.
 */
abstract class PlacementTool(protected val ed: Editor) {
    var version by mutableIntStateOf(0)
    var layer: Layer? = null
        protected set
    /** Control points in document pixels; null until the first drag. */
    var a: PointF? = null
        protected set
    var b: PointF? = null
        protected set

    protected abstract val mode: Mode
    protected abstract val label: String
    /** Draws the item onto [c] (document coordinates). */
    protected abstract fun drawItem(c: Canvas)
    /** Area the item touches, in document pixels. */
    abstract fun bounds(): RectF

    fun begin(): Boolean {
        ed.exitModes()
        if (!ed.canPaint()) return false
        layer = ed.doc.active
        a = null; b = null
        ed.panel = null
        ed.mode = mode
        version++
        ed.invalidate()
        return true
    }

    fun hasItem() = a != null && b != null

    fun setPoints(p: PointF, q: PointF) { a = p; b = q; changed() }
    fun moveA(p: PointF) { a = p; changed() }
    fun moveB(p: PointF) { b = p; changed() }
    fun moveBy(dx: Float, dy: Float) {
        val p = a ?: return; val q = b ?: return
        a = PointF(p.x + dx, p.y + dy); b = PointF(q.x + dx, q.y + dy); changed()
    }

    fun changed() { version++; ed.invalidate() }

    /** Replaces the layer's drawing while editing: layer + item composited exactly as on commit. */
    fun drawLive(canvas: Canvas, l: Layer, paint: Paint?): Boolean {
        if (ed.mode != mode || l !== layer || !hasItem()) return false
        val full = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        canvas.saveLayer(full, paint)
        canvas.drawBitmap(l.bitmap, 0f, 0f, null)
        composite(canvas, l)
        canvas.restore()
        return true
    }

    private val dstIn = Paint().apply { blendMode = BlendMode.DST_IN }

    private fun composite(c: Canvas, l: Layer) {
        val full = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        c.saveLayer(full, Paint().apply { blendMode = if (l.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER })
        drawItem(c)
        ed.selection?.let { c.drawBitmap(it, 0f, 0f, dstIn) }
        c.restore()
    }

    /** Rasterises the item into the layer (one undo step) and keeps the tool open for the next one. */
    fun commit() {
        val l = layer ?: return
        if (!hasItem()) return
        val bf = bounds()
        val r = Rect(floor(bf.left).toInt() - 2, floor(bf.top).toInt() - 2, ceil(bf.right).toInt() + 2, ceil(bf.bottom).toInt() + 2)
        if (!r.intersect(0, 0, ed.doc.width, ed.doc.height)) { a = null; b = null; changed(); return }
        ed.safely(label) {
            val before = Bitmaps.crop(l.bitmap, r)
            val c = Canvas(l.bitmap)
            c.clipRect(r)
            composite(c, l)
            ed.history.push(PixelEntry(label, l, r, before, Bitmaps.crop(l.bitmap, r)))
            l.touch()
            ed.doc.strokeCount++
            ed.afterCommit()
        }
        a = null; b = null
        changed()
    }

    fun cancel() { a = null; b = null; changed() }

    /** Leaving the mode commits what is on screen, like the other tools. */
    fun finish() {
        commit()
        layer = null
        if (ed.mode == mode) ed.mode = Mode.PAINT
        version++
        ed.invalidate()
    }

    /** Masks are greyscale: use the colour's luminance. */
    protected fun colorFor(c: Int): Int {
        if (layer?.isMask != true) return c
        val y = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), y, y, y)
    }
}

enum class ShapeKind(val label: String) {
    RECT(L.t("직사각형")), ELLIPSE(L.t("타원")), LINE(L.t("직선")), POLYGON(L.t("다각형")), STAR(L.t("별"))
}

enum class ShapeStyle(val label: String) { STROKE(L.t("선")), FILL(L.t("채우기")), BOTH(L.t("선+채우기")) }

class ShapeTool(ed: Editor) : PlacementTool(ed) {
    override val mode = Mode.SHAPE
    override val label get() = L.tf("도형: {0}", kind.label)

    var kind by mutableStateOf(ShapeKind.RECT)
    var style by mutableStateOf(ShapeStyle.STROKE)
    /** Line width as a fraction of the canvas' long side (0.1 %..10 %), so it means the same on any canvas. */
    var width by mutableFloatStateOf(0.006f)
    var corner by mutableFloatStateOf(0f)      // rect corner radius, 0..1 of the short half-side
    var sides by mutableIntStateOf(5)
    var constrain by mutableStateOf(false)     // square / circle / 15° lines
    var fromCenter by mutableStateOf(false)

    fun strokePx() = width * max(ed.doc.width, ed.doc.height)

    /** Box (or line end points) after the square / centre options. */
    private fun geometry(): Pair<PointF, PointF>? {
        val p = a ?: return null; var q = b ?: return null
        if (constrain) {
            val dx = q.x - p.x; val dy = q.y - p.y
            q = if (kind == ShapeKind.LINE) {
                val len = hypot(dx, dy)
                val step = (PI / 12).toFloat()
                val ang = Math.round(kotlin.math.atan2(dy, dx) / step) * step
                PointF(p.x + cos(ang) * len, p.y + sin(ang) * len)
            } else {
                val m = max(abs(dx), abs(dy))
                PointF(p.x + m * (if (dx < 0) -1f else 1f), p.y + m * (if (dy < 0) -1f else 1f))
            }
        }
        if (fromCenter && kind != ShapeKind.LINE) return PointF(2 * p.x - q.x, 2 * p.y - q.y) to q
        return p to q
    }

    fun path(): Path? {
        val (p, q) = geometry() ?: return null
        val path = Path()
        val r = RectF(min(p.x, q.x), min(p.y, q.y), max(p.x, q.x), max(p.y, q.y))
        when (kind) {
            ShapeKind.RECT -> {
                val rad = corner * min(r.width(), r.height()) / 2f
                if (rad > 0.5f) path.addRoundRect(r, rad, rad, Path.Direction.CW) else path.addRect(r, Path.Direction.CW)
            }
            ShapeKind.ELLIPSE -> path.addOval(r, Path.Direction.CW)
            ShapeKind.LINE -> { path.moveTo(p.x, p.y); path.lineTo(q.x, q.y) }
            ShapeKind.POLYGON, ShapeKind.STAR -> {
                val n = sides.coerceIn(3, 24)
                val cx = r.centerX(); val cy = r.centerY(); val rx = r.width() / 2f; val ry = r.height() / 2f
                val pts = if (kind == ShapeKind.STAR) n * 2 else n
                for (i in 0 until pts) {
                    val t = -PI.toFloat() / 2f + 2f * PI.toFloat() * i / pts
                    val k = if (kind == ShapeKind.STAR && i % 2 == 1) 0.45f else 1f
                    val x = cx + cos(t) * rx * k; val y = cy + sin(t) * ry * k
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
        return path
    }

    override fun drawItem(c: Canvas) {
        val path = path() ?: return
        val fill = kind != ShapeKind.LINE && style != ShapeStyle.STROKE
        val stroke = kind == ShapeKind.LINE || style != ShapeStyle.FILL
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (fill) {
            p.style = Paint.Style.FILL
            p.color = colorFor(if (style == ShapeStyle.BOTH) ed.previousColor else ed.color)
            c.drawPath(path, p)
        }
        if (stroke) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokePx()
            p.strokeJoin = if (kind == ShapeKind.RECT && corner == 0f) Paint.Join.MITER else Paint.Join.ROUND
            p.strokeCap = Paint.Cap.ROUND
            p.color = colorFor(ed.color)
            c.drawPath(path, p)
        }
    }

    override fun bounds(): RectF {
        val r = RectF()
        path()?.computeBounds(r, true)
        val pad = strokePx() / 2f + 2f
        r.inset(-pad, -pad)
        return r
    }

    /** Screen-space handles: the two control points after constraints. */
    fun handlePoints(): List<PointF> = geometry()?.let { listOf(it.first, it.second) } ?: emptyList()
}

enum class GradientKind(val label: String) { LINEAR(L.t("선형")), RADIAL(L.t("방사형")) }

class GradientTool(ed: Editor) : PlacementTool(ed) {
    override val mode = Mode.GRADIENT
    override val label get() = L.t("그라디언트")

    var kind by mutableStateOf(GradientKind.LINEAR)
    /** End colour: the previous colour, or the current colour fading to transparent. */
    var toTransparent by mutableStateOf(false)
    var reversed by mutableStateOf(false)
    var opacity by mutableFloatStateOf(1f)

    override fun drawItem(c: Canvas) {
        val p = a ?: return; val q = b ?: return
        var c0 = colorFor(ed.color)
        var c1 = if (toTransparent) c0 and 0x00FFFFFF else colorFor(ed.previousColor)
        if (reversed) { val t = c0; c0 = c1; c1 = t }
        val shader = when (kind) {
            GradientKind.LINEAR -> LinearGradient(p.x, p.y, q.x, q.y, c0, c1, Shader.TileMode.CLAMP)
            GradientKind.RADIAL -> RadialGradient(p.x, p.y, hypot(q.x - p.x, q.y - p.y).coerceAtLeast(1f), c0, c1, Shader.TileMode.CLAMP)
        }
        val paint = Paint(Paint.DITHER_FLAG).apply { this.shader = shader; alpha = (opacity * 255).toInt().coerceIn(0, 255) }
        c.drawRect(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat(), paint)
    }

    override fun bounds() = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
}
