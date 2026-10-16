package com.prosketch.app.ui.editor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prosketch.app.data.BrushStore
import com.prosketch.app.data.PaletteStore
import com.prosketch.app.data.Prefs
import com.prosketch.app.data.PressureCurve
import com.prosketch.app.data.ProjectStore
import com.prosketch.app.data.TimelapseRecorder
import com.prosketch.app.engine.BackgroundEntry
import com.prosketch.app.engine.Bitmaps
import com.prosketch.app.engine.Brush
import com.prosketch.app.engine.BrushStroke
import com.prosketch.app.engine.CompoundEntry
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.DefaultBrushes
import com.prosketch.app.engine.Document
import com.prosketch.app.engine.DocumentEntry
import com.prosketch.app.engine.FloodFill
import com.prosketch.app.engine.History
import com.prosketch.app.engine.HistoryHost
import com.prosketch.app.engine.InputPoint
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.engine.PixelEntry
import com.prosketch.app.engine.PropsEntry
import com.prosketch.app.engine.QShape
import com.prosketch.app.engine.QuickShapeRecognizer
import com.prosketch.app.engine.RenderHook
import com.prosketch.app.engine.SelectionEntry
import com.prosketch.app.engine.SelectionOps
import com.prosketch.app.engine.SmudgeStroke
import com.prosketch.app.engine.SnapAssist
import com.prosketch.app.engine.StrokeKind
import com.prosketch.app.engine.StructureEntry
import com.prosketch.app.engine.TiledBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Tool(val label: String) { BRUSH("페인트"), SMUDGE("문지르기"), ERASER("지우기") }
enum class Mode { PAINT, SELECT, TRANSFORM, ADJUST, LIQUIFY, GUIDE_EDIT }
enum class Panel { BRUSHES, LAYERS, COLOR, ACTIONS, ADJUSTMENTS, BRUSH_STUDIO }

class Hud(val text: String, val time: Long = SystemClock.uptimeMillis(), val action: Pair<String, () -> Unit>? = null)

class ClipboardContent(val bitmap: Bitmap, val left: Int, val top: Int)

class QuickShapeState(val shape: QShape, val anchor: PointF, var current: QShape, var perfect: Boolean = false)

