package com.prosketch.app.ui.canvas

import com.prosketch.app.L

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.prosketch.app.data.FingerMode
import com.prosketch.app.data.PenButtonAction
import com.prosketch.app.engine.AdjustmentType
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.GuideType
import com.prosketch.app.engine.InputPoint
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.engine.ClipItem
import com.prosketch.app.engine.SelectionMode
import com.prosketch.app.engine.SymmetryType
import com.prosketch.app.engine.TransformMode
import com.prosketch.app.ui.editor.Editor
import com.prosketch.app.ui.editor.Mode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The drawing surface: GPU-composited rendering with cached layer groups, and all
 * pen / finger input (strokes, gestures, tool interactions).
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, val editor: Editor) : View(context) {

    private val doc get() = editor.doc
    private val density = resources.displayMetrics.density

    // ------------------------------------------------------------------ view transform
    val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var fitted = false
    private var previousView: Matrix? = null
    private var animator: ValueAnimator? = null

    val scale: Float get() = viewMatrix.mapRadius(1f).coerceAtLeast(1e-4f)
    val rotationDeg: Float get() {
        val v = floatArrayOf(1f, 0f); viewMatrix.mapVectors(v)
        return Math.toDegrees(atan2(v[1], v[0]).toDouble()).toFloat()
    }

    fun toDoc(x: Float, y: Float): PointF {
        viewMatrix.invert(inverse)
        val p = floatArrayOf(x, y); inverse.mapPoints(p)
        return PointF(p[0], p[1])
    }

    fun toScreen(x: Float, y: Float): PointF {
        val p = floatArrayOf(x, y); viewMatrix.mapPoints(p)
        return PointF(p[0], p[1])
    }

    private fun fitMatrix(): Matrix {
        val margin = 24f * density
        val s = min((width - 2 * margin) / doc.width, (height - 2 * margin) / doc.height).coerceAtLeast(0.01f)
        return Matrix().apply {
            setScale(s, s)
            postTranslate((width - doc.width * s) / 2f, (height - doc.height * s) / 2f)
        }
    }

    fun fitToScreen(animate: Boolean = true) {
        previousView = Matrix(viewMatrix)
        animateTo(fitMatrix(), animate)
        editor.zoomText = null
    }

    private fun animateTo(target: Matrix, animate: Boolean) {
        animator?.cancel()
        if (!animate || width == 0) { viewMatrix.set(target); invalidate(); return }
        val from = decompose(viewMatrix); val to = decompose(target)
        var dr = to[1] - from[1]
        while (dr > 180) dr -= 360f
        while (dr < -180) dr += 360f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = from[0] + (to[0] - from[0]) * t
                val r = from[1] + dr * t
                val cx = from[2] + (to[2] - from[2]) * t
                val cy = from[3] + (to[3] - from[3]) * t
                compose(s, r, cx, cy)
                invalidate()
            }
            start()
        }
    }

    /** [scale, rotation, screen x of doc centre, screen y of doc centre] */
    private fun decompose(m: Matrix): FloatArray {
        val v = floatArrayOf(1f, 0f); m.mapVectors(v)
        val c = floatArrayOf(doc.width / 2f, doc.height / 2f); m.mapPoints(c)
        return floatArrayOf(hypot(v[0], v[1]), Math.toDegrees(atan2(v[1], v[0]).toDouble()).toFloat(), c[0], c[1])
    }

    private fun compose(s: Float, r: Float, cx: Float, cy: Float) {
        viewMatrix.reset()
        viewMatrix.postTranslate(-doc.width / 2f, -doc.height / 2f)
        viewMatrix.postScale(s, s)
        viewMatrix.postRotate(r)
        viewMatrix.postTranslate(cx, cy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0) { viewMatrix.set(fitMatrix()); fitted = true }
        else if (oldw > 0) { viewMatrix.postTranslate((w - oldw) / 2f, (h - oldh) / 2f) }
    }

    init {
        editor.invalidator = { postInvalidateOnAnimation() }
        editor.fitRequest = { post { fitToScreen() } }
        editor.mirrorRequest = { invalidate() }
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------------ rendering
    private val belowNode = RenderNode("below")
    private val aboveNode = RenderNode("above")
    private var belowSig = Long.MIN_VALUE
    private var aboveSig = Long.MIN_VALUE
    private val surroundColor = 0xFF1C1C1E.toInt()
    private val checker: Paint by lazy {
        val c = 12
        val b = Bitmap.createBitmap(c * 2, c * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = 0xFFD6D6D6.toInt() }
        cv.drawRect(0f, 0f, c.toFloat(), c.toFloat(), p); cv.drawRect(c.toFloat(), c.toFloat(), 2f * c, 2f * c, p)
        Paint().apply { shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; setShadowLayer(18f, 0f, 4f, 0x99000000.toInt()) }

    private fun signature(items: List<ClipItem>, includeBg: Boolean, filter: Boolean): Long {
        var h = 1125899906842597L
        h = 31 * h + doc.width; h = 31 * h + doc.height
        if (includeBg) { h = 31 * h + doc.backgroundVersion; h = 31 * h + (if (doc.backgroundVisible) 1 else 0) + doc.backgroundColor }
        h = 31 * h + (if (filter) 1 else 0)
        for (it in items) for (l in doc.itemLayers(it)) {
            h = 31 * h + l.id; h = 31 * h + l.version; h = 31 * h + System.identityHashCode(l.bitmap)
        }
        return h
    }

    private fun record(node: RenderNode, items: List<ClipItem>, includeBg: Boolean, compositing: Boolean, filter: Boolean) {
        node.setPosition(0, 0, doc.width, doc.height)
        node.setUseCompositingLayer(compositing, null)
        val c = node.beginRecording(doc.width, doc.height)
        if (includeBg) Compositor.drawBackground(c, doc)
        Compositor.drawItems(c, doc, items, editor.renderHook, filter)
        node.endRecording()
    }

    /** Documents using CPU-only blend modes are composited in software and cached until something changes. */
    private var softCache: Bitmap? = null
    private var softSig = Long.MIN_VALUE
    private var softTick = 0L

    private fun drawSoftware(canvas: Canvas, items: List<ClipItem>, filter: Boolean) {
        var sig = signature(items, true, filter)
        if (editor.liveActive) sig = 31 * sig + (++softTick)
        if (sig != softSig || softCache == null) {
            softCache = Compositor.flatten(doc, 1f, editor.renderHook, true)
            softSig = sig
        }
        canvas.drawBitmap(softCache!!, 0f, 0f, if (filter) Paint(Paint.FILTER_BITMAP_FLAG) else null)
    }

    private val grayPaint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(surroundColor)
        if (editor.mirrorView) { canvas.save(); canvas.scale(-1f, 1f, width / 2f, 0f) }
        drawContent(canvas)
        if (editor.mirrorView) canvas.restore()
    }

    private fun drawContent(canvas: Canvas) {
        val docRect = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        val filter = scale < 2.5f

        // drop shadow + checkerboard (transparent background)
        val quad = Path().apply { addRect(docRect, Path.Direction.CW); transform(viewMatrix) }
        canvas.drawPath(quad, shadowPaint)
        if (!doc.backgroundVisible) {
            canvas.save(); canvas.clipPath(quad); canvas.drawPaint(checker); canvas.restore()
        }

        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(docRect)
        val isolate = !doc.backgroundVisible || editor.grayscaleView
        if (isolate) canvas.saveLayer(docRect, if (editor.grayscaleView) grayPaint else null)

        val items = doc.items()
        val activeTop = doc.activeLayer?.let { doc.topAncestor(it) }
        val ai = items.indexOfFirst { item -> item.layers.any { it === activeTop } }.coerceAtLeast(0)
        val custom = items.any { doc.usesCustomBlend(it) }
        val simple = !canvas.isHardwareAccelerated || doc.animation.enabled || editor.soloLayer != null || items.isEmpty()
        if (doc.animation.enabled) {
            com.prosketch.app.engine.Anim.drawEditing(canvas, doc,
                if (editor.playing) editor.playLayer else com.prosketch.app.engine.Anim.currentFrame(doc), editor.playing, editor.renderHook)
        } else if (custom) {
            drawSoftware(canvas, items, filter)
        } else if (simple) {
            Compositor.drawBackground(canvas, doc)
            Compositor.drawItems(canvas, doc, items, editor.renderHook, filter)
        } else {
            val below = items.subList(0, ai)
            val sigB = signature(below, true, filter)
            if (sigB != belowSig) { record(belowNode, below, true, true, filter); belowSig = sigB }
            canvas.drawRenderNode(belowNode)
            Compositor.drawItem(canvas, doc, items[ai], editor.renderHook, filter)
            val above = items.subList(ai + 1, items.size)
            if (above.isNotEmpty()) {
                if (above.all { Compositor.isolatable(doc, it) }) {
                    val sigA = signature(above, false, filter)
                    if (sigA != aboveSig) { record(aboveNode, above, false, true, filter); aboveSig = sigA }
                    canvas.drawRenderNode(aboveNode)
                } else Compositor.drawItems(canvas, doc, above, editor.renderHook, filter)
            }
        }
        if (isolate) canvas.restore()

        // selection mask overlay
        editor.selectionOverlay?.let { (bmp, s) ->
            val m = Matrix().apply { setScale(s, s) }
            canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (editor.prefs.selectionMaskOpacity * 255).toInt() })
        }
        canvas.restore()

        drawGuides(canvas)
        drawToolOverlays(canvas)
        drawCursor(canvas)
        drawEyedropper(canvas)
        drawColorDrop(canvas)
    }

    // ------------------------------------------------------------------ overlays
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = 0xFF2F8CFF.toInt()

    private fun handle(c: Canvas, x: Float, y: Float, color: Int, r: Float = 9f * density) {
        fillPaint.color = Color.WHITE; c.drawCircle(x, y, r + 2.5f * density, fillPaint)
        fillPaint.color = color; c.drawCircle(x, y, r, fillPaint)
    }

    private fun drawGuides(canvas: Canvas) {
        val g = doc.guides
        if (!g.enabled && editor.mode != Mode.GUIDE_EDIT) return
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        linePaint.color = g.color
        linePaint.alpha = (g.opacity * 255).toInt()
        linePaint.strokeWidth = g.thickness / scale * density * 0.75f
        linePaint.pathEffect = null
        val cx = g.centerX(doc.width); val cy = g.centerY(doc.height)
        val ext = hypot(doc.width.toFloat(), doc.height.toFloat())
        when (g.type) {
            GuideType.GRID, GuideType.ISOMETRIC -> {
                canvas.save(); canvas.rotate(g.rotationDeg, cx, cy)
                val step = g.gridSize.coerceAtLeast(8f)
                val angles = if (g.type == GuideType.GRID) floatArrayOf(0f, 90f) else floatArrayOf(30f, 90f, 150f)
                for (a in angles) {
                    canvas.save(); canvas.rotate(a, cx, cy)
                    var k = -((ext / step).toInt() + 1)
                    while (k * step <= ext) {
                        val y = cy + k * step
                        canvas.drawLine(cx - ext, y, cx + ext, y, linePaint)
                        k++
                    }
                    canvas.restore()
                }
                canvas.restore()
            }
            GuideType.PERSPECTIVE -> {
                for (vp in g.vanishingPoints) {
                    for (i in 0 until 36) {
                        val a = i * PI.toFloat() * 2 / 36
                        canvas.drawLine(vp.x, vp.y, vp.x + cos(a) * ext * 2, vp.y + sin(a) * ext * 2, linePaint)
                    }
                }
                if (g.vanishingPoints.size in 1..2) {
                    val y = g.vanishingPoints.map { it.y }.average().toFloat()
                    val p = Paint(linePaint).apply { strokeWidth = linePaint.strokeWidth * 2; alpha = 255 }
                    canvas.drawLine(-ext, y, ext * 2, y, p)
                }
            }
            GuideType.SYMMETRY -> {
                canvas.save(); canvas.rotate(g.rotationDeg, cx, cy)
                val p = Paint(linePaint).apply { strokeWidth = linePaint.strokeWidth * 1.6f }
                when (g.symmetry) {
                    SymmetryType.VERTICAL -> canvas.drawLine(cx, cy - ext, cx, cy + ext, p)
                    SymmetryType.HORIZONTAL -> canvas.drawLine(cx - ext, cy, cx + ext, cy, p)
                    SymmetryType.QUADRANT -> { canvas.drawLine(cx, cy - ext, cx, cy + ext, p); canvas.drawLine(cx - ext, cy, cx + ext, cy, p) }
                    SymmetryType.RADIAL -> {
                        val n = g.radialSegments.coerceIn(2, 32)
                        for (i in 0 until n) {
                            val a = (i * 2 * PI / n - PI / 2).toFloat()
                            canvas.drawLine(cx, cy, cx + cos(a) * ext, cy + sin(a) * ext, p)
                        }
                    }
                }
                canvas.restore()
            }
        }
        canvas.restore()
        if (editor.mode == Mode.GUIDE_EDIT) {
            if (g.type == GuideType.PERSPECTIVE) {
                for (vp in g.vanishingPoints) { val s = toScreen(vp.x, vp.y); handle(canvas, s.x, s.y, accent) }
            } else {
                val s = toScreen(cx, cy)
                handle(canvas, s.x, s.y, accent)
                val rh = rotationHandleGuide()
                handle(canvas, rh.x, rh.y, 0xFF30C46C.toInt(), 8f * density)
            }
        }
    }

    private fun rotationHandleGuide(): PointF {
        val g = doc.guides
        val c = toScreen(g.centerX(doc.width), g.centerY(doc.height))
        val a = Math.toRadians((g.rotationDeg + rotationDeg).toDouble() - 90.0)
        val r = 90f * density
        return PointF(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r)
    }

    private fun drawToolOverlays(canvas: Canvas) {
        when (editor.mode) {
            Mode.SELECT -> {
                val st = editor.selectionTool
                linePaint.color = accent; linePaint.alpha = 255; linePaint.strokeWidth = 2f * density
                linePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
                if (st.lasso.isNotEmpty()) {
                    val path = Path()
                    st.lasso.forEachIndexed { i, p -> val s = toScreen(p.x, p.y); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
                    canvas.drawPath(path, linePaint)
                    val s0 = toScreen(st.lasso[0].x, st.lasso[0].y)
                    handle(canvas, s0.x, s0.y, accent, 7f * density)
                }
                st.dragRect?.let { r ->
                    val path = Path()
                    val rr = RectF(min(r.left, r.right), min(r.top, r.bottom), max(r.left, r.right), max(r.top, r.bottom))
                    if (st.mode == SelectionMode.ELLIPSE) path.addOval(rr, Path.Direction.CW) else path.addRect(rr, Path.Direction.CW)
                    path.transform(viewMatrix)
                    canvas.drawPath(path, linePaint)
                }
                linePaint.pathEffect = null
            }
            Mode.TRANSFORM -> drawTransform(canvas)
            Mode.ADJUST -> drawAdjustOverlay(canvas)
            Mode.LIQUIFY -> {
                val lq = editor.adjustTool.liquify ?: return
                val pos = hoverPos ?: lastToolPos ?: return
                linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * density; linePaint.pathEffect = null
                val r = lq.radius() * scale
                canvas.drawCircle(pos.x, pos.y, r, linePaint)
                linePaint.color = 0x88000000.toInt(); canvas.drawCircle(pos.x, pos.y, r + 1.5f * density, linePaint)
            }
            else -> {}
        }
    }

    private fun transformScreenCorners(): FloatArray? {
        val s = editor.transformTool.session ?: return null
        val pts = s.corners.copyOf(); viewMatrix.mapPoints(pts)
        return pts
    }

    private fun rotationHandle(sc: FloatArray): PointF {
        val mx = (sc[0] + sc[2]) / 2; val my = (sc[1] + sc[3]) / 2
        val cx = (sc[0] + sc[2] + sc[4] + sc[6]) / 4; val cy = (sc[1] + sc[3] + sc[5] + sc[7]) / 4
        val dx = mx - cx; val dy = my - cy
        val l = hypot(dx, dy).coerceAtLeast(1f)
        val off = 36f * density
        val p = PointF(mx + dx / l * off, my + dy / l * off)
        // keep the handle reachable: if it would sit under the top bar, flip it to the opposite edge
        if (p.y < 64f * density) {
            val bx = (sc[4] + sc[6]) / 2; val by = (sc[5] + sc[7]) / 2
            val ex = bx - cx; val ey = by - cy
            val el = hypot(ex, ey).coerceAtLeast(1f)
            return PointF(bx + ex / el * off, by + ey / el * off)
        }
        return p
    }

    private fun drawTransform(canvas: Canvas) {
        val s = editor.transformTool.session ?: return
        val sc = transformScreenCorners() ?: return
        linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * density
        linePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        val p = Path().apply { moveTo(sc[0], sc[1]); lineTo(sc[2], sc[3]); lineTo(sc[4], sc[5]); lineTo(sc[6], sc[7]); close() }
        canvas.drawPath(p, linePaint)
        linePaint.color = 0x99000000.toInt(); linePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 6.5f * density)
        canvas.drawPath(p, linePaint)
        linePaint.pathEffect = null
        if (s.mode == TransformMode.WARP) {
            val w = s.warpPoints().copyOf(); viewMatrix.mapPoints(w)
            linePaint.color = 0xCCFFFFFF.toInt(); linePaint.strokeWidth = 1f * density
            for (j in 0 until 4) for (i in 0 until 3) {
                canvas.drawLine(w[(j * 4 + i) * 2], w[(j * 4 + i) * 2 + 1], w[(j * 4 + i + 1) * 2], w[(j * 4 + i + 1) * 2 + 1], linePaint)
                canvas.drawLine(w[(i * 4 + j) * 2], w[(i * 4 + j) * 2 + 1], w[((i + 1) * 4 + j) * 2], w[((i + 1) * 4 + j) * 2 + 1], linePaint)
            }
            for (k in 0 until 16) handle(canvas, w[k * 2], w[k * 2 + 1], accent, 6f * density)
            return
        }
        for (i in 0 until 4) handle(canvas, sc[i * 2], sc[i * 2 + 1], accent)
        if (s.mode != TransformMode.DISTORT || true) for (e in 0 until 4) {
            val a = e; val b = (e + 1) % 4
            handle(canvas, (sc[a * 2] + sc[b * 2]) / 2, (sc[a * 2 + 1] + sc[b * 2 + 1]) / 2, accent, 6.5f * density)
        }
        if (s.mode != TransformMode.DISTORT) {
            val rh = rotationHandle(sc)
            linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * density
            val tmx = (sc[0] + sc[2]) / 2; val tmy = (sc[1] + sc[3]) / 2
            val bmx = (sc[4] + sc[6]) / 2; val bmy = (sc[5] + sc[7]) / 2
            if (hypot(rh.x - tmx, rh.y - tmy) < hypot(rh.x - bmx, rh.y - bmy)) canvas.drawLine(tmx, tmy, rh.x, rh.y, linePaint)
            else canvas.drawLine(bmx, bmy, rh.x, rh.y, linePaint)
            handle(canvas, rh.x, rh.y, 0xFF30C46C.toInt())
        }
        // size read-out
        val txt = "${s.widthPx().roundToInt()} × ${s.heightPx().roundToInt()} px · ${((s.angle() % 360 + 360) % 360).roundToInt()}°"
        drawPill(canvas, txt, (sc[4] + sc[6]) / 2, max(sc[5], sc[7]) + 30f * density)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private fun drawPill(c: Canvas, text: String, x: Float, y: Float) {
        textPaint.textSize = 13f * density
        val w = textPaint.measureText(text) + 20f * density
        fillPaint.color = 0xCC000000.toInt()
        c.drawRoundRect(x - w / 2, y - 14f * density, x + w / 2, y + 10f * density, 12f * density, 12f * density, fillPaint)
        c.drawText(text, x, y + 2f * density, textPaint)
    }

    // brush cursor (hover + while painting)
    private var hoverPos: PointF? = null
    private var paintPos: PointF? = null
    private var lastToolPos: PointF? = null

    private fun drawCursor(canvas: Canvas) {
        if (!editor.prefs.brushCursor || editor.mode != Mode.PAINT) return
        val pos = hoverPos ?: paintPos ?: return
        val d = editor.brushDocSize(1f / scale) * scale
        if (d < 3f) return
        linePaint.pathEffect = null; linePaint.strokeWidth = 1f * density
        linePaint.color = 0xAAFFFFFF.toInt(); canvas.drawCircle(pos.x, pos.y, d / 2f, linePaint)
        linePaint.color = 0x88000000.toInt(); canvas.drawCircle(pos.x, pos.y, d / 2f + 1.2f * density, linePaint)
    }

    // ------------------------------------------------------------------ eyedropper
    private var dropperPos: PointF? = null
    private var dropperColor: Int? = null
    private var dropperLayerOnly = false

    private fun sampleAt(x: Float, y: Float) {
        dropperPos = PointF(x, y)
        val d = toDoc(x, y)
        editor.sampleColor(d.x, d.y, dropperLayerOnly)?.let { dropperColor = it }
        invalidate()
    }

    private fun drawEyedropper(canvas: Canvas) {
        val p = dropperPos ?: return
        val c = dropperColor ?: editor.color
        val r = 54f * density; val sw = 20f * density
        val cy = p.y - (if (lastWasStylus) 0f else 0f)
        val oval = RectF(p.x - r, cy - r, p.x + r, cy + r)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw }
        ring.color = c; canvas.drawArc(oval, 180f, 180f, false, ring)
        ring.color = editor.color; canvas.drawArc(oval, 0f, 180f, false, ring)
        linePaint.pathEffect = null; linePaint.strokeWidth = 1.5f * density
        linePaint.color = 0x66000000; canvas.drawCircle(p.x, cy, r + sw / 2, linePaint); canvas.drawCircle(p.x, cy, r - sw / 2, linePaint)
        linePaint.color = Color.WHITE; canvas.drawCircle(p.x, cy, 4f * density, linePaint)
        if (dropperLayerOnly) drawPill(canvas, L.t("현재 레이어"), p.x, cy - r - 24f * density)
    }

    // ------------------------------------------------------------------ ColorDrop (dragged from the colour button)
    private var dropPos: PointF? = null
    private var dropPreviewing = false
    private var dropHoldX = 0f
    private val dropHold = Runnable {
        val p = dropPos ?: return@Runnable
        val d = toDoc(p.x, p.y)
        editor.colorDropPreview(d.x, d.y)
        dropPreviewing = true
        dropHoldX = p.x
        editor.hud(L.tf("ColorDrop 임계값: {0}% · 좌우로 밀어 조절", (editor.fillThreshold * 100).toInt()))
    }

    fun colorDropMove(x: Float, y: Float) {
        val prev = dropPos
        dropPos = PointF(x, y)
        if (dropPreviewing) {
            editor.colorDropAdjust((x - dropHoldX) / width.coerceAtLeast(1) * 1.2f)
            dropHoldX = x
        } else if (prev == null || hypot(prev.x - x, prev.y - y) > 6f * density) {
            removeCallbacks(dropHold); postDelayed(dropHold, 550)
        }
        invalidate()
    }

    fun colorDropEnd(x: Float, y: Float, cancelled: Boolean) {
        removeCallbacks(dropHold)
        val d = toDoc(x, y)
        val inside = d.x >= 0 && d.y >= 0 && d.x < doc.width && d.y < doc.height
        if (!cancelled && inside) editor.colorDropCommit(d.x, d.y, immediate = !dropPreviewing)
        else editor.endFill()
        dropPos = null; dropPreviewing = false
        invalidate()
    }

    private fun drawColorDrop(canvas: Canvas) {
        val p = dropPos ?: return
        fillPaint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, 15f * density, fillPaint)
        fillPaint.color = editor.color; canvas.drawCircle(p.x, p.y, 12f * density, fillPaint)
    }

    // ------------------------------------------------------------------ input
    private enum class G { NONE, IGNORE, PENDING, STROKE, PAN, NAV, EYEDROPPER, TOOL, OPACITY, QUICKMENU }

    private var g = G.NONE
    private var strokePointer = -1
    private var lastWasStylus = false
    private val slop get() = 8f * density
    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var downPressure = 1f
    private var maxPointers = 0
    private var navMoved = false
    private var eraserOverride = false

    // navigation
    private var navId1 = -1; private var navId2 = -1
    private var navCx = 0f; private var navCy = 0f; private var navDist = 0f; private var navAngle = 0f
    private var navTotalScale = 1f
    private var navStartCx = 0f; private var navStartCy = 0f
    private var scrubDir = 0; private var scrubReversals = 0; private var scrubAnchorX = 0f; private var scrubDone = false
    private var swipeDone = false

    // QuickShape hold detection
    private var qsAnchorX = 0f; private var qsAnchorY = 0f
    private val qsRunnable = Runnable {
        if (g == G.STROKE && editor.strokeActive && editor.tryQuickShape()) postDelayed(qsSettle, 180)
    }
    private var qsFlushPosted = false
    private val qsFlush = Runnable {
        qsFlushPosted = false
        editor.flushQuickShape()
        removeCallbacks(qsSettle)
        if (editor.quickShapeIsPreview) postDelayed(qsSettle, 180)
    }
    // full-quality render once the pen rests
    private val qsSettle = Runnable { if (editor.quickShape != null && editor.quickShapeIsPreview) editor.renderQuickShape() }

    private val holdEyedropper = Runnable {
        if (g == G.PENDING && editor.mode == Mode.PAINT) {
            if (editor.prefs.quickMenuGesture == 1) { openQuickMenu(downX, downY); return@Runnable }
            g = G.EYEDROPPER
            dropperLayerOnly = false
            sampleAt(downX, downY)
        }
    }

    // ---- QuickMenu (coordinates handed to Compose are unmirrored screen px)
    private fun unmirrorX(x: Float) = if (editor.mirrorView) width - x else x

    private fun openQuickMenu(x: Float, y: Float) {
        g = G.QUICKMENU
        editor.quickMenuHover = -1
        editor.quickMenuAt = PointF(unmirrorX(x), y)
    }

    private fun quickMenuMove(x: Float, y: Float) {
        val c = editor.quickMenuAt ?: return
        editor.quickMenuHover = com.prosketch.app.ui.editor.quickMenuIndexAt(c, unmirrorX(x), y, 56f * density)
    }

    private fun quickMenuUp() {
        val i = editor.quickMenuHover
        editor.quickMenuHover = -1
        if (i >= 0) {
            editor.quickMenuAt = null
            com.prosketch.app.ui.editor.QuickAction.parse(editor.prefs.quickMenuActions)[i].run(editor)
        }   // released on the centre: the menu stays open for tapping
    }

    /** Space bar / external triggers. */
    fun toggleQuickMenu() {
        editor.quickMenuAt = if (editor.quickMenuAt != null) null else PointF(width / 2f, height / 2f)
    }
    private val rapidUndo = object : Runnable {
        override fun run() {
            if (g == G.NAV && !navMoved) {
                if (maxPointers == 2) editor.undo() else if (maxPointers == 3) editor.redo()
                rapidActive = true
                postDelayed(this, 110)
            }
        }
    }
    private var rapidActive = false

    private fun isStylus(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun fingerPaints(): Boolean = when (editor.prefs.fingerMode) {
        FingerMode.PAINT -> true
        FingerMode.NONE -> false
        FingerMode.AUTO -> !editor.penDetected
    }

    private fun point(e: MotionEvent, idx: Int, h: Int = -1): InputPoint {
        val x = if (h < 0) e.getX(idx) else e.getHistoricalX(idx, h)
        val y = if (h < 0) e.getY(idx) else e.getHistoricalY(idx, h)
        val pr = if (h < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, h)
        val tilt = if (h < 0) e.getAxisValue(MotionEvent.AXIS_TILT, idx) else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
        var ori = if (h < 0) e.getOrientation(idx) else e.getHistoricalOrientation(idx, h)
        ori -= Math.toRadians(rotationDeg.toDouble()).toFloat()
        val t = if (h < 0) e.eventTime else e.getHistoricalEventTime(h)
        val d = toDoc(x, y)
        return InputPoint(d.x, d.y, pr, tilt, ori, t)
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean = mirrored(ev) { e -> handleHover(e) }

    private fun handleHover(e: MotionEvent): Boolean {
        if (isStylus(e, 0)) {
            editor.penDetected = true
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { hoverPos = PointF(e.x, e.y); invalidate() }
                MotionEvent.ACTION_HOVER_EXIT -> { hoverPos = null; invalidate() }
            }
            return true
        }
        return super.onHoverEvent(e)
    }

    private val mirrorMatrix = Matrix()

    /** Mirror view flips the display; touches are flipped the same way so they land where they appear. */
    private inline fun <T> mirrored(e: MotionEvent, block: (MotionEvent) -> T): T {
        if (!editor.mirrorView) return block(e)
        mirrorMatrix.setScale(-1f, 1f, width / 2f, 0f)
        val m = MotionEvent.obtain(e)
        m.transform(mirrorMatrix)
        try { return block(m) } finally { m.recycle() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = mirrored(ev) { e -> handleTouch(e) }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e, false)
            MotionEvent.ACTION_CANCEL -> onUp(e, true)
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        animator?.cancel()
        maxPointers = 1; navMoved = false; rapidActive = false
        downX = e.x; downY = e.y; downTime = e.eventTime; downPressure = e.pressure
        val stylus = isStylus(e, 0)
        lastWasStylus = stylus
        strokePointer = e.getPointerId(0)
        if (stylus) { editor.penDetected = true; requestUnbufferedDispatch(e) }
        if (editor.panel != null) { editor.panel = null; g = G.IGNORE; return }
        editor.copyPasteMenu = false

        if (editor.eyedropperArmed || editor.eyedropperHeld) {
            editor.eyedropperArmed = false
            g = G.EYEDROPPER; dropperLayerOnly = false; sampleAt(e.x, e.y); return
        }
        if (editor.layerOpacityTarget != null) { g = G.OPACITY; return }

        if (stylus) {
            val btn = e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
            eraserOverride = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            if (btn) when (editor.prefs.penButton) {
                PenButtonAction.ERASER -> eraserOverride = true
                PenButtonAction.EYEDROPPER -> { g = G.EYEDROPPER; dropperLayerOnly = false; sampleAt(e.x, e.y); return }
                PenButtonAction.UNDO -> { editor.undo(); g = G.IGNORE; return }
                PenButtonAction.QUICKMENU -> { openQuickMenu(e.x, e.y); return }
                PenButtonAction.NONE -> {}
            }
            startPrimary(e, 0, true)
        } else {
            eraserOverride = false
            g = G.PENDING
            if (editor.mode == Mode.PAINT && (editor.prefs.holdEyedropper || editor.prefs.quickMenuGesture == 1) && !editor.continueFill)
                postDelayed(holdEyedropper, (editor.prefs.holdEyedropperDelay * 1000).toLong())
        }
    }

    /** Starts the primary action for one pointer (stroke or tool). */
    private fun startPrimary(e: MotionEvent, idx: Int, stylus: Boolean) {
        removeCallbacks(holdEyedropper)
        strokePointer = e.getPointerId(idx)
        val x = if (g == G.PENDING) downX else e.getX(idx)
        val y = if (g == G.PENDING) downY else e.getY(idx)
        when (editor.mode) {
            Mode.PAINT -> {
                if (editor.continueFill) { g = G.TOOL; return }
                if (!stylus && !fingerPaints()) { g = G.PAN; panLastX = e.getX(idx); panLastY = e.getY(idx); return }
                val d = toDoc(x, y)
                val p0 = InputPoint(d.x, d.y, if (stylus) e.getPressure(idx) else 1f,
                    e.getAxisValue(MotionEvent.AXIS_TILT, idx), e.getOrientation(idx), e.eventTime)
                if (editor.beginStroke(p0, stylus, 1f / scale, eraserOverride)) {
                    g = G.STROKE
                    qsAnchorX = x; qsAnchorY = y
                    paintPos = PointF(e.getX(idx), e.getY(idx))
                    if (g == G.STROKE && (x != e.getX(idx) || y != e.getY(idx))) editor.strokeTo(point(e, idx))
                } else g = G.IGNORE
            }
            else -> { g = G.TOOL; toolDown(x, y, if (stylus) e.getPressure(idx) else 1f) }
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        maxPointers = max(maxPointers, e.pointerCount)
        if (isStylus(e, idx)) {
            editor.penDetected = true
            // pen landed while a finger was down: the finger was a palm or a gesture → pen wins
            if (g == G.STROKE && !lastWasStylus) editor.cancelStroke()
            removeCallbacks(holdEyedropper); removeCallbacks(rapidUndo)
            lastWasStylus = true
            g = G.NONE
            startPrimary(e, idx, true)
            return
        }
        when (g) {
            G.STROKE -> if (editor.quickShape != null) editor.quickShapePerfect()
            G.EYEDROPPER -> { dropperLayerOnly = !dropperLayerOnly; dropperPos?.let { sampleAt(it.x, it.y) } }
            G.PENDING, G.PAN, G.NONE -> startNav(e)
            G.NAV -> startNav(e)
            G.TOOL -> if (!lastWasStylus) { toolCancel(); startNav(e) }
            else -> {}
        }
    }

    private fun startNav(e: MotionEvent) {
        removeCallbacks(holdEyedropper)
        if (g != G.NAV) {
            g = G.NAV
            navTotalScale = 1f; scrubReversals = 0; scrubDir = 0; scrubDone = false; swipeDone = false
            removeCallbacks(rapidUndo)
            postDelayed(rapidUndo, (editor.prefs.rapidUndoDelay * 1000).toLong().coerceAtLeast(250))
        }
        resetNavRef(e, -1)
        navStartCx = navCx; navStartCy = navCy
        scrubAnchorX = navCx
    }

    private fun resetNavRef(e: MotionEvent, excludeIdx: Int) {
        val ids = (0 until e.pointerCount).filter { it != excludeIdx }
        if (ids.size < 2) { navId1 = if (ids.isNotEmpty()) e.getPointerId(ids[0]) else -1; navId2 = -1; if (ids.isNotEmpty()) { navCx = e.getX(ids[0]); navCy = e.getY(ids[0]) }; return }
        navId1 = e.getPointerId(ids[0]); navId2 = e.getPointerId(ids[1])
        val x1 = e.getX(ids[0]); val y1 = e.getY(ids[0]); val x2 = e.getX(ids[1]); val y2 = e.getY(ids[1])
        navCx = (x1 + x2) / 2; navCy = (y1 + y2) / 2
        navDist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
        navAngle = atan2(y2 - y1, x2 - x1)
    }

    private var panLastX = 0f; private var panLastY = 0f

    private fun onMove(e: MotionEvent) {
        when (g) {
            G.PENDING -> {
                if (hypot(e.x - downX, e.y - downY) > slop) {
                    removeCallbacks(holdEyedropper)
                    startPrimary(e, 0, false)
                    if (g == G.PAN) onMove(e)
                }
            }
            G.STROKE -> {
                val idx = e.findPointerIndex(strokePointer)
                if (idx < 0) return
                for (h in 0 until e.historySize) editor.strokeTo(point(e, idx, h))
                editor.strokeTo(point(e, idx))
                if (editor.quickShape != null && !qsFlushPosted) { qsFlushPosted = true; postOnAnimation(qsFlush) }
                val x = e.getX(idx); val y = e.getY(idx)
                paintPos = PointF(x, y); hoverPos = null
                if (editor.quickShape == null && editor.prefs.quickShape) {
                    if (hypot(x - qsAnchorX, y - qsAnchorY) > 5f * density) {
                        qsAnchorX = x; qsAnchorY = y
                        removeCallbacks(qsRunnable)
                        postDelayed(qsRunnable, (editor.prefs.quickShapeDelay * 1000).toLong())
                    }
                }
            }
            G.PAN -> {
                viewMatrix.postTranslate(e.x - panLastX, e.y - panLastY)
                panLastX = e.x; panLastY = e.y
                invalidate()
            }
            G.NAV -> navMove(e)
            G.EYEDROPPER -> sampleAt(e.x, e.y)
            G.QUICKMENU -> quickMenuMove(e.x, e.y)
            G.TOOL -> {
                val idx = e.findPointerIndex(strokePointer).coerceAtLeast(0)
                for (h in 0 until e.historySize) toolMove(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h))
                toolMove(e.getX(idx), e.getY(idx), if (lastWasStylus) e.getPressure(idx) else 1f)
            }
            G.OPACITY -> {
                val l = editor.layerOpacityTarget ?: return
                val dx = (e.x - downX) / width
                downX = e.x
                editor.setOpacityLive(l, l.opacity + dx * 1.2f)
                editor.hud(L.tf("불투명도 {0}%", (l.opacity * 100).roundToInt()))
            }
            else -> {}
        }
    }

    private fun navMove(e: MotionEvent) {
        val i1 = e.findPointerIndex(navId1)
        val i2 = if (navId2 >= 0) e.findPointerIndex(navId2) else -1
        if (i1 < 0) return
        val cx: Float; val cy: Float
        if (i2 >= 0) {
            val x1 = e.getX(i1); val y1 = e.getY(i1); val x2 = e.getX(i2); val y2 = e.getY(i2)
            cx = (x1 + x2) / 2; cy = (y1 + y2) / 2
            val dist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
            val ang = atan2(y2 - y1, x2 - x1)
            if (!navMoved && (hypot(cx - navStartCx, cy - navStartCy) > slop * 1.5f || abs(dist / navDist - 1f) > 0.06f)) {
                navMoved = true; removeCallbacks(rapidUndo)
            }
            if (maxPointers == 2 && navMoved) {
                var s = dist / navDist
                val cur = scale
                s = s.coerceIn(0.02f / cur, 64f / cur)
                viewMatrix.postTranslate(cx - navCx, cy - navCy)
                viewMatrix.postScale(s, s, cx, cy)
                if (editor.prefs.rotateWithPinch) viewMatrix.postRotate(Math.toDegrees((ang - navAngle).toDouble()).toFloat(), cx, cy)
                navTotalScale *= s
                editor.zoomText = "${(scale * 100).roundToInt()}% · ${((rotationDeg % 360 + 360) % 360).roundToInt()}°"
                invalidate()
            }
            navDist = dist; navAngle = ang
        } else { cx = e.getX(i1); cy = e.getY(i1) }

        if (maxPointers >= 3 && e.pointerCount >= 3) {
            var sx = 0f; var sy = 0f
            for (k in 0 until e.pointerCount) { sx += e.getX(k); sy += e.getY(k) }
            sx /= e.pointerCount; sy /= e.pointerCount
            if (hypot(sx - navStartCx, sy - navStartCy) > slop * 2) { navMoved = true; removeCallbacks(rapidUndo) }
            // scrub: horizontal direction reversals
            val dx = sx - scrubAnchorX
            if (abs(dx) > 28f * density) {
                val dir = if (dx > 0) 1 else -1
                if (scrubDir != 0 && dir != scrubDir) scrubReversals++
                scrubDir = dir; scrubAnchorX = sx
                if (scrubReversals >= 3 && !scrubDone && editor.prefs.scrubClear) { scrubDone = true; editor.clearLayer() }
            }
            if (!swipeDone && !scrubDone && scrubReversals == 0 && sy - navStartCy > 110f * density && abs(sx - navStartCx) < 80f * density && editor.prefs.threeSwipeMenu) {
                swipeDone = true; editor.copyPasteMenu = true
            }
        }
        navCx = cx; navCy = cy
    }

    private fun onPointerUp(e: MotionEvent) {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        val canceled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
        when (g) {
            G.STROKE -> if (id == strokePointer) {
                removeCallbacks(qsRunnable)
                if (canceled) editor.cancelStroke() else editor.endStroke()
                g = G.IGNORE; paintPos = null
            }
            G.NAV -> resetNavRef(e, idx)
            G.TOOL -> if (id == strokePointer) { toolUp(); g = G.IGNORE }
            else -> {}
        }
    }

    private fun onUp(e: MotionEvent, cancel: Boolean) {
        removeCallbacks(holdEyedropper); removeCallbacks(qsRunnable); removeCallbacks(rapidUndo)
        when (g) {
            G.STROKE -> if (cancel) editor.cancelStroke() else editor.endStroke()
            G.PENDING -> if (!cancel) {
                // a finger tap
                if (editor.mode == Mode.PAINT) {
                    if (editor.continueFill) { val d = toDoc(e.x, e.y); editor.colorDropCommit(d.x, d.y, true) }
                    else if (fingerPaints()) {
                        startPrimary(e, 0, false)
                        if (g == G.STROKE) editor.endStroke()
                    }
                } else { toolDown(e.x, e.y, 1f); toolUp(tap = true) }
            }
            G.TOOL -> if (cancel) toolCancel() else toolUp(tap = hypot(e.x - downX, e.y - downY) < slop && e.eventTime - downTime < 300)
            G.NAV -> {
                val dur = e.eventTime - downTime
                if (!navMoved && !rapidActive && dur < 350) {
                    when (maxPointers) {
                        2 -> if (editor.prefs.twoFingerUndo) editor.undo()
                        3 -> if (editor.prefs.threeFingerRedo) editor.redo()
                        4 -> if (editor.prefs.fourTapFullscreen) { editor.fullscreen = !editor.fullscreen; editor.hud(if (editor.fullscreen) L.t("전체 화면") else L.t("전체 화면 해제")) }
                    }
                } else if (maxPointers == 2 && dur < 380 && navTotalScale < 0.62f) {
                    fitToScreen()
                } else if (maxPointers == 2 && dur < 380 && navTotalScale > 1.8f && previousView != null) {
                    animateTo(previousView!!, true); previousView = null
                } else if (maxPointers == 2) snapRotation()
                editor.zoomText = null
            }
            G.EYEDROPPER -> {
                dropperColor?.let { if (!cancel) { editor.setColor(it); editor.hud(L.t("색상 선택됨")) } }
                dropperPos = null; dropperColor = null
            }
            G.OPACITY -> editor.layerOpacityTarget?.let { editor.commitOpacity(it); editor.layerOpacityTarget = null }
            G.QUICKMENU -> if (!cancel) quickMenuUp()
            else -> {}
        }
        g = G.NONE
        paintPos = null
        strokePointer = -1
        invalidate()
    }

    private fun snapRotation() {
        val r = rotationDeg
        val near = (r / 90f).roundToInt() * 90f
        if (abs(r - near) in 0.01f..5f) {
            val d = decompose(viewMatrix)
            animator?.cancel()
            val from = d[1]
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160
                addUpdateListener { compose(d[0], from + (near - from) * (it.animatedValue as Float), d[2], d[3]); invalidate() }
                start()
            }
        }
    }

    // ------------------------------------------------------------------ tool interactions (select / transform / liquify / adjust / guides)
    private var toolLastX = 0f; private var toolLastY = 0f
    private var tfHit = -1          // 0..3 corner, 4..7 edge, 8 rotate, 9 move, 100+k warp point, 99 warp free
    private var tfStartAngle = 0f
    private var guideHit = -1
    private var autoStarted = false
    private var lassoDragging = false
    private var momentumVx = 0f; private var momentumVy = 0f

    // ---- adjustments: focal disc, clone source, recolor crosshair, pencil strokes, swipe
    private var adjDrag = 0          // 0 swipe, 1 focal, 2 clone disc, 3 crosshair, 4 paint, 5 drag vector
    private var adjDown = PointF()
    private var adjDownTime = 0L
    private var adjMoved = false

    private fun nearScreen(p: PointF?, x: Float, y: Float, r: Float): Boolean {
        p ?: return false
        val s = toScreen(p.x, p.y)
        return hypot(s.x - x, s.y - y) < r
    }

    private fun adjustDown(x: Float, y: Float, pressure: Float) {
        val at = editor.adjustTool
        val t = at.type ?: return
        val d = toDoc(x, y)
        adjDown = PointF(d.x, d.y); adjDownTime = SystemClock.uptimeMillis(); adjMoved = false
        adjDrag = when {
            t == AdjustmentType.CLONE && nearScreen(at.cloneSource, x, y, 44f * density) -> 2
            t == AdjustmentType.CLONE -> { at.strokeDown(d.x, d.y, pressure, lastWasStylus, 1f / scale); 4 }
            nearScreen(arrowEnd(), x, y, 30f * density) -> 6
            nearScreen(at.focal(), x, y, 48f * density) -> 1
            t == AdjustmentType.RECOLOR -> { at.setRecolorTarget(d.x, d.y); 3 }
            at.pencil -> { at.strokeDown(d.x, d.y, pressure, lastWasStylus, 1f / scale); 4 }
            at.usesDragVector() -> 5
            else -> 0
        }
    }

    private fun adjustMove(x: Float, y: Float, dxs: Float, pressure: Float, d: PointF) {
        val at = editor.adjustTool
        if (hypot(x - downX, y - downY) > 8f * density) adjMoved = true
        when (adjDrag) {
            1 -> at.setFocal(d.x, d.y)
            2 -> at.moveCloneSource(PointF(d.x, d.y))
            3 -> at.setRecolorTarget(d.x, d.y)
            4 -> at.strokeMove(d.x, d.y, pressure, lastWasStylus)
            5 -> at.dragVector(d.x - adjDown.x, d.y - adjDown.y)
            6 -> at.focal()?.let { f -> at.setParam("angle", ((Math.toDegrees(atan2(d.y - f.y, d.x - f.x).toDouble()).toFloat() + 360f) % 360f)) }
            else -> at.dragPrimary(dxs / width)
        }
    }

    /** Directional perspective blur: document position of the arrow handle. */
    private fun arrowEnd(): PointF? {
        val at = editor.adjustTool
        if (at.type != AdjustmentType.PERSPECTIVE_BLUR || (at.params["mode"] ?: 0f) < 0.5f) return null
        val f = at.focal() ?: return null
        val a = Math.toRadians((at.params["angle"] ?: 0f).toDouble())
        val len = 34f * density * 2.2f / scale
        return PointF(f.x + cos(a).toFloat() * len, f.y + sin(a).toFloat() * len)
    }

    private fun adjustUp() {
        val at = editor.adjustTool
        when (adjDrag) {
            4 -> at.strokeUp()
            2 -> if (!adjMoved && SystemClock.uptimeMillis() - adjDownTime > 450) {
                at.cloneLocked = !at.cloneLocked
                editor.hud(if (at.cloneLocked) L.t("복제 소스 고정") else L.t("복제 소스 고정 해제"))
            }
        }
        adjDrag = 0
    }

    private fun drawAdjustOverlay(canvas: Canvas) {
        val at = editor.adjustTool
        val t = at.type ?: return
        linePaint.pathEffect = null
        at.focal()?.let { f ->
            val s = toScreen(f.x, f.y)
            val r = 34f * density
            fillPaint.color = 0x332F8CFF; canvas.drawCircle(s.x, s.y, r, fillPaint)
            linePaint.color = Color.WHITE; linePaint.strokeWidth = 2.5f * density; canvas.drawCircle(s.x, s.y, r, linePaint)
            linePaint.color = 0x99000000.toInt(); linePaint.strokeWidth = 1f * density; canvas.drawCircle(s.x, s.y, r + 2f * density, linePaint)
            if (t == AdjustmentType.PERSPECTIVE_BLUR && (at.params["mode"] ?: 0f) >= 0.5f) {
                val e = arrowEnd()!!.let { toScreen(it.x, it.y) }
                val ex = e.x; val ey = e.y
                linePaint.color = Color.WHITE; linePaint.strokeWidth = 3f * density
                canvas.drawLine(s.x, s.y, ex, ey, linePaint)
                handle(canvas, ex, ey, accent, 7f * density)
            }
        }
        if (t == AdjustmentType.CLONE) at.cloneSource?.let { c ->
            val s = toScreen(c.x, c.y)
            val r = 30f * density
            linePaint.color = if (at.cloneLocked) accent else Color.WHITE; linePaint.strokeWidth = 3f * density
            canvas.drawCircle(s.x, s.y, r, linePaint)
            linePaint.color = 0x99000000.toInt(); linePaint.strokeWidth = 1f * density; canvas.drawCircle(s.x, s.y, r + 2.5f * density, linePaint)
            linePaint.color = Color.WHITE; linePaint.strokeWidth = 1.5f * density
            canvas.drawLine(s.x - r * 0.5f, s.y, s.x + r * 0.5f, s.y, linePaint); canvas.drawLine(s.x, s.y - r * 0.5f, s.x, s.y + r * 0.5f, linePaint)
        }
        at.recolorTarget()?.let { p ->
            val s = toScreen(p.x, p.y)
            val r = 22f * density
            fillPaint.color = editor.color; canvas.drawCircle(s.x, s.y, r * 0.55f, fillPaint)
            linePaint.color = Color.WHITE; linePaint.strokeWidth = 2.5f * density
            canvas.drawCircle(s.x, s.y, r, linePaint)
            canvas.drawLine(s.x - r * 1.7f, s.y, s.x - r, s.y, linePaint); canvas.drawLine(s.x + r, s.y, s.x + r * 1.7f, s.y, linePaint)
            canvas.drawLine(s.x, s.y - r * 1.7f, s.x, s.y - r, linePaint); canvas.drawLine(s.x, s.y + r, s.x, s.y + r * 1.7f, linePaint)
        }
    }

    private fun toolDown(x: Float, y: Float, pressure: Float) {
        toolLastX = x; toolLastY = y
        lastToolPos = PointF(x, y)
        val d = toDoc(x, y)
        when (editor.mode) {
            Mode.SELECT -> {
                val st = editor.selectionTool
                when (st.mode) {
                    SelectionMode.FREEHAND -> {
                        if (st.lasso.isNotEmpty()) {
                            val s0 = toScreen(st.lasso[0].x, st.lasso[0].y)
                            if (st.lasso.size >= 3 && hypot(s0.x - x, s0.y - y) < 22f * density) { st.closeLasso(); lassoDragging = false; return }
                        }
                        st.lassoPoint(d); lassoDragging = false
                    }
                    SelectionMode.RECTANGLE, SelectionMode.ELLIPSE -> st.dragRect = RectF(d.x, d.y, d.x, d.y)
                    SelectionMode.AUTOMATIC -> { st.autoSelect(d.x, d.y); autoStarted = true }
                }
            }
            Mode.TRANSFORM -> transformDown(x, y)
            Mode.LIQUIFY -> { editor.adjustTool.syncLiquify(); momentumVx = 0f; momentumVy = 0f }
            Mode.ADJUST -> adjustDown(x, y, pressure)
            Mode.GUIDE_EDIT -> guideDown(x, y)
            Mode.PAINT -> if (editor.continueFill) { /* handled on up */ }
            else -> {}
        }
        invalidate()
    }

    private fun toolMove(x: Float, y: Float, pressure: Float) {
        val dxs = x - toolLastX; val dys = y - toolLastY
        if (dxs == 0f && dys == 0f) return
        val d = toDoc(x, y)
        val prevD = toDoc(toolLastX, toolLastY)
        lastToolPos = PointF(x, y)
        when (editor.mode) {
            Mode.SELECT -> {
                val st = editor.selectionTool
                when (st.mode) {
                    SelectionMode.FREEHAND -> {
                        val last = st.lasso.lastOrNull()
                        if (last == null || hypot(d.x - last.x, d.y - last.y) * scale > 3f * density) { st.lassoPoint(d); lassoDragging = true }
                    }
                    SelectionMode.RECTANGLE, SelectionMode.ELLIPSE -> st.dragRect?.let { it.right = d.x; it.bottom = d.y }
                    SelectionMode.AUTOMATIC -> if (autoStarted) st.autoAdjust(dxs / width * 0.8f)
                }
            }
            Mode.TRANSFORM -> transformMove(x, y, d, prevD)
            Mode.LIQUIFY -> {
                val lq = editor.adjustTool.liquify ?: return
                lq.stroke(d.x, d.y, d.x - prevD.x, d.y - prevD.y, pressure)
                momentumVx = d.x - prevD.x; momentumVy = d.y - prevD.y
            }
            Mode.ADJUST -> adjustMove(x, y, dxs, pressure, d)
            Mode.GUIDE_EDIT -> guideMove(d, prevD, x, y)
            else -> {}
        }
        toolLastX = x; toolLastY = y
        invalidate()
    }

    private fun toolUp(tap: Boolean = false) {
        when (editor.mode) {
            Mode.SELECT -> {
                val st = editor.selectionTool
                when (st.mode) {
                    SelectionMode.FREEHAND -> if (lassoDragging && st.lasso.size > 8) {
                        val a = toScreen(st.lasso.first().x, st.lasso.first().y); val b = toScreen(st.lasso.last().x, st.lasso.last().y)
                        if (hypot(a.x - b.x, a.y - b.y) < 60f * density) st.closeLasso()
                    }
                    SelectionMode.RECTANGLE, SelectionMode.ELLIPSE -> st.dragRect?.let { st.applyRect(it, st.mode == SelectionMode.ELLIPSE) }
                    SelectionMode.AUTOMATIC -> { st.autoEnd(); autoStarted = false }
                }
            }
            Mode.LIQUIFY -> startMomentum()
            Mode.ADJUST -> adjustUp()
            Mode.TRANSFORM -> { tfHit = -1; editor.transformTool.changed() }
            Mode.PAINT -> if (editor.continueFill && tap) { val d = toDoc(downX, downY); editor.colorDropCommit(d.x, d.y, true) }
            else -> {}
        }
        guideHit = -1
        lassoDragging = false
        invalidate()
    }

    private fun toolCancel() {
        val st = editor.selectionTool
        st.dragRect = null
        if (lassoDragging) st.lasso.clear()
        tfHit = -1
        if (adjDrag == 4) editor.adjustTool.strokeCancel()
        adjDrag = 0
        invalidate()
    }

    private fun startMomentum() {
        val lq = editor.adjustTool.liquify ?: return
        if (lq.momentum <= 0f || hypot(momentumVx, momentumVy) < 0.5f) return
        val pos = lastToolPos ?: return
        var p = toDoc(pos.x, pos.y)
        var vx = momentumVx; var vy = momentumVy
        var frames = (lq.momentum * 40).toInt()
        val step = object : Runnable {
            override fun run() {
                if (frames-- <= 0 || editor.adjustTool.liquify !== lq) return
                lq.stroke(p.x, p.y, vx, vy, 1f)
                p = PointF(p.x + vx, p.y + vy)
                vx *= 0.9f; vy *= 0.9f
                invalidate()
                postOnAnimation(this)
            }
        }
        postOnAnimation(step)
    }

    private fun transformDown(x: Float, y: Float) {
        val s = editor.transformTool.session ?: return
        val sc = transformScreenCorners() ?: return
        val r = 24f * density
        tfHit = 9
        if (s.mode == TransformMode.WARP) {
            val w = s.warpPoints().copyOf(); viewMatrix.mapPoints(w)
            tfHit = 99
            for (k in 0 until 16) if (hypot(w[k * 2] - x, w[k * 2 + 1] - y) < r) { tfHit = 100 + k; break }
            return
        }
        if (s.mode != TransformMode.DISTORT) {
            val rh = rotationHandle(sc)
            if (hypot(rh.x - x, rh.y - y) < r) {
                tfHit = 8
                val c = toScreen(s.center().x, s.center().y)
                tfStartAngle = atan2(y - c.y, x - c.x)
                return
            }
        }
        for (i in 0 until 4) if (hypot(sc[i * 2] - x, sc[i * 2 + 1] - y) < r) { tfHit = i; return }
        for (e in 0 until 4) {
            val a = e; val b = (e + 1) % 4
            if (hypot((sc[a * 2] + sc[b * 2]) / 2 - x, (sc[a * 2 + 1] + sc[b * 2 + 1]) / 2 - y) < r) { tfHit = 4 + e; return }
        }
    }

    private fun transformMove(x: Float, y: Float, d: PointF, prevD: PointF) {
        val s = editor.transformTool.session ?: return
        when {
            tfHit in 0..3 -> s.dragCorner(tfHit, d)
            tfHit in 4..7 -> s.dragEdge(tfHit - 4, d)
            tfHit == 8 -> {
                val c = toScreen(s.center().x, s.center().y)
                val a = atan2(y - c.y, x - c.x)
                s.rotate(Math.toDegrees((a - tfStartAngle).toDouble()).toFloat())
                tfStartAngle = a
            }
            tfHit >= 100 -> s.warpDrag(d, d.x - prevD.x, d.y - prevD.y, tfHit - 100)
            tfHit == 99 -> s.warpDrag(d, d.x - prevD.x, d.y - prevD.y, -1)
            else -> {
                var dx = d.x - prevD.x; var dy = d.y - prevD.y
                if (s.magnetics) { if (abs(x - downX) > abs(y - downY)) dy = 0f else dx = 0f }
                s.translate(dx, dy)
            }
        }
        editor.transformTool.changed()
    }

    private fun guideDown(x: Float, y: Float) {
        val gc = doc.guides
        val r = 26f * density
        guideHit = -1
        if (gc.type == GuideType.PERSPECTIVE) {
            gc.vanishingPoints.forEachIndexed { i, vp -> val s = toScreen(vp.x, vp.y); if (hypot(s.x - x, s.y - y) < r) guideHit = i }
            if (guideHit < 0 && gc.vanishingPoints.size < 3) {
                gc.vanishingPoints.add(toDoc(x, y)); guideHit = gc.vanishingPoints.size - 1
                editor.version++
            }
        } else {
            val c = toScreen(gc.centerX(doc.width), gc.centerY(doc.height))
            val rh = rotationHandleGuide()
            guideHit = when {
                hypot(rh.x - x, rh.y - y) < r -> 101
                hypot(c.x - x, c.y - y) < r * 1.5f -> 100
                else -> -1
            }
        }
    }

    private fun guideMove(d: PointF, prevD: PointF, x: Float, y: Float) {
        val gc = doc.guides
        when {
            gc.type == GuideType.PERSPECTIVE && guideHit in gc.vanishingPoints.indices -> gc.vanishingPoints[guideHit] = d
            guideHit == 100 -> { gc.cx = gc.centerX(doc.width) + d.x - prevD.x; gc.cy = gc.centerY(doc.height) + d.y - prevD.y }
            guideHit == 101 -> {
                val c = toScreen(gc.centerX(doc.width), gc.centerY(doc.height))
                var a = Math.toDegrees(atan2((y - c.y).toDouble(), (x - c.x).toDouble())).toFloat() + 90f - rotationDeg
                val snapped = (a / 15f).roundToInt() * 15f
                if (abs(a - snapped) < 3f) a = snapped
                gc.rotationDeg = a
            }
        }
    }

    // ------------------------------------------------------------------ keyboard helpers
    fun zoomBy(f: Float) {
        val s = (scale * f).coerceIn(0.02f, 64f) / scale
        viewMatrix.postScale(s, s, width / 2f, height / 2f)
        invalidate()
    }

    @Suppress("unused")
    private fun distance(a: PointF, b: PointF) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    @Suppress("unused")
    private val adjustTypes = AdjustmentType.entries
    @Suppress("unused")
    private val uptime get() = SystemClock.uptimeMillis()
}
