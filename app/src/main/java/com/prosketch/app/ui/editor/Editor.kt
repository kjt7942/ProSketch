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
import androidx.compose.runtime.mutableStateListOf
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
import com.prosketch.app.engine.TextEntry
import com.prosketch.app.engine.TextRenderer
import com.prosketch.app.engine.TextSpec
import com.prosketch.app.engine.TreeState
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
enum class Panel { BRUSHES, LAYERS, COLOR, ACTIONS, ADJUSTMENTS, BRUSH_STUDIO, HISTORY }

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
    var studioBrushId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var recentColors by mutableStateOf(prefs.recentColors)
    var layerOpacityTarget by mutableStateOf<Layer?>(null)   // two-finger tap on layer → canvas slider
    var soloLayer by mutableStateOf<Layer?>(null)
    var eyedropperArmed by mutableStateOf(false)     // Modify button tapped → next touch samples colour
    var eyedropperHeld by mutableStateOf(false)      // Modify button held → touches sample colour
    var penDetected by mutableStateOf(false)
    var zoomText by mutableStateOf<String?>(null)
    /** Secondary layer selection (swipe right in the layers panel); the primary is [Document.activeLayer]. */
    val secondary = mutableStateListOf<Long>()
    /** QuickMenu centre (screen px) while open, and the button under the finger. */
    var quickMenuAt by mutableStateOf<PointF?>(null)
    var quickMenuHover by mutableIntStateOf(-1)
    /** View-only aids: mirrored display (checks proportions) and greyscale display (checks values). */
    var mirrorView by mutableStateOf(false)
    var grayscaleView by mutableStateOf(false)
    var mirrorRequest: ((Boolean) -> Unit)? = null

    fun toggleMirrorView() { mirrorView = !mirrorView; mirrorRequest?.invoke(mirrorView); hud(if (mirrorView) "좌우 반전 보기 (그림은 그대로)" else "좌우 반전 보기 끔") }
    fun toggleGrayscaleView() { grayscaleView = !grayscaleView; invalidate(); hud(if (grayscaleView) "흑백 보기: 명암 확인" else "흑백 보기 끔") }

    /** Undo or redo until [doneCount] steps remain done (history panel). */
    fun jumpHistory(doneCount: Int) {
        if (strokeActive) cancelStroke()
        if (mode != Mode.PAINT) exitModes()
        var guard = 300
        while (history.size > doneCount && guard-- > 0) history.undo() ?: break
        while (history.size < doneCount && guard-- > 0) history.redo() ?: break
        hud("작업 내역: ${history.size}단계")
        bump()
    }

    /** Text layer whose words are being edited in the text dialog. */
    var textEditing by mutableStateOf<Layer?>(null)

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
    /** Something is drawn live over a layer (stroke, transform, filter preview, fill preview, liquify). */
    val liveActive get() = strokeActive || transformTool.session != null || adjustTool.type != null || fillPreview != null

    fun canPaint(): Boolean {
        val l = doc.active ?: return false
        if (l.isGroup) { hud("그룹에는 그릴 수 없습니다. 그룹 안의 레이어를 선택하세요"); return false }
        if (!doc.effectiveVisible(l)) { hud("숨겨진 레이어에는 그릴 수 없습니다"); return false }
        if (doc.effectiveLocked(l)) { hud("잠긴 레이어입니다"); return false }
        if (l.isText) rasterizeText(l)
        return true
    }

    /** Masks are greyscale: paint with the luminance of the current colour. */
    fun paintColorFor(l: Layer): Int {
        if (!l.isMask) return color
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
        return Color.rgb(y, y, y)
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
            stroke = BrushStroke(brush, strokeKind, paintColorFor(layer), size, opacity, strokeBuffer, sym, stylus, screenToDoc, sampler).also { it.begin(p) }
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
        val act = doc.active
        val c = if (layerOnly) {
            if (act == null) return null
            if (act.isGroup) Compositor.renderNode(doc, act).let { b -> b.getPixel(ix, iy).also { b.recycle() } } else act.bitmap.getPixel(ix, iy)
        } else Compositor.sampleComposite(doc, ix, iy, renderHook)
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
        val src = if (ref.isGroup) Compositor.renderNode(doc, ref) else ref.bitmap
        src.getPixels(px, 0, doc.width, 0, 0, doc.width, doc.height)
        if (src !== ref.bitmap) src.recycle()
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
        val gap = prefs.colorDropGap
        val c = paintColorFor(layer)
        val sel = selection?.let { SelectionOps.toBytes(it) }
        val w = doc.width; val h = doc.height
        scope.launch(Dispatchers.Default) {
            val region = FloodFill.region(ref, w, h, pt.x.toInt(), pt.y.toInt(), th, gap = gap) ?: return@launch
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
            val region = FloodFill.region(ref, w, h, x.toInt(), y.toInt(), fillThreshold, gap = prefs.colorDropGap) ?: run { endFill(); return }
            val px = IntArray(w * h)
            layer.bitmap.getPixels(px, 0, w, 0, 0, w, h)
            FloodFill.applyFill(px, w, region, paintColorFor(layer), selection?.let { SelectionOps.toBytes(it) }, layer.alphaLock)
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
    private class Snap(val layers: List<Layer>, val active: Int, val tree: Map<Layer, TreeState>)
    private fun snap() = Snap(doc.layers.toList(), doc.activeIndex, doc.treeState())

    private fun pushStructure(label: String, before: Snap) {
        doc.normalize()
        history.push(StructureEntry(label, before.layers, before.active, before.tree, doc.layers.toList(), doc.activeIndex, doc.treeState()))
        doc.layers.forEach { it.touch() }
        val ids = doc.layers.map { it.id }.toSet()
        secondary.removeAll { it !in ids || it == doc.activeLayer?.id }
        bump()
    }

    fun nextLayerName(): String {
        var n = doc.layers.count { !it.isGroup } + 1
        while (doc.layers.any { it.name == "레이어 $n" }) n++
        return "레이어 $n"
    }

    fun checkLayerLimit(extra: Int = 1): Boolean {
        if (doc.pixelBudgetUsed() + extra > maxLayers) { hud("최대 레이어 수(${maxLayers}개)에 도달했습니다"); return false }
        return true
    }

    /** Adds [layer] (a new blank one by default) above the active layer, inside an open active group. */
    fun addLayer(layer: Layer? = null, label: String = "새 레이어"): Layer? {
        if (!checkLayerLimit(if (layer?.isGroup == true) 0 else 1)) return null
        val before = snap()
        val l = layer ?: Layer.blank(doc.width, doc.height, nextLayerName())
        val a = doc.activeLayer
        val (idx, parent) = when {
            a == null -> doc.layers.size to 0L
            a.isGroup && a.expanded -> doc.activeIndex to a.id
            a.isGroup -> doc.activeIndex + 1 to a.parentId
            else -> doc.activeIndex + 1 to a.parentId
        }
        l.parentId = parent
        doc.layers.add(idx.coerceIn(0, doc.layers.size), l)
        doc.activeIndex = doc.layers.indexOf(l)
        doc.editMask = false
        pushStructure(label, before)
        return l
    }

    fun selectLayer(i: Int, mask: Boolean = false) {
        if (i !in doc.layers.indices) return
        if (mode == Mode.TRANSFORM || mode == Mode.ADJUST || mode == Mode.LIQUIFY) exitModes()
        doc.activeIndex = i
        doc.editMask = mask && doc.layers[i].mask != null
        secondary.clear()
        version++
        invalidate()
    }

    /** Swipe right in the panel: add/remove a secondary selection. */
    fun toggleSecondary(l: Layer) {
        if (l === doc.activeLayer) return
        if (secondary.contains(l.id)) secondary.remove(l.id) else secondary.add(l.id)
        version++
    }

    /** Primary + secondary layers, without those already covered by a selected group, bottom → top. */
    fun selectedLayers(): List<Layer> {
        val ids = secondary.toSet() + listOfNotNull(doc.activeLayer?.id)
        return doc.layers.filter { it.id in ids }.filter { l -> doc.ancestors(l).none { it.id in ids } }
    }

    fun deleteLayer(i: Int) { doc.layers.getOrNull(i)?.let { deleteLayers(listOf(it)) } }

    fun deleteSelected() = deleteLayers(selectedLayers())

    fun deleteLayers(targets: List<Layer>) {
        val remove = LinkedHashSet<Layer>()
        for (t in targets) { val i = doc.layers.indexOf(t); if (i >= 0) doc.blockRange(i).forEach { remove.add(doc.layers[it]) } }
        if (remove.isEmpty()) return
        if (doc.layers.filter { !it.isGroup }.all { it in remove }) {
            // keep at least one layer: clear it instead
            if (targets.size == 1 && !targets[0].isGroup) { clearLayer(targets[0]); return }
            hud("마지막 레이어는 삭제할 수 없습니다"); return
        }
        val before = snap()
        val active = doc.activeLayer
        val lowest = doc.layers.indexOfFirst { it in remove }
        doc.layers.removeAll(remove)
        doc.activeIndex = if (active != null && active !in remove) doc.layers.indexOf(active) else (lowest - 1).coerceIn(0, doc.layers.size - 1)
        doc.editMask = false
        if (soloLayer in remove) soloLayer = null
        pushStructure(if (remove.size > 1) "레이어 ${targets.size}개 삭제" else "레이어 삭제", before)
    }

    /** Copies the layer or group at [i] with all descendants (new ids, parents remapped). */
    private fun copyBlock(i: Int): List<Layer> {
        val range = doc.blockRange(i)
        val idMap = HashMap<Long, Long>()
        val copies = range.map { k -> val src = doc.layers[k]; src.copy(src.name).also { idMap[src.id] = it.id } }
        copies.forEach { c -> idMap[c.parentId]?.let { c.parentId = it } }
        return copies
    }

    fun duplicateLayer(i: Int) {
        val src = doc.layers.getOrNull(i) ?: return
        val range = doc.blockRange(i)
        if (!checkLayerLimit(range.sumOf { k -> doc.layers[k].let { (if (it.isGroup) 0 else 1) + (if (it.mask != null) 1 else 0) } })) return
        val before = snap()
        val copies = copyBlock(i)
        doc.layers.addAll(range.last + 1, copies)
        doc.activeIndex = range.last + copies.size
        pushStructure(if (src.isGroup) "그룹 복제" else "레이어 복제", before)
    }

    /**
     * Moves [layer] (with its block) to the gap below the display row [rowAbove] (null = very top).
     * Dropping right under an open group header puts it inside that group; [outdent] moves it out of
     * that many enclosing groups.
     */
    fun moveToGap(layer: Layer, rowAbove: Layer?, outdent: Int = 0) {
        if (rowAbove === layer || (rowAbove != null && doc.isDescendant(rowAbove, layer))) return
        val from = doc.layers.indexOf(layer)
        if (from < 0) return
        val before = snap()
        val active = doc.activeLayer
        val block = doc.blockRange(from).map { doc.layers[it] }
        doc.layers.removeAll(block.toSet())
        var parent: Long
        var at: Int
        if (rowAbove == null) { parent = 0L; at = doc.layers.size }
        else {
            val ai = doc.layers.indexOf(rowAbove)
            if (rowAbove.isGroup && rowAbove.expanded) { parent = rowAbove.id; at = ai }
            else { parent = rowAbove.parentId; at = doc.blockRange(ai).first }
        }
        repeat(outdent) {
            val g = doc.byId(parent) ?: return@repeat
            parent = g.parentId
            at = doc.blockRange(doc.layers.indexOf(g)).first
        }
        block.last().parentId = parent
        doc.layers.addAll(at.coerceIn(0, doc.layers.size), block)
        doc.activeIndex = doc.layers.indexOf(active).coerceAtLeast(0)
        pushStructure("레이어 이동", before)
    }

    /** Visible rows of the layers panel, top → bottom (children of collapsed groups hidden). */
    fun displayRows(): List<Layer> = doc.layers.asReversed().filter { l -> doc.ancestors(l).all { it.expanded } }

    /** Depth a layer gets when dropped into the gap below [rowAbove]. */
    fun gapDepth(rowAbove: Layer?) = when {
        rowAbove == null -> 0
        rowAbove.isGroup && rowAbove.expanded -> doc.depth(rowAbove) + 1
        else -> doc.depth(rowAbove)
    }

    /** Moves a layer one step up/down among its siblings (keyboard / buttons). */
    fun nudgeLayer(l: Layer, up: Boolean) {
        val sib = doc.children(l.parentId)
        val k = sib.indexOf(l)
        val rows = displayRows()
        val rowAbove = if (up) {
            val above = sib.getOrNull(k + 1) ?: return
            rows.getOrNull(rows.indexOf(above) - 1)
        } else {
            val below = sib.getOrNull(k - 1) ?: return
            val bi = doc.layers.indexOf(below); val first = doc.blockRange(bi).first
            rows.lastOrNull { r -> doc.layers.indexOf(r) in first..bi }
        }
        moveToGap(l, rowAbove, (gapDepth(rowAbove) - doc.depth(l)).coerceAtLeast(0))
    }

    /** Composites [layers] (bottom → top, with blend modes, masks and clipping) into one bitmap. */
    private fun composite(layers: List<Layer>): Bitmap {
        val items = ArrayList<com.prosketch.app.engine.ClipItem>()
        var k = 0
        while (k < layers.size) {
            val base = layers[k]; val clips = ArrayList<Layer>(); var j = k + 1
            while (j < layers.size && layers[j].clipping && !layers[j].isGroup && layers[j].parentId == base.parentId) { clips.add(layers[j]); j++ }
            items.add(com.prosketch.app.engine.ClipItem(base, clips)); k = j
        }
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        Compositor.drawItems(Canvas(out), doc, items, null)
        return out
    }

    private fun mergedLayer(from: Layer, pixels: Bitmap): Layer {
        val m = Layer(pixels, from.name)
        m.parentId = from.parentId
        m.isReference = from.isReference
        return m
    }

    fun mergeDown(i: Int) {
        val top = doc.layers.getOrNull(i) ?: return
        if (top.isGroup) { flattenGroup(top); return }
        val sib = doc.children(top.parentId)
        val below = sib.getOrNull(sib.indexOf(top) - 1) ?: run { hud("아래에 병합할 레이어가 없습니다"); return }
        if (below.isGroup) { hud("그룹과는 병합할 수 없습니다"); return }
        if (doc.effectiveLocked(top) || doc.effectiveLocked(below)) { hud("잠긴 레이어입니다"); return }
        val before = snap()
        val base = Compositor.renderNode(doc, below)
        val c = Canvas(base)
        if (top.visible) {
            val node = Compositor.renderNode(doc, top)
            if (top.clipping) Canvas(node).drawBitmap(below.bitmap, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
            if (top.blend.isCustom) com.prosketch.app.engine.SoftBlend.blend(base, node, top.blend, top.opacity)
            else c.drawBitmap(node, 0f, 0f, Compositor.layerPaint(top))
            node.recycle()
        }
        val merged = mergedLayer(below, base).also { it.applyProps(below.props().copy(clipping = below.clipping)) }
        merged.parentId = below.parentId
        val bi = doc.layers.indexOf(below)
        doc.layers[bi] = merged
        doc.layers.remove(top)
        doc.activeIndex = doc.layers.indexOf(merged)
        doc.editMask = false
        pushStructure("아래 레이어와 병합", before)
    }

    /** Turns a group into one pixel layer with the same look (its blend/opacity stay as layer properties). */
    fun flattenGroup(g: Layer) {
        val gi = doc.layers.indexOf(g)
        if (gi < 0 || !g.isGroup) return
        val before = snap()
        val px = Compositor.renderNode(doc, g)
        val l = mergedLayer(g, px)
        l.opacity = g.opacity
        l.blend = if (g.passThrough) LayerBlend.NORMAL else g.blend
        l.visible = g.visible
        val range = doc.blockRange(gi)
        val blockSet = range.map { doc.layers[it] }.toSet()
        doc.layers.removeAll(blockSet)
        doc.layers.add(range.first.coerceIn(0, doc.layers.size), l)
        doc.activeIndex = doc.layers.indexOf(l)
        pushStructure("그룹 병합", before)
    }

    /** Pinch-merge / "Merge" on a multi-selection: everything between merges into one layer. */
    fun mergeRange(a: Int, b: Int) {
        val lo = min(a, b); val hi = max(a, b)
        if (lo == hi) return
        val parent = doc.layers[hi].parentId
        mergeLayers(doc.layers.subList(lo, hi + 1).filter { it.parentId == parent })
    }

    fun mergeSelected() = mergeLayers(selectedLayers())

    fun mergeLayers(targets: List<Layer>) {
        val list = targets.filter { it in doc.layers }.sortedBy { doc.layers.indexOf(it) }
        if (list.size < 2) { hud("병합하려면 레이어를 두 개 이상 선택하세요"); return }
        if (list.any { doc.effectiveLocked(it) }) { hud("잠긴 레이어입니다"); return }
        val before = snap()
        val visibleOnes = list.filter { it.visible }
        val px = composite(visibleOnes)
        val bottom = list.first()
        val l = mergedLayer(bottom, px)
        val remove = LinkedHashSet<Layer>()
        for (t in list) doc.blockRange(doc.layers.indexOf(t)).forEach { remove.add(doc.layers[it]) }
        val at = doc.layers.indexOfFirst { it in remove }
        doc.layers.removeAll(remove)
        doc.layers.add(at.coerceIn(0, doc.layers.size), l)
        doc.activeIndex = doc.layers.indexOf(l)
        doc.editMask = false
        pushStructure("레이어 병합", before)
    }

    // ---- groups
    fun createGroup(targets: List<Layer> = selectedLayers()) {
        val list = targets.filter { it in doc.layers }
        if (list.isEmpty()) return
        val before = snap()
        val top = list.maxBy { doc.layers.indexOf(it) }
        val g = Layer.group(nextGroupName())
        g.parentId = top.parentId
        doc.layers.add(doc.layers.indexOf(top) + 1, g)
        for (l in list) l.parentId = g.id
        doc.activeIndex = doc.layers.indexOf(g)
        doc.editMask = false
        secondary.clear()
        pushStructure("그룹", before)
        hud("새 그룹")
    }

    private fun nextGroupName(): String {
        var n = doc.layers.count { it.isGroup } + 1
        while (doc.layers.any { it.name == "새 그룹 $n" }) n++
        return if (n == 1) "새 그룹" else "새 그룹 $n"
    }

    fun ungroup(g: Layer) {
        if (!g.isGroup) return
        val before = snap()
        val kids = doc.children(g.id)
        kids.forEach { it.parentId = g.parentId }
        doc.layers.remove(g)
        doc.activeIndex = kids.lastOrNull()?.let { doc.layers.indexOf(it) } ?: doc.activeIndex.coerceIn(0, doc.layers.size - 1)
        pushStructure("그룹 해제", before)
    }

    fun toggleExpanded(g: Layer) { g.expanded = !g.expanded; version++ }

    fun setPassThrough(g: Layer, on: Boolean) = changeProps(g, "혼합 모드") { it.passThrough = on }

    // ---- masks
    fun addMask(target: Layer? = doc.activeLayer) {
        val l = target ?: return
        if (l.mask != null) { selectLayer(doc.indexOf(l), mask = true); return }
        if (!checkLayerLimit()) return
        val before = snap()
        l.mask = Layer.whiteMask(doc.width, doc.height)
        doc.activeIndex = doc.indexOf(l)
        doc.editMask = true
        pushStructure("마스크", before)
        hud("레이어 마스크: 검은색으로 가리고 흰색으로 드러냅니다")
    }

    fun deleteMask(l: Layer) {
        if (l.mask == null) return
        val before = snap()
        l.mask = null
        doc.editMask = false
        pushStructure("마스크 삭제", before)
    }

    /** Bakes the mask into the layer's pixels. */
    fun applyMask(l: Layer) {
        val m = l.mask ?: return
        if (l.isGroup) { hud("그룹 마스크는 그룹을 병합할 때 적용됩니다"); return }
        val before = snap()
        val px = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(px).drawBitmap(m.bitmap, 0f, 0f, Compositor.maskPaint)
        val nl = Layer(px, l.name).also { it.applyProps(l.props()); it.parentId = l.parentId; it.text = l.text }
        val i = doc.indexOf(l)
        doc.layers[i] = nl
        doc.activeIndex = i
        doc.editMask = false
        pushStructure("마스크 적용", before)
    }

    // ---- properties
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

    fun setBlend(layer: Layer, m: LayerBlend) = changeProps(layer, "혼합 모드") { it.blend = m; if (it.isGroup) it.passThrough = false }
    fun toggleVisible(layer: Layer) = changeProps(layer, "가시성") { it.visible = !it.visible }
    fun toggleAlphaLock(layer: Layer) {
        if (layer.isGroup) { hud("그룹에는 알파 채널 잠금을 쓸 수 없습니다"); return }
        changeProps(layer, "알파 채널 잠금") { it.alphaLock = !it.alphaLock }; hud(if (layer.alphaLock) "알파 채널 잠금 켬" else "알파 채널 잠금 끔")
    }
    fun toggleLock(layer: Layer) { changeProps(layer, "레이어 잠금") { it.locked = !it.locked }; hud(if (layer.locked) "레이어 잠김" else "레이어 잠금 해제") }
    fun togglePrivate(layer: Layer) { changeProps(layer, "비공개 레이어") { it.isPrivate = !it.isPrivate }; hud(if (layer.isPrivate) "비공개: 타임랩스와 미리보기에서 제외" else "비공개 해제") }
    fun toggleClipping(layer: Layer) {
        if (layer.isGroup) { hud("그룹은 클리핑 마스크가 될 수 없습니다"); return }
        val sib = doc.children(layer.parentId)
        if (sib.indexOf(layer) <= 0) { hud("아래에 레이어가 있어야 클리핑 마스크를 만들 수 있습니다"); return }
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
        if (layer.isText) rasterizeText(layer)
        val before = Bitmaps.crop(layer.bitmap, r)
        val c = Canvas(layer.bitmap)
        c.clipRect(r)
        block(c)
        history.push(PixelEntry(label, layer, r, before, Bitmaps.crop(layer.bitmap, r)))
        layer.touch()
        afterCommit()
    }

    private fun editable(l: Layer?): Layer? {
        if (l == null) return null
        if (l.isGroup) { hud("그룹 안의 레이어를 선택하세요"); return null }
        if (doc.effectiveLocked(l)) { hud("잠긴 레이어입니다"); return null }
        return l
    }

    fun clearLayer(target: Layer? = doc.active) {
        val l = editable(target) ?: return
        val sel = selection
        if (l.isMask) {
            editPixels(l, "마스크 초기화", sel?.let { Bitmaps.maskBounds(it) } ?: doc.bounds()) { c ->
                if (sel != null) { c.saveLayer(null, null); c.drawColor(Color.WHITE); c.drawBitmap(sel, 0f, 0f, dstIn); c.restore() } else c.drawColor(Color.WHITE)
            }
            hud("마스크 초기화"); return
        }
        val r = sel?.let { Bitmaps.maskBounds(it) } ?: Bitmaps.opaqueBounds(l.bitmap) ?: return
        editPixels(l, "지우기", r) { c ->
            if (sel != null) c.drawBitmap(sel, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
            else c.drawColor(0, BlendMode.CLEAR)
        }
        hud(if (sel != null) "선택 영역 지우기" else "레이어 지우기")
    }

    fun clearLayer(i: Int) = clearLayer(doc.layers.getOrNull(i))

    fun fillLayer(target: Layer? = doc.active, c0: Int = color) {
        val l = editable(target) ?: return
        val c = if (l.isMask) paintColorFor(l) else c0
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

    fun fillLayer(i: Int) = fillLayer(doc.layers.getOrNull(i))

    fun invertLayer(target: Layer? = doc.active) {
        val l = editable(target) ?: return
        val w = doc.width; val h = doc.height
        val px = IntArray(w * h)
        l.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (k in px.indices) { val c = px[k]; px[k] = (c and 0xFF000000.toInt()) or (c.inv() and 0x00FFFFFF) }
        editPixels(l, "반전") { _ -> l.bitmap.setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun invertLayer(i: Int) = invertLayer(doc.layers.getOrNull(i))

    fun selectLayerAlpha(target: Layer? = doc.active) {
        val l = target ?: return
        val src: Bitmap = when {
            l.isMask -> Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(l.bitmap, 0f, 0f, Paint().apply { colorFilter = (Compositor.maskPaint.colorFilter) })
            }
            l.isGroup -> Compositor.renderNode(doc, l)
            else -> l.bitmap
        }
        applySelection(SelectionOps.fromAlpha(src), record = true, label = "레이어 선택")
        if (src !== l.bitmap) src.recycle()
        hud("레이어 내용 선택됨")
    }

    fun selectLayerAlpha(i: Int) = selectLayerAlpha(doc.layers.getOrNull(i))

    // ---- text layers
    fun addTextLayer(spec: TextSpec) {
        if (spec.text.isBlank()) return
        val l = Layer(TextRenderer.render(spec, doc.width, doc.height), spec.text.lineSequence().first().take(24).ifBlank { "텍스트" })
        l.text = spec
        addLayer(l, "텍스트 추가")
    }

    fun updateText(l: Layer, spec: TextSpec) {
        val before = l.text
        val beforePx = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val px = TextRenderer.render(spec, doc.width, doc.height)
        Bitmaps.paste(l.bitmap, px, 0, 0); px.recycle()
        l.text = spec
        val autoName = before == null || l.name == before.text.lineSequence().first().take(24)
        if (autoName) l.name = spec.text.lineSequence().first().take(24).ifBlank { "텍스트" }
        history.push(TextEntry("텍스트 편집", l, before, beforePx, spec, l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
        l.touch(); afterCommit()
    }

    // text editing session: changes preview live on the canvas and become one undo step on "done"
    private var textOrigSpec: TextSpec? = null
    private var textOrigPx: Bitmap? = null
    var textIsNew by mutableStateOf(false)
        private set

    /** Actions › Add › Add text: a new text layer in the middle of the canvas, opened for editing. */
    fun startNewText() {
        exitModes()
        panel = null
        val size = max(24f, max(doc.width, doc.height) / 16f)
        var spec = TextSpec("텍스트", size = size, color = color)
        val (w, h) = TextRenderer.boxSize(spec)
        spec = spec.copy(x = (doc.width - w) / 2f, y = (doc.height - h) / 2f)
        addTextLayer(spec)
        val l = doc.activeLayer ?: return
        if (l.isText) beginTextEdit(l, isNew = true)
    }

    fun beginTextEdit(l: Layer, isNew: Boolean = false) {
        if (!l.isText) return
        if (doc.effectiveLocked(l)) { hud("잠긴 레이어입니다"); return }
        textEditing?.let { endTextEdit(true) }
        exitModes()
        panel = null
        textOrigSpec = l.text
        textOrigPx = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        textIsNew = isNew
        textEditing = l
    }

    fun previewText(spec: TextSpec) {
        val l = textEditing ?: return
        l.text = spec
        val px = TextRenderer.render(spec, doc.width, doc.height)
        Bitmaps.paste(l.bitmap, px, 0, 0); px.recycle()
        l.touch()
        invalidate()
    }

    fun endTextEdit(commit: Boolean) {
        val l = textEditing ?: return
        textEditing = null
        val orig = textOrigSpec; val origPx = textOrigPx
        textOrigSpec = null; textOrigPx = null
        val spec = l.text
        val empty = spec == null || spec.text.isBlank()
        if (!commit || (empty && textIsNew)) {
            if (textIsNew) { history.undo(); return }   // drops the layer that "add text" created
            if (orig != null && origPx != null) { l.text = orig; Bitmaps.paste(l.bitmap, origPx, 0, 0); l.touch(); bump() }
            return
        }
        if (spec == null || orig == null || origPx == null || spec == orig) { bump(); return }
        val autoName = l.name == orig.text.lineSequence().first().take(24) || l.name == "텍스트"
        if (autoName) l.name = spec.text.lineSequence().first().take(24).ifBlank { "텍스트" }
        history.push(TextEntry(if (textIsNew) "텍스트 추가" else "텍스트 편집", l, orig, origPx, spec, l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
        l.touch()
        afterCommit()
    }

    fun rasterizeText(l: Layer) {
        val spec = l.text ?: return
        val px = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        l.text = null
        history.push(TextEntry("래스터화", l, spec, px, null, px))
        l.touch()
        hud("텍스트를 래스터화했습니다")
        bump()
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
        val act = doc.active ?: return false
        val src: Bitmap = when {
            all -> Compositor.flatten(doc, background = false)
            act.isGroup -> Compositor.renderNode(doc, act)
            else -> act.bitmap
        }
        val r = (sel?.let { Bitmaps.maskBounds(it) } ?: Bitmaps.opaqueBounds(src)) ?: run { hud("복사할 내용이 없습니다"); return false }
        val crop = Bitmaps.crop(src, r)
        if (sel != null) Canvas(crop).drawBitmap(sel, -r.left.toFloat(), -r.top.toFloat(), dstIn)
        clipboard = ClipboardContent(crop, r.left, r.top)
        if (src !== act.bitmap) src.recycle()
        hud(if (all) "모두 복사됨" else "복사됨")
        return true
    }

    fun cut() { if (doc.active?.isGroup == true) { hud("그룹은 잘라낼 수 없습니다"); return }; if (copy()) { clearLayer(); hud("잘라내기") } }

    fun paste(name: String = "붙여넣기") {
        val cb = clipboard ?: run { hud("클립보드가 비어 있습니다"); return }
        val l = Layer.blank(doc.width, doc.height, name)
        Canvas(l.bitmap).drawBitmap(cb.bitmap, cb.left.toFloat(), cb.top.toFloat(), null)
        addLayer(layer = l, label = "붙여넣기")
        hud("붙여넣기")
    }

    fun duplicateSelection() { if (copy()) paste("선택에서") }
    fun cutAndPaste() { if (doc.active?.isGroup == true) { hud("그룹은 잘라낼 수 없습니다"); return }; if (copy()) { clearLayer(); paste("선택에서") } }

    // ------------------------------------------------------------------ insert / text
    fun insertImage(src: Bitmap, name: String = "삽입된 이미지", private: Boolean = false) {
        if (!checkLayerLimit()) return
        val s = min(1f, min(doc.width / src.width.toFloat(), doc.height / src.height.toFloat()))
        val w = max(1, (src.width * s).toInt()); val h = max(1, (src.height * s).toInt())
        val placed = Rect((doc.width - w) / 2, (doc.height - h) / 2, (doc.width - w) / 2 + w, (doc.height - h) / 2 + h)
        val l = Layer.blank(doc.width, doc.height, name)
        Canvas(l.bitmap).drawBitmap(src, null, placed, Paint(Paint.FILTER_BITMAP_FLAG))
        exitModes()
        l.isPrivate = private
        addLayer(l, name) ?: return
        transformTool.begin()
    }

    // ------------------------------------------------------------------ canvas operations
    fun resizeCanvas(newW: Int, newH: Int, offX: Int, offY: Int, resample: Boolean) {
        if (newW == doc.width && newH == doc.height && offX == 0 && offY == 0) return
        exitModes()
        val targets = pixelTargets()
        val before = targets.associateWith { it.bitmap }
        val after = HashMap<Layer, Bitmap>()
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val beforeText = doc.layers.filter { it.isText }.associateWith { it.text }
        val afterText = beforeText.mapValues { (_, t) -> if (resample) null else t?.copy(x = t.x + offX, y = t.y + offY) }
        for (l in targets) {
            val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            val c = Canvas(nb)
            if (resample) c.drawBitmap(l.bitmap, null, Rect(0, 0, newW, newH), p)
            else c.drawBitmap(l.bitmap, offX.toFloat(), offY.toFloat(), null)
            after[l] = nb
        }
        val e = DocumentEntry(if (resample) "캔버스 크기 조정" else "자르기 및 크기 조정", doc.width to doc.height, before, newW to newH, after, beforeText, afterText)
        e.redo(this)
        history.push(e)
        hud("캔버스: ${newW} × ${newH}px")
    }

    fun flipCanvas(horizontal: Boolean) {
        exitModes()
        val targets = pixelTargets()
        val before = targets.associateWith { it.bitmap }
        val after = HashMap<Layer, Bitmap>()
        val beforeText = doc.layers.filter { it.isText }.associateWith { it.text }
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        for (l in targets) {
            val nb = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(l.bitmap, m, null)
            after[l] = nb
        }
        val e = DocumentEntry(if (horizontal) "캔버스 수평 뒤집기" else "캔버스 수직 뒤집기", doc.width to doc.height, before, doc.width to doc.height, after,
            beforeText, beforeText.mapValues { null })
        e.redo(this)
        history.push(e)
    }

    /** Every bitmap that covers the canvas: pixel layers and masks. */
    private fun pixelTargets(): List<Layer> = doc.layers.filter { !it.isGroup } + doc.layers.mapNotNull { it.mask }

    fun flipLayer(horizontal: Boolean) {
        val a = doc.active ?: return
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        val targets = if (a.isGroup) doc.pixelLayers(a) + doc.pixelLayers(a).mapNotNull { it.mask } else listOf(a)
        if (targets.any { doc.effectiveLocked(it) }) { hud("잠긴 레이어입니다"); return }
        val parts = ArrayList<com.prosketch.app.engine.HistoryEntry>()
        for (l in targets) {
            if (l.isText) rasterizeText(l)
            val before = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val c = Canvas(l.bitmap)
            c.drawColor(0, BlendMode.CLEAR); c.drawBitmap(before, m, null)
            parts.add(PixelEntry("레이어 뒤집기", l, doc.bounds(), before, l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
            l.touch()
        }
        if (parts.isNotEmpty()) history.push(if (parts.size == 1) parts[0] else CompoundEntry("레이어 뒤집기", parts))
        afterCommit()
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

        override fun isVisible(layer: Layer): Boolean {
            soloLayer?.let { solo ->
                if (doc.layers.contains(solo)) return layer === solo || doc.isDescendant(layer, solo) || doc.isDescendant(solo, layer)
            }
            return layer.visible
        }
    }

    // ------------------------------------------------------------------ animation
    /** Adds an empty top-level frame right after the current one. */
    fun addFrame() {
        if (!checkLayerLimit()) return
        val before = snap()
        val cur = com.prosketch.app.engine.Anim.currentFrame(doc)
        val l = Layer.blank(doc.width, doc.height, nextLayerName())
        val idx = if (cur == null) doc.layers.size else doc.indexOf(cur) + 1
        doc.layers.add(idx.coerceIn(0, doc.layers.size), l)
        doc.activeIndex = doc.layers.indexOf(l)
        doc.editMask = false
        pushStructure("프레임 추가", before)
    }

    private val frameThumbs = HashMap<Long, Pair<Long, Bitmap>>()

    /** Timeline thumbnail of a frame (groups and masked layers are composited). */
    fun frameThumb(l: Layer): Bitmap {
        val sig = doc.itemLayers(com.prosketch.app.engine.ClipItem(l, emptyList())).fold(17L) { h, x -> h * 31 + x.version }
        frameThumbs[l.id]?.let { if (it.first == sig) return it.second }
        val bmp = if (!l.isGroup && l.mask == null) l.thumbnail(120) else {
            val full = Compositor.renderNode(doc, l)
            val s = 120f / max(full.width, full.height)
            Bitmap.createScaledBitmap(full, max(1, (full.width * s).toInt()), max(1, (full.height * s).toInt()), true).also { full.recycle() }
        }
        frameThumbs[l.id] = sig to bmp
        return bmp
    }

    /** Frame shown while playing (null = not playing). */
    var playLayer by mutableStateOf<Layer?>(null)

    fun setHold(frame: Layer, hold: Int) {
        val before = frame.holdFrames
        if (before == hold) return
        frame.holdFrames = hold.coerceIn(0, 120)
        history.push(object : com.prosketch.app.engine.HistoryEntry("유지 시간") {
            override val bytes = 16L
            override fun undo(h: com.prosketch.app.engine.HistoryHost) { frame.holdFrames = before; bump() }
            override fun redo(h: com.prosketch.app.engine.HistoryHost) { frame.holdFrames = hold; bump() }
        })
        bump()
    }

    // ------------------------------------------------------------------ timelapse
    fun captureTimelapse() {
        if (!doc.timelapseEnabled) return
        val shadow = doc.shallowCopy()
        val s = min(1f, 1280f / max(doc.width, doc.height))
        timelapse.capture { Compositor.flatten(shadow, s, publicHook) }
    }

    /** Hides private layers (gallery thumbnail, time-lapse). */
    val publicHook = object : RenderHook {
        override fun isVisible(layer: Layer) = layer.visible && !layer.isPrivate
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
        val thumb = Compositor.flatten(doc, min(1f, 640f / max(doc.width, doc.height)), publicHook)
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