class Editor(
    val ctx: Context,
    override val doc: Document,
    val store: ProjectStore,
    val prefs: Prefs,
    val brushes: BrushStore,
    val palettes: PaletteStore,
    val scope: CoroutineScope,
) : HistoryHost {

    // ------------------------------------------------------------------ observable UI state
    var version by mutableIntStateOf(0)
    var tool by mutableStateOf(Tool.BRUSH)
    var mode by mutableStateOf(Mode.PAINT)
    var panel by mutableStateOf<Panel?>(null)
    var color by mutableIntStateOf(Color.BLACK)
    var previousColor by mutableIntStateOf(Color.WHITE)
    var hue by mutableFloatStateOf(0f)        // kept separately so greys keep their hue
    val toolBrush = mutableStateMapOf<Tool, String>()
    val toolSize = mutableStateMapOf<Tool, Float>()
    val toolOpacity = mutableStateMapOf<Tool, Float>()
    var hud by mutableStateOf<Hud?>(null)
    var fullscreen by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var selectionVersion by mutableIntStateOf(0)
    var continueFill by mutableStateOf(false)
    var copyPasteMenu by mutableStateOf(false)
    var referenceVisible by mutableStateOf(false)
    var referenceImage by mutableStateOf<Bitmap?>(null)
    var referenceVersion by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var playFrame by mutableIntStateOf(0)
    var studioBrushId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var recentColors by mutableStateOf(prefs.recentColors)
    var layerOpacityTarget by mutableStateOf<Layer?>(null)   // two-finger tap on layer → canvas slider
    var soloLayer by mutableStateOf<Layer?>(null)
    var eyedropperArmed by mutableStateOf(false)     // Modify button tapped → next touch samples colour
    var eyedropperHeld by mutableStateOf(false)      // Modify button held → touches sample colour
    var penDetected by mutableStateOf(false)
    var zoomText by mutableStateOf<String?>(null)

    val selectionTool = SelectionTool(this)
    val transformTool = TransformTool(this)
    val adjustTool = AdjustTool(this)

    var invalidator: (() -> Unit)? = null
    var fitRequest: (() -> Unit)? = null
    fun invalidate() = invalidator?.invoke() ?: Unit

    // ------------------------------------------------------------------ engine state
    private val totalMem: Long = run {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }
    val maxLayers get() = doc.maxLayers(totalMem)
    val history = History(this, (totalMem * 0.07).toLong().coerceIn(96L shl 20, 640L shl 20))
    var strokeBuffer = TiledBuffer(doc.width, doc.height)
        private set
    var selection: Bitmap? = null
        private set
    var selectionOverlay: Pair<Bitmap, Float>? = null
        private set
    var clipboard: ClipboardContent? = null
    var pressureCurve = PressureCurve(prefs.pressureCurve)
    val timelapse = TimelapseRecorder(store.timelapseDir(doc.id))
    private val saveDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())
    private var sessionStart = SystemClock.elapsedRealtime()
    var dirty = false
        private set

    init {
        history.listener = { canUndo = history.canUndo; canRedo = history.canRedo }
        loadToolState()
        if (doc.layers.isEmpty()) doc.layers.add(Layer.blank(doc.width, doc.height, "레이어 1"))
    }

    // ------------------------------------------------------------------ HistoryHost
    override fun layerPixelsChanged(layer: Layer) { layer.touch(); bump() }
    override fun structureChanged() { bump() }
    override fun setSelectionMask(mask: Bitmap?) { applySelection(mask, record = false) }
    override fun documentResized() {
        strokeBuffer.release()
        strokeBuffer = TiledBuffer(doc.width, doc.height)
        bump()
        fitRequest?.invoke()
    }

    fun bump() {
        version++
        dirty = true
        invalidate()
        scheduleAutosave()
    }

    fun hud(text: String, action: Pair<String, () -> Unit>? = null) { hud = Hud(text, action = action) }

    // ------------------------------------------------------------------ tools & brushes
    private fun loadToolState() {
        val o = prefs.toolState
        for (t in Tool.entries) {
            val def = when (t) { Tool.BRUSH -> "b_studio"; Tool.SMUDGE -> "b_soft_air"; Tool.ERASER -> "b_soft_air" }
            toolBrush[t] = o.optString("brush_$t", def).let { if (brushes.get(it) != null) it else def }
            toolSize[t] = o.optDouble("size_$t", (brushes.get(toolBrush[t]!!)?.defaultSize ?: 0.35f).toDouble()).toFloat()
            toolOpacity[t] = o.optDouble("opacity_$t", if (t == Tool.SMUDGE) 0.6 else 1.0).toFloat()
        }
        color = o.optLong("color", 0xFF1A1A1AL).toInt()
        previousColor = o.optLong("prevColor", 0xFFFFFFFFL).toInt()
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv); hue = hsv[0]
    }

    fun saveToolState() {
        val o = JSONObject()
        for (t in Tool.entries) {
            o.put("brush_$t", toolBrush[t]); o.put("size_$t", toolSize[t]?.toDouble()); o.put("opacity_$t", toolOpacity[t]?.toDouble())
        }
        o.put("color", color.toLong() and 0xFFFFFFFFL); o.put("prevColor", previousColor.toLong() and 0xFFFFFFFFL)
        prefs.toolState = o
    }

    fun brushFor(t: Tool): Brush = brushes.get(toolBrush[t] ?: "b_studio") ?: DefaultBrushes.all().first()
    val currentBrush: Brush get() = brushFor(tool)

    fun selectTool(t: Tool) {
        if (mode != Mode.PAINT) exitModes()
        continueFill = false
        if (tool == t && panel == null) { panel = Panel.BRUSHES; return }
        if (tool == t && panel == Panel.BRUSHES) { panel = null; return }
        tool = t
        panel = null
    }

    /** Tap-and-hold on an unselected tool: carry over the brush of the current tool. */
    fun selectToolWithCurrentBrush(t: Tool) {
        val b = toolBrush[tool]
        if (b != null) { toolBrush[t] = b; toolSize[t] = toolSize[tool] ?: 0.3f }
        tool = t
        hud("${t.label}: ${brushFor(t).name}")
    }

    fun chooseBrush(id: String) {
        toolBrush[tool] = id
        brushes.markRecent(id)
        brushes.get(id)?.let { toolSize[tool] = it.defaultSize; if (tool != Tool.SMUDGE) toolOpacity[tool] = it.defaultOpacity }
        saveToolState()
    }

    /** Brush diameter in document pixels for the current tool. */
    fun brushDocSize(screenToDoc: Float, t: Tool = tool): Float {
        val b = brushFor(t)
        val docScale = (max(doc.width, doc.height) / 2560f).coerceIn(0.4f, 3f)
        val px = b.sizeFor(toolSize[t] ?: 0.3f) * docScale
        return if (prefs.screenRelativeBrush) px * screenToDoc / docScale.coerceAtLeast(0.01f) else px
    }

    // ------------------------------------------------------------------ colour
    fun setColor(c: Int, commitHistory: Boolean = true) {
        val opaque = c or (0xFF shl 24)
        if (opaque != color && commitHistory) previousColor = color
        color = opaque
        val hsv = FloatArray(3); Color.colorToHSV(opaque, hsv)
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
        if (commitHistory) pushRecentColor(opaque)
    }

    fun pushRecentColor(c: Int) {
        val l = recentColors.toMutableList()
        l.remove(c); l.add(0, c)
        while (l.size > 10) l.removeAt(l.size - 1)
        recentColors = l
        prefs.recentColors = l
    }

    fun swapWithPrevious() { val p = previousColor; previousColor = color; setColor(p, commitHistory = false); hud("이전 색상") }

    // ------------------------------------------------------------------ stroke handling
    private var stroke: BrushStroke? = null
    private var smudge: SmudgeStroke? = null
    private var smudgeBefore: Bitmap? = null
    private var strokeLayer: Layer? = null
    private var strokeKind = StrokeKind.PAINT
    private var assist: SnapAssist? = null
    private val pendingAssist = ArrayList<InputPoint>()
    private val rawPoints = ArrayList<PointF>()
    private var pressureSum = 0f
    private var pressureCount = 0
    var quickShape: QuickShapeState? = null
        private set
    private var lastRaw: InputPoint? = null
    private var strokeStylus = false
    val strokeActive get() = stroke != null || smudge != null

    fun canPaint(): Boolean {
        val l = doc.active ?: return false
        if (!l.visible) { hud("숨겨진 레이어에는 그릴 수 없습니다"); return false }
        if (l.locked) { hud("잠긴 레이어입니다"); return false }
        return true
    }

    fun beginStroke(p0: InputPoint, stylus: Boolean, screenToDoc: Float, eraserOverride: Boolean): Boolean {
        if (strokeActive) cancelStroke()
        if (!canPaint()) return false
        if (playing) playing = false
        val layer = doc.active!!
        val p = p0.copy(pressure = if (stylus) pressureCurve.map(p0.pressure) else 1f)
        val t = if (eraserOverride) Tool.ERASER else tool
        val brush = brushFor(t)
        val size = brushDocSize(screenToDoc, t)
        val opacity = brush.opacityFor(toolOpacity[t] ?: 1f)
        strokeLayer = layer
        strokeStylus = stylus
        rawPoints.clear(); pendingAssist.clear(); quickShape = null
        rawPoints.add(PointF(p.x, p.y)); pressureSum = p.pressure; pressureCount = 1; lastRaw = p
        assist = if (doc.guides.snaps) SnapAssist(doc.guides, doc.width, doc.height, 10f * screenToDoc).also { it.project(p.x, p.y) } else null
        val sym = doc.guides.symmetryTransforms(doc.width, doc.height)
        if (t == Tool.SMUDGE) {
            smudgeBefore = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            smudge = SmudgeStroke(brush, layer.bitmap, size, (toolOpacity[t] ?: 0.6f).coerceIn(0.02f, 1f), stylus, screenToDoc, selection, layer.alphaLock)
                .also { it.begin(p) }
        } else {
            strokeKind = if (t == Tool.ERASER) StrokeKind.ERASE else StrokeKind.PAINT
            val bmp = layer.bitmap
            val sampler: ((Float, Float) -> Int)? = if (brush.pull > 0f && strokeKind == StrokeKind.PAINT) { x, y ->
                bmp.getPixel(x.toInt().coerceIn(0, bmp.width - 1), y.toInt().coerceIn(0, bmp.height - 1))
            } else null
            strokeBuffer.clear()
            stroke = BrushStroke(brush, strokeKind, color, size, opacity, strokeBuffer, sym, stylus, screenToDoc, sampler).also { it.begin(p) }
        }
        invalidate()
        return true
    }

    fun strokeTo(p0: InputPoint) {
        if (!strokeActive) return
        val p = p0.copy(pressure = if (strokeStylus) pressureCurve.map(p0.pressure) else 1f)
        lastRaw = p
        val qs = quickShape
        if (qs != null) { adjustQuickShape(PointF(p.x, p.y)); return }
        rawPoints.add(PointF(p.x, p.y)); pressureSum += p.pressure; pressureCount++
        val a = assist
        if (a != null) {
            if (!a.decided) {
                pendingAssist.add(p)
                if (a.project(p.x, p.y) == null) return
                for (q in pendingAssist) a.project(q.x, q.y)?.let { feed(q.copy(x = it.x, y = it.y)) }
                pendingAssist.clear()
                return
            }
            val pr = a.project(p.x, p.y) ?: return
            feed(p.copy(x = pr.x, y = pr.y))
            return
        }
        feed(p)
    }

    private fun feed(p: InputPoint) {
        stroke?.add(p)
        smudge?.add(p)
        invalidate()
    }

    fun endStroke() {
        val layer = strokeLayer
        val qs = quickShape
        try {
            if (smudge != null && layer != null) {
                smudge!!.end()
                val r = smudge!!.dirtyRect()
                val before = smudgeBefore
                if (r != null && before != null) {
                    r.intersect(0, 0, doc.width, doc.height)
                    history.push(PixelEntry("문지르기", layer, r, Bitmaps.crop(before, r), Bitmaps.crop(layer.bitmap, r)))
                    layer.touch(); doc.strokeCount++
                    afterCommit()
                }
                return
            }
            val s = stroke ?: return
            if (qs == null) {
                if (assist != null && pendingAssist.isNotEmpty()) for (q in pendingAssist) feed(q)
                s.end()
            }
            commitStroke(layer ?: return, s, if (qs != null) "QuickShape" else if (strokeKind == StrokeKind.ERASE) "지우기" else "브러시")
        } finally {
            smudgeBefore?.recycle(); smudgeBefore = null
            stroke = null; smudge = null; strokeLayer = null; quickShape = null; assist = null
            invalidate()
        }
    }

    fun cancelStroke() {
        val layer = strokeLayer
        smudgeBefore?.let { b -> if (layer != null) Bitmaps.paste(layer.bitmap, b, 0, 0); layer?.touch(); b.recycle() }
        smudgeBefore = null
        stroke = null; smudge = null; strokeLayer = null; quickShape = null; assist = null
        strokeBuffer.clear()
        invalidate()
    }

    fun strokePaint(): Paint {
        val s = stroke
        val l = strokeLayer
        return Paint().apply {
            blendMode = when {
                strokeKind == StrokeKind.ERASE -> BlendMode.DST_OUT
                l?.alphaLock == true -> BlendMode.SRC_ATOP
                else -> s?.brush?.strokeBlend?.mode ?: BlendMode.SRC_OVER
            }
            alpha = ((s?.compositeOpacity ?: 1f) * 255f).toInt().coerceIn(0, 255)
        }
    }

    private val dstIn = Paint().apply { blendMode = BlendMode.DST_IN }

    private fun commitStroke(layer: Layer, s: BrushStroke, label: String) {
        val r = strokeBuffer.dirtyRect() ?: run { strokeBuffer.clear(); return }
        val before = Bitmaps.crop(layer.bitmap, r)
        val c = Canvas(layer.bitmap)
        c.clipRect(r)
        c.saveLayer(RectF(r), strokePaint())
        strokeBuffer.drawTo(c, null)
        selection?.let { c.drawBitmap(it, 0f, 0f, dstIn) }
        c.restore()
        val after = Bitmaps.crop(layer.bitmap, r)
        strokeBuffer.clear()
        history.push(PixelEntry(label, layer, r, before, after))
        layer.touch()
        doc.strokeCount++
        afterCommit()
    }

    /** Draws the in-progress stroke over its layer (used by the render hook). */
    fun drawStrokeLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean {
        if (layer !== strokeLayer || stroke == null || strokeBuffer.isEmpty) return false
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        canvas.saveLayer(bounds, paint)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        canvas.saveLayer(bounds, strokePaint())
        strokeBuffer.drawTo(canvas, null)
        selection?.let { canvas.drawBitmap(it, 0f, 0f, dstIn) }
        canvas.restore()
        canvas.restore()
        return true
    }

    fun afterCommit() {
        bump()
        captureTimelapse()
        if (referenceVisible && referenceImage == null) referenceVersion++
    }

    // ------------------------------------------------------------------ QuickShape
    fun tryQuickShape(): Boolean {
        if (!prefs.quickShape || stroke == null || quickShape != null || rawPoints.size < 4) return false
        val shape = QuickShapeRecognizer.recognize(rawPoints) ?: return false
        val anchor = lastRaw?.let { PointF(it.x, it.y) } ?: rawPoints.last()
        quickShape = QuickShapeState(shape, anchor, shape)
        renderQuickShape()
        hud("QuickShape: ${shape.label}")
        return true
    }

    fun quickShapePerfect() {
        val qs = quickShape ?: return
        qs.perfect = !qs.perfect
        lastRaw?.let { adjustQuickShape(PointF(it.x, it.y)) } ?: renderQuickShape()
        hud(if (qs.perfect) "완벽한 ${qs.current.label}" else qs.shape.label)
    }

    private fun adjustQuickShape(p: PointF) {
        val qs = quickShape ?: return
        var cur: QShape = when (val s = qs.shape) {
            is QShape.Line -> QShape.Line(s.a, p)
            else -> {
                val c = s.center()
                val d0 = hypot(qs.anchor.x - c.x, qs.anchor.y - c.y).coerceAtLeast(1f)
                val sc = hypot(p.x - c.x, p.y - c.y) / d0
                val rot = atan2(p.y - c.y, p.x - c.x) - atan2(qs.anchor.y - c.y, qs.anchor.x - c.x)
                val cr = cos(rot); val sr = sin(rot)
                s.mapped { q ->
                    val dx = (q.x - c.x) * sc; val dy = (q.y - c.y) * sc
                    PointF(c.x + dx * cr - dy * sr, c.y + dx * sr + dy * cr)
                }
            }
        }
        if (qs.perfect) cur = cur.perfect()
        qs.current = cur
        renderQuickShape()
    }

    private fun renderQuickShape() {
        val qs = quickShape ?: return
        val shape = if (qs.perfect && qs.current === qs.shape) qs.shape.perfect() else qs.current
        stroke?.replacePath(shape.path(), (pressureSum / pressureCount.coerceAtLeast(1)).coerceIn(0.2f, 1f))
        invalidate()
    }

    // ------------------------------------------------------------------ eyedropper
    fun sampleColor(x: Float, y: Float, layerOnly: Boolean): Int? {
        val ix = x.toInt(); val iy = y.toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return null
        val c = if (layerOnly) doc.active?.bitmap?.getPixel(ix, iy) ?: return null
        else Compositor.sampleComposite(doc, ix, iy, renderHook)
        if (Color.alpha(c) < 8) return if (layerOnly) null else (if (doc.backgroundVisible) doc.backgroundColor else null)
        return c or (0xFF shl 24)
    }

    // ------------------------------------------------------------------ fill / ColorDrop
    var fillPreview: Bitmap? = null
        private set
    private var fillLayer: Layer? = null
    private var fillPoint: PointF? = null
    private var fillRef: IntArray? = null
    private var fillJob = 0
    var fillThreshold by mutableFloatStateOf(prefs.colorDropThreshold)

    private fun referencePixels(): IntArray {
        val ref = doc.layers.firstOrNull { it.isReference } ?: doc.active!!
        val px = IntArray(doc.width * doc.height)
        ref.bitmap.getPixels(px, 0, doc.width, 0, 0, doc.width, doc.height)
        return px
    }

    /** Starts a ColorDrop preview at a document point (hold to adjust threshold). */
    fun colorDropPreview(x: Float, y: Float) {
        if (!canPaint()) return
        fillLayer = doc.active
        fillPoint = PointF(x, y)
        if (fillRef == null) fillRef = referencePixels()
        recomputeFill()
    }

    fun colorDropAdjust(dxFraction: Float) {
        if (fillPoint == null) return
        fillThreshold = (fillThreshold + dxFraction).coerceIn(0f, 1f)
        hud("ColorDrop 임계값: ${(fillThreshold * 100).toInt()}%")
        recomputeFill()
    }

    private fun recomputeFill() {
        val layer = fillLayer ?: return
        val pt = fillPoint ?: return
        val ref = fillRef ?: return
        val job = ++fillJob
        val th = fillThreshold
        val c = color
        val sel = selection?.let { SelectionOps.toBytes(it) }
        val w = doc.width; val h = doc.height
        scope.launch(Dispatchers.Default) {
            val region = FloodFill.region(ref, w, h, pt.x.toInt(), pt.y.toInt(), th) ?: return@launch
            val px = IntArray(w * h)
            layer.bitmap.getPixels(px, 0, w, 0, 0, w, h)
            FloodFill.applyFill(px, w, region, c, sel, layer.alphaLock)
            withContext(Dispatchers.Main) {
                if (job != fillJob) return@withContext
                val pv = fillPreview?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pv.setPixels(px, 0, w, 0, 0, w, h)
                fillPreview = pv
                fillBounds = region.bounds
                invalidate()
            }
        }
    }

    private var fillBounds: Rect? = null

    fun colorDropCommit(x: Float, y: Float, immediate: Boolean) {
        if (immediate || fillPreview == null) {
            if (!canPaint()) return
            val layer = doc.active!!
            val ref = fillRef ?: referencePixels()
            val w = doc.width; val h = doc.height
            val region = FloodFill.region(ref, w, h, x.toInt(), y.toInt(), fillThreshold) ?: run { endFill(); return }
            val px = IntArray(w * h)
            layer.bitmap.getPixels(px, 0, w, 0, 0, w, h)
            FloodFill.applyFill(px, w, region, color, selection?.let { SelectionOps.toBytes(it) }, layer.alphaLock)
            val r = Rect(region.bounds).apply { intersect(0, 0, w, h) }
            val before = Bitmaps.crop(layer.bitmap, r)
            layer.bitmap.setPixels(px, 0, w, 0, 0, w, h)
            history.push(PixelEntry("ColorDrop", layer, r, before, Bitmaps.crop(layer.bitmap, r)))
            layer.touch(); afterCommit()
        } else {
            val layer = fillLayer!!
            val r = Rect(fillBounds ?: Rect(0, 0, doc.width, doc.height)).apply { intersect(0, 0, doc.width, doc.height) }
            val before = Bitmaps.crop(layer.bitmap, r)
            Bitmaps.paste(layer.bitmap, Bitmaps.crop(fillPreview!!, r), r.left, r.top)
            history.push(PixelEntry("ColorDrop", layer, r, before, Bitmaps.crop(layer.bitmap, r)))
            layer.touch(); afterCommit()
        }
        prefs.colorDropThreshold = fillThreshold
        endFill()
        if (!continueFill) hud("ColorDrop", "계속 채우기" to { continueFill = true; hud("탭하여 계속 채우기") })
    }

    fun endFill() {
        fillJob++
        fillPoint = null; fillLayer = null; fillRef = null; fillBounds = null
        fillPreview?.recycle(); fillPreview = null
        invalidate()
    }

    fun drawFillLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean {
        val pv = fillPreview ?: return false
        if (layer !== fillLayer) return false
        canvas.drawBitmap(pv, 0f, 0f, paint)
        return true
    }

    // ------------------------------------------------------------------ layers
    private fun snapshotLayers() = doc.layers.toList()

    private fun pushStructure(label: String, before: List<Layer>, beforeActive: Int) {
        history.push(StructureEntry(label, before, beforeActive, snapshotLayers(), doc.activeIndex))
        doc.layers.forEach { it.touch() }
        bump()
    }

    fun nextLayerName(): String {
        var n = doc.layers.size + 1
        while (doc.layers.any { it.name == "레이어 $n" }) n++
        return "레이어 $n"
    }

    fun checkLayerLimit(): Boolean {
        if (doc.layers.size >= maxLayers) { hud("최대 레이어 수(${maxLayers}개)에 도달했습니다"); return false }
        return true
    }

    fun addLayer(above: Int = doc.activeIndex, layer: Layer? = null, label: String = "새 레이어"): Layer? {
        if (!checkLayerLimit()) return null
        val before = snapshotLayers(); val ba = doc.activeIndex
        val l = layer ?: Layer.blank(doc.width, doc.height, nextLayerName())
        val idx = (above + 1).coerceIn(0, doc.layers.size)
        doc.layers.add(idx, l)
        doc.activeIndex = idx
        pushStructure(label, before, ba)
        return l
    }

    fun selectLayer(i: Int) {
        if (i !in doc.layers.indices) return
        if (mode == Mode.TRANSFORM || mode == Mode.ADJUST || mode == Mode.LIQUIFY) exitModes()
        doc.activeIndex = i
        version++
        invalidate()
    }

    fun deleteLayer(i: Int) {
        if (doc.layers.size <= 1) {
            // keep at least one layer: clear it instead
            clearLayer(i); return
        }
        val before = snapshotLayers(); val ba = doc.activeIndex
        doc.layers.removeAt(i)
        if (doc.layers[0].clipping) doc.layers[0].clipping = false
        doc.activeIndex = (if (i <= doc.activeIndex) doc.activeIndex - 1 else doc.activeIndex).coerceIn(0, doc.layers.size - 1)
        pushStructure("레이어 삭제", before, ba)
    }

    fun duplicateLayer(i: Int) {
        if (!checkLayerLimit()) return
        val before = snapshotLayers(); val ba = doc.activeIndex
        val src = doc.layers[i]
        doc.layers.add(i + 1, src.copy(src.name))
        doc.activeIndex = i + 1
        pushStructure("레이어 복제", before, ba)
    }

    fun moveLayer(from: Int, to: Int) {
        if (from == to || from !in doc.layers.indices || to !in doc.layers.indices) return
        val before = snapshotLayers(); val ba = doc.activeIndex
        val activeLayer = doc.active
        val l = doc.layers.removeAt(from)
        doc.layers.add(to, l)
        doc.activeIndex = doc.layers.indexOf(activeLayer).coerceAtLeast(0)
        if (doc.layers[0].clipping) doc.layers[0].clipping = false
        pushStructure("레이어 이동", before, ba)
    }

    fun mergeDown(i: Int) {
        if (i <= 0) { hud("아래에 병합할 레이어가 없습니다"); return }
        val before = snapshotLayers(); val ba = doc.activeIndex
        val top = doc.layers[i]; val bottom = doc.layers[i - 1]
        val merged = bottom.copy(bottom.name)
        val c = Canvas(merged.bitmap)
        if (top.visible) {
            val p = Compositor.layerPaint(top)
            if (top.clipping) {
                c.saveLayer(null, p)
                c.drawBitmap(top.bitmap, 0f, 0f, null)
                c.drawBitmap(bottom.bitmap, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
                c.restore()
            } else c.drawBitmap(top.bitmap, 0f, 0f, p)
        }
        doc.layers.removeAt(i)
        doc.layers[i - 1] = merged
        doc.activeIndex = i - 1
        pushStructure("아래 레이어와 병합", before, ba)
    }

    /** Pinch-merge: merge the inclusive range into the bottom layer. */
    fun mergeRange(a: Int, b: Int) {
        val lo = min(a, b); val hi = max(a, b)
        if (lo == hi) return
        val before = snapshotLayers(); val ba = doc.activeIndex
        val base = doc.layers[lo].copy()
        val c = Canvas(base.bitmap)
        for (k in lo + 1..hi) {
            val l = doc.layers[k]
            if (l.visible) c.drawBitmap(l.bitmap, 0f, 0f, Compositor.layerPaint(l))
        }
        repeat(hi - lo) { doc.layers.removeAt(lo + 1) }
        doc.layers[lo] = base
        doc.activeIndex = lo
        pushStructure("레이어 병합", before, ba)
    }

    fun changeProps(layer: Layer, label: String, f: (Layer) -> Unit) {
        val b = layer.props()
        f(layer)
        layer.touch()
        val a = layer.props()
        if (a != b) history.push(PropsEntry(label, layer, b, a))
        bump()
    }

    /** Live opacity change without history; commit with [commitOpacity]. */
    private var opacityStart: com.prosketch.app.engine.LayerProps? = null
    fun setOpacityLive(layer: Layer, v: Float) {
        if (opacityStart == null) opacityStart = layer.props()
        layer.opacity = v.coerceIn(0f, 1f); layer.touch(); version++; invalidate()
    }
    fun commitOpacity(layer: Layer) {
        val b = opacityStart ?: return
        opacityStart = null
        history.push(PropsEntry("불투명도", layer, b, layer.props()))
        bump()
    }

    fun setBlend(layer: Layer, m: LayerBlend) = changeProps(layer, "혼합 모드") { it.blend = m }
    fun toggleVisible(layer: Layer) = changeProps(layer, "가시성") { it.visible = !it.visible }
    fun toggleAlphaLock(layer: Layer) { changeProps(layer, "알파 채널 잠금") { it.alphaLock = !it.alphaLock }; hud(if (layer.alphaLock) "알파 채널 잠금 켬" else "알파 채널 잠금 끔") }
    fun toggleLock(layer: Layer) { changeProps(layer, "레이어 잠금") { it.locked = !it.locked }; hud(if (layer.locked) "레이어 잠김" else "레이어 잠금 해제") }
    fun toggleClipping(layer: Layer) {
        if (doc.indexOf(layer) == 0) { hud("맨 아래 레이어는 클리핑 마스크가 될 수 없습니다"); return }
        changeProps(layer, "클리핑 마스크") { it.clipping = !it.clipping }
    }
    fun toggleReference(layer: Layer) {
        val wasRef = layer.isReference
        doc.layers.forEach { if (it.isReference && it !== layer) changeProps(it, "참조") { l -> l.isReference = false } }
        changeProps(layer, "참조") { it.isReference = !wasRef }
        hud(if (layer.isReference) "참조 레이어로 설정" else "참조 해제")
    }
    fun rename(layer: Layer, name: String) = changeProps(layer, "이름 변경") { it.name = name.ifBlank { it.name } }

    fun soloToggle(layer: Layer) {
        soloLayer = if (soloLayer === layer) null else layer
        hud(if (soloLayer != null) "단독 가시성 켬" else "단독 가시성 끔")
        version++; invalidate()
    }

    fun setBackground(color: Int? = null, visible: Boolean? = null) {
        val bc = doc.backgroundColor; val bv = doc.backgroundVisible
        color?.let { doc.backgroundColor = it or (0xFF shl 24) }
        visible?.let { doc.backgroundVisible = it }
        doc.touchBackground()
        history.push(BackgroundEntry(bc, bv, doc.backgroundColor, doc.backgroundVisible))
        bump()
    }

    /** Edits pixels of [layer] inside [rect] with undo support. */
    fun editPixels(layer: Layer, label: String, rect: Rect = Rect(0, 0, doc.width, doc.height), block: (Canvas) -> Unit) {
        val r = Rect(rect).apply { intersect(0, 0, doc.width, doc.height) }
        if (r.isEmpty) return
        val before = Bitmaps.crop(layer.bitmap, r)
        val c = Canvas(layer.bitmap)
        c.clipRect(r)
        block(c)
        history.push(PixelEntry(label, layer, r, before, Bitmaps.crop(layer.bitmap, r)))
        layer.touch()
        afterCommit()
    }

    fun clearLayer(i: Int = doc.activeIndex) {
        val l = doc.layers.getOrNull(i) ?: return
        if (l.locked) { hud("잠긴 레이어입니다"); return }
        val sel = selection
        val r = sel?.let { Bitmaps.maskBounds(it) } ?: Bitmaps.opaqueBounds(l.bitmap) ?: return
        editPixels(l, "지우기", r) { c ->
            if (sel != null) c.drawBitmap(sel, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
            else c.drawColor(0, BlendMode.CLEAR)
        }
        hud(if (sel != null) "선택 영역 지우기" else "레이어 지우기")
    }

    fun fillLayer(i: Int = doc.activeIndex, c: Int = color) {
        val l = doc.layers.getOrNull(i) ?: return
        if (l.locked) { hud("잠긴 레이어입니다"); return }
        val sel = selection
        editPixels(l, "레이어 채우기", sel?.let { Bitmaps.maskBounds(it) } ?: Rect(0, 0, doc.width, doc.height)) { cv ->
            val p = Paint().apply {
                this.color = c
                blendMode = if (l.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
            }
            if (sel != null) {
                cv.saveLayer(null, p)
                cv.drawColor(c or (0xFF shl 24))
                cv.drawBitmap(sel, 0f, 0f, dstIn)
                cv.restore()
            } else cv.drawPaint(p)
        }
    }

    fun invertLayer(i: Int = doc.activeIndex) {
        val l = doc.layers.getOrNull(i) ?: return
        val w = doc.width; val h = doc.height
        val px = IntArray(w * h)
        l.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (k in px.indices) { val c = px[k]; px[k] = (c and 0xFF000000.toInt()) or (c.inv() and 0x00FFFFFF) }
        editPixels(l, "반전") { _ -> l.bitmap.setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun selectLayerAlpha(i: Int = doc.activeIndex) {
        val l = doc.layers.getOrNull(i) ?: return
        applySelection(SelectionOps.fromAlpha(l.bitmap), record = true, label = "레이어 선택")
        hud("레이어 내용 선택됨")
    }

    // ------------------------------------------------------------------ selection
    fun applySelection(mask: Bitmap?, record: Boolean, label: String = "선택") {
        val before = selection
        val m = mask?.takeIf { !SelectionOps.isEmpty(it) }
        if (record && (before != null || m != null)) {
            history.push(SelectionEntry(label, before?.copy(Bitmap.Config.ALPHA_8, false), m?.copy(Bitmap.Config.ALPHA_8, false)))
        }
        selection = m
        selectionOverlay = m?.let { SelectionOps.buildOverlay(it) }
        selectionVersion++
        invalidate()
    }

    fun clearSelection(record: Boolean = true) { if (selection != null) applySelection(null, record, "선택 해제") }

    // ------------------------------------------------------------------ clipboard (3-finger swipe menu)
    fun copy(all: Boolean = false): Boolean {
        val sel = selection
        val src: Bitmap = if (all) Compositor.flatten(doc, background = false) else (doc.active?.bitmap ?: return false)
        val r = (sel?.let { Bitmaps.maskBounds(it) } ?: Bitmaps.opaqueBounds(src)) ?: run { hud("복사할 내용이 없습니다"); return false }
        val crop = Bitmaps.crop(src, r)
        if (sel != null) Canvas(crop).drawBitmap(sel, -r.left.toFloat(), -r.top.toFloat(), dstIn)
        clipboard = ClipboardContent(crop, r.left, r.top)
        if (all) src.recycle()
        hud(if (all) "모두 복사됨" else "복사됨")
        return true
    }

    fun cut() { if (copy()) { clearLayer(); hud("잘라내기") } }

    fun paste(name: String = "붙여넣기") {
        val cb = clipboard ?: run { hud("클립보드가 비어 있습니다"); return }
        val l = Layer.blank(doc.width, doc.height, name)
        Canvas(l.bitmap).drawBitmap(cb.bitmap, cb.left.toFloat(), cb.top.toFloat(), null)
        addLayer(layer = l, label = "붙여넣기")
        hud("붙여넣기")
    }

    fun duplicateSelection() { if (copy()) paste("선택에서") }
    fun cutAndPaste() { if (copy()) { clearLayer(); paste("선택에서") } }

    // ------------------------------------------------------------------ insert / text
    fun insertImage(src: Bitmap, name: String = "삽입된 이미지") {
        if (!checkLayerLimit()) return
        val s = min(1f, min(doc.width / src.width.toFloat(), doc.height / src.height.toFloat()))
        val w = max(1, (src.width * s).toInt()); val h = max(1, (src.height * s).toInt())
        val placed = Rect((doc.width - w) / 2, (doc.height - h) / 2, (doc.width - w) / 2 + w, (doc.height - h) / 2 + h)
        val l = Layer.blank(doc.width, doc.height, name)
        val before = snapshotLayers(); val ba = doc.activeIndex
        Canvas(l.bitmap).drawBitmap(src, null, placed, Paint(Paint.FILTER_BITMAP_FLAG))
        doc.layers.add(doc.activeIndex + 1, l)
        doc.activeIndex += 1
        pushStructure(name, before, ba)
        exitModes()
        transformTool.begin()
    }

    fun addText(text: String, typeface: Typeface, sizePx: Float, align: Layout.Alignment) {
        if (text.isBlank()) return
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = sizePx; this.color = this@Editor.color }
        val maxW = (doc.width * 0.9f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW).setAlignment(align).build()
        var lw = 0f
        for (i in 0 until layout.lineCount) lw = max(lw, layout.getLineWidth(i))
        val w = max(1, lw.toInt() + 8); val h = max(1, layout.height + 8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(4f + when (align) { Layout.Alignment.ALIGN_CENTER -> -(maxW - lw) / 2f; Layout.Alignment.ALIGN_OPPOSITE -> -(maxW - lw); else -> 0f }, 4f)
        layout.draw(c)
        insertImage(bmp, text.lineSequence().first().take(20))
        bmp.recycle()
    }

    // ------------------------------------------------------------------ canvas operations
    fun resizeCanvas(newW: Int, newH: Int, offX: Int, offY: Int, resample: Boolean) {
        if (newW == doc.width && newH == doc.height && offX == 0 && offY == 0) return
        exitModes()
        val before = doc.layers.associateWith { it.bitmap }
        val after = HashMap<Layer, Bitmap>()
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in doc.layers) {
            val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            val c = Canvas(nb)
            if (resample) c.drawBitmap(l.bitmap, null, Rect(0, 0, newW, newH), p)
            else c.drawBitmap(l.bitmap, offX.toFloat(), offY.toFloat(), null)
            after[l] = nb
        }
        val e = DocumentEntry(if (resample) "캔버스 크기 조정" else "자르기 및 크기 조정", doc.width to doc.height, before, newW to newH, after)
        e.redo(this)
        history.push(e)
        hud("캔버스: ${newW} × ${newH}px")
    }

    fun flipCanvas(horizontal: Boolean) {
        exitModes()
        val before = doc.layers.associateWith { it.bitmap }
        val after = HashMap<Layer, Bitmap>()
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        for (l in doc.layers) {
            val nb = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(l.bitmap, m, null)
            after[l] = nb
        }
        val e = DocumentEntry(if (horizontal) "캔버스 수평 뒤집기" else "캔버스 수직 뒤집기", doc.width to doc.height, before, doc.width to doc.height, after)
        e.redo(this)
        history.push(e)
    }

    fun flipLayer(horizontal: Boolean) {
        val l = doc.active ?: return
        val copy = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        editPixels(l, "레이어 뒤집기") { c -> c.drawColor(0, BlendMode.CLEAR); c.drawBitmap(copy, m, null) }
        copy.recycle()
    }

    // ------------------------------------------------------------------ modes
    fun exitModes() {
        when (mode) {
            Mode.TRANSFORM -> transformTool.commit()
            Mode.ADJUST, Mode.LIQUIFY -> adjustTool.apply()
            Mode.SELECT -> selectionTool.finish()
            else -> {}
        }
        mode = Mode.PAINT
        invalidate()
    }

    fun undo() {
        if (strokeActive) { cancelStroke(); return }
        if (mode == Mode.TRANSFORM && transformTool.session != null) { transformTool.cancel(); hud("변형 취소"); return }
        if (mode == Mode.SELECT && selectionTool.undoPending()) return
        val l = history.undo()
        if (l != null) hud("실행 취소: $l") else hud("실행 취소할 항목이 없습니다")
        if (referenceVisible) referenceVersion++
    }

    fun redo() {
        val l = history.redo()
        if (l != null) hud("다시 실행: $l") else hud("다시 실행할 항목이 없습니다")
        if (referenceVisible) referenceVersion++
    }

    // ------------------------------------------------------------------ render hook
    val renderHook = object : RenderHook {
        override fun drawLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean {
            if (drawStrokeLive(canvas, layer, paint)) return true
            if (transformTool.drawLive(canvas, layer, paint)) return true
            if (adjustTool.drawLive(canvas, layer, paint)) return true
            if (drawFillLive(canvas, layer, paint)) return true
            return false
        }

        override fun isVisible(index: Int, layer: Layer): Boolean {
            soloLayer?.let { if (doc.layers.contains(it)) return layer === it }
            val a = doc.animation
            if (!a.enabled) return layer.visible
            if (!layer.visible) return false
            val cur = if (playing) playFrame else doc.activeIndex
            if (a.firstFrameBackground && index == 0) return true
            if (index == cur) return true
            if (playing) return false
            return kotlin.math.abs(index - cur) <= a.onionFrames
        }

        override fun extraAlpha(index: Int, layer: Layer): Float {
            val a = doc.animation
            if (!a.enabled || playing) return 1f
            val cur = doc.activeIndex
            if (index == cur || (a.firstFrameBackground && index == 0)) return 1f
            val d = kotlin.math.abs(index - cur)
            return a.onionOpacity * (1f - (d - 1f) / (a.onionFrames + 1f))
        }

        override fun colorFilter(index: Int, layer: Layer): android.graphics.ColorFilter? {
            val a = doc.animation
            if (!a.enabled || playing || !a.colorOnion) return null
            val cur = doc.activeIndex
            if (index == cur || (a.firstFrameBackground && index == 0)) return null
            val tint = if (index < cur) 0xFFE5484D.toInt() else 0xFF30A46C.toInt()
            return android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
    }

    // ------------------------------------------------------------------ animation
    fun animationFrames(): List<Int> {
        val n = doc.layers.size
        val start = if (doc.animation.firstFrameBackground && n > 1) 1 else 0
        return (start until n).toList()
    }

    // ------------------------------------------------------------------ timelapse
    fun captureTimelapse() {
        if (!doc.timelapseEnabled) return
        val shadow = doc.shallowCopy()
        val s = min(1f, 1280f / max(doc.width, doc.height))
        timelapse.capture { Compositor.flatten(shadow, s) }
    }

    // ------------------------------------------------------------------ persistence
    private val autosave = Runnable { save() }
    private fun scheduleAutosave() {
        main.removeCallbacks(autosave)
        main.postDelayed(autosave, 4000)
    }

    fun save(onDone: (() -> Unit)? = null) {
        main.removeCallbacks(autosave)
        if (strokeActive) { scheduleAutosave(); return }
        val now = SystemClock.elapsedRealtime()
        doc.timeSpentMs += now - sessionStart
        sessionStart = now
        val thumb = Compositor.flatten(doc, min(1f, 640f / max(doc.width, doc.height)))
        val snap = store.snapshot(doc, thumb)
        dirty = false
        scope.launch(saveDispatcher) {
            runCatching { store.write(snap) }
            thumb.recycle()
            if (onDone != null) withContext(Dispatchers.Main) { onDone() }
        }
        saveToolState()
    }

    fun close(onDone: () -> Unit) {
        if (strokeActive) endStroke()
        exitModes()
        save {
            strokeBuffer.release()
            onDone()
        }
    }
}
