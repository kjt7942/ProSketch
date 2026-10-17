package com.prosketch.app.ui.editor

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prosketch.app.engine.AdjustmentType
import com.prosketch.app.engine.Bitmaps
import com.prosketch.app.engine.StrokeKind
import com.prosketch.app.engine.InputPoint
import com.prosketch.app.engine.BrushStroke
import com.prosketch.app.engine.ColorBalanceSet
import com.prosketch.app.engine.CompoundEntry
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.CurveSet
import com.prosketch.app.engine.Filters
import com.prosketch.app.engine.FloodFill
import com.prosketch.app.engine.GradientPreset
import com.prosketch.app.engine.GradientPresets
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LiquifyMode
import com.prosketch.app.engine.LiquifySession
import com.prosketch.app.engine.PixelEntry
import com.prosketch.app.engine.SelectionEntry
import com.prosketch.app.engine.SelectionMode
import com.prosketch.app.engine.SelectionOp
import com.prosketch.app.engine.SelectionOps
import com.prosketch.app.engine.TransformMode
import com.prosketch.app.engine.TransformSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sin

// ====================================================================== Selection

class SelectionTool(private val ed: Editor) {
    var mode by mutableStateOf(SelectionMode.FREEHAND)
    var op by mutableStateOf(SelectionOp.ADD)
    var colorFill by mutableStateOf(false)
    var feather by mutableFloatStateOf(0f)
    var version by mutableIntStateOf(0)
    val saved = mutableStateListOf<Bitmap>()

    /** Freehand polygon / lasso in progress (document coordinates). */
    val lasso = ArrayList<PointF>()
    /** Rectangle/ellipse being dragged. */
    var dragRect: RectF? = null

    private var autoRef: IntArray? = null
    private var autoPoint: PointF? = null
    private var autoBase: Bitmap? = null
    var autoThreshold by mutableFloatStateOf(ed.prefs.autoSelectThreshold)

    fun begin() {
        ed.exitModes()
        ed.mode = com.prosketch.app.ui.editor.Mode.SELECT
        ed.panel = null
        lasso.clear(); dragRect = null
        version++
    }

    private fun effectiveOp(): SelectionOp = if (ed.selection == null) SelectionOp.REPLACE else op

    private fun commitMask(build: (Bitmap?) -> Bitmap?) {
        val newMask = build(ed.selection)
        ed.applySelection(newMask, record = true)
        if (colorFill && newMask != null) ed.fillLayer()
        version++
    }

    fun applyPath(path: Path) {
        val w = ed.doc.width; val h = ed.doc.height
        commitMask { cur -> SelectionOps.pathMask(cur, w, h, path, effectiveOp()) }
    }

    // ---- freehand
    fun lassoPoint(p: PointF) { lasso.add(p); version++; ed.invalidate() }

    fun closeLasso() {
        if (lasso.size >= 3) {
            val path = Path().apply {
                moveTo(lasso[0].x, lasso[0].y)
                for (i in 1 until lasso.size) lineTo(lasso[i].x, lasso[i].y)
                close()
            }
            applyPath(path)
        }
        lasso.clear(); version++; ed.invalidate()
    }

    fun undoPending(): Boolean {
        if (lasso.isEmpty()) return false
        lasso.removeAt(lasso.size - 1); version++; ed.invalidate()
        return true
    }

    // ---- rect / ellipse
    fun applyRect(r: RectF, ellipse: Boolean) {
        val rr = RectF(min(r.left, r.right), min(r.top, r.bottom), max(r.left, r.right), max(r.top, r.bottom))
        if (rr.width() < 2 || rr.height() < 2) return
        applyPath(Path().apply { if (ellipse) addOval(rr, Path.Direction.CW) else addRect(rr, Path.Direction.CW) })
        dragRect = null
    }

    // ---- automatic (magic wand)
    fun autoSelect(x: Float, y: Float) {
        if (autoRef == null) {
            val flat = Compositor.flatten(ed.doc, background = false)
            autoRef = IntArray(ed.doc.width * ed.doc.height).also { flat.getPixels(it, 0, ed.doc.width, 0, 0, ed.doc.width, ed.doc.height) }
            flat.recycle()
        }
        autoBase = ed.selection?.copy(Bitmap.Config.ALPHA_8, false)
        autoPoint = PointF(x, y)
        runAuto(record = true)
    }

    fun autoAdjust(dxFraction: Float) {
        if (autoPoint == null) return
        autoThreshold = (autoThreshold + dxFraction).coerceIn(0f, 1f)
        ed.hud(L.tf("선택 임계값: {0}%", (autoThreshold * 100).toInt()))
        runAuto(record = false)
    }

    fun autoEnd() { ed.prefs.autoSelectThreshold = autoThreshold; autoPoint = null; autoBase = null }

    private fun runAuto(record: Boolean) {
        val ref = autoRef ?: return
        val p = autoPoint ?: return
        val w = ed.doc.width; val h = ed.doc.height
        val reg = FloodFill.region(ref, w, h, p.x.toInt(), p.y.toInt(), autoThreshold, fringe = 1) ?: return
        val shape = SelectionOps.fromBytes(reg.mask, w, h)
        val base = autoBase
        val newMask = if (base == null) shape else SelectionOps.combineMask(base, shape, if (op == SelectionOp.SUBTRACT) SelectionOp.SUBTRACT else SelectionOp.ADD)
        if (record) ed.applySelection(newMask, record = true, label = L.t("자동 선택"))
        else ed.applySelection(newMask, record = false)
        version++
    }

    // ---- operations
    fun invert() {
        ed.applySelection(SelectionOps.invert(ed.selection, ed.doc.width, ed.doc.height), record = true, label = L.t("선택 반전"))
    }

    fun applyFeather(radius: Float) {
        val s = ed.selection ?: return
        ed.applySelection(SelectionOps.feather(s, radius), record = true, label = L.t("페더"))
    }

    fun clear() { ed.clearSelection(); lasso.clear(); version++ }

    fun saveCurrent() { ed.selection?.let { saved.add(it.copy(Bitmap.Config.ALPHA_8, false)); ed.hud(L.t("선택 영역 저장됨")) } }

    fun load(i: Int) { saved.getOrNull(i)?.let { ed.applySelection(it.copy(Bitmap.Config.ALPHA_8, true), record = true, label = L.t("선택 불러오기")) } }

    fun finish() {
        if (lasso.size >= 3) closeLasso()
        lasso.clear(); dragRect = null; autoRef = null; autoPoint = null
    }
}

// ====================================================================== Transform

class TransformTool(private val ed: Editor) {
    var session: TransformSession? = null
        private set
    var version by mutableIntStateOf(0)

    /** Every layer moved by the session after the primary one: groups, secondary layers and masks. */
    private class Extra(val layer: Layer, val floating: Bitmap, val before: Bitmap)
    private var extras: List<Extra> = emptyList()
    /** Text layer transformed as editable text (move / scale / rotate keep it editable). */
    private var textBefore: com.prosketch.app.engine.TextSpec? = null

    fun changed() { version++; ed.invalidate() }

    /** Pixel layers a transform should move: the selection (primary + secondaries, groups expanded) plus their masks. */
    private fun targets(): List<Layer> {
        val doc = ed.doc
        val active = doc.active ?: return emptyList()
        if (active.isMask) return listOf(active)
        val roots = ed.selectedLayers().ifEmpty { listOf(active) }
        val px = roots.flatMap { doc.pixelLayers(it) }.distinct()
        return px + px.mapNotNull { it.mask } + roots.filter { it.isGroup }.mapNotNull { it.mask }
    }

    fun begin(): Boolean = ed.safely(L.t("변형")) { beginImpl() } ?: false

    private fun beginImpl(): Boolean {
        val doc = ed.doc
        val all = targets()
        if (all.isEmpty()) { ed.hud(L.t("변형할 레이어가 없습니다")); return false }
        if (all.any { doc.effectiveLocked(it) }) { ed.hud(L.t("잠긴 레이어입니다")); return false }
        val content = all.filter { !it.isMask }.ifEmpty { all }
        val sel = ed.selection
        val r = sel?.let { Bitmaps.maskBounds(it) } ?: content.mapNotNull { Bitmaps.opaqueBounds(it.bitmap) }.reduceOrNull { a, b -> Rect(a).apply { union(b) } }
        if (r == null) { ed.hud(L.t("변형할 내용이 없습니다")); return false }
        val primary = content.first()
        // a lone text layer stays editable; anything mixed is rasterised first
        textBefore = if (primary.isText && all.size == 1 && sel == null) primary.text else null
        if (textBefore == null) all.filter { it.isText }.forEach { ed.rasterizeText(it) }
        val dstOut = Paint().apply { blendMode = BlendMode.DST_OUT }
        val dstIn = Paint().apply { blendMode = BlendMode.DST_IN }
        fun lift(l: Layer): Pair<Bitmap, Bitmap> {
            val before = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val floating = Bitmaps.crop(l.bitmap, r)
            val c = Canvas(l.bitmap)
            if (sel != null) {
                Canvas(floating).drawBitmap(sel, -r.left.toFloat(), -r.top.toFloat(), dstIn)
                c.drawBitmap(sel, 0f, 0f, dstOut)
            } else { c.save(); c.clipRect(r); c.drawColor(0, BlendMode.CLEAR); c.restore() }
            l.touch()
            return floating to before
        }
        val (pf, pb) = lift(primary)
        extras = all.filter { it !== primary }.map { l -> val (f, b) = lift(l); Extra(l, f, b) }
        val fMask = sel?.let { Bitmaps.crop(it, r) }
        session = TransformSession(primary, pf, r, pb, sel?.copy(Bitmap.Config.ALPHA_8, false), fMask)
        ed.mode = Mode.TRANSFORM
        ed.panel = null
        if (all.size > 1) ed.hud(L.tf("레이어 {0}개 변형", content.size))
        changed()
        return true
    }

    private fun floatingFor(layer: Layer): Bitmap? {
        val s = session ?: return null
        if (s.layer === layer) return s.floating
        return extras.firstOrNull { it.layer === layer }?.floating
    }

    fun drawLive(canvas: Canvas, layer: Layer, paint: Paint?): Boolean {
        val s = session ?: return false
        val f = floatingFor(layer) ?: return false
        canvas.saveLayer(RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat()), paint)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        s.drawFloating(canvas, f)
        canvas.restore()
        return true
    }

    /** New text spec when the session is a pure move / scale / rotation of the box, else null. */
    private fun similarityText(s: TransformSession, spec: com.prosketch.app.engine.TextSpec): com.prosketch.app.engine.TextSpec? {
        if (s.mode == TransformMode.WARP || s.mode == TransformMode.DISTORT) return null
        val c = s.corners
        val r = s.origin
        val ux = c[2] - c[0]; val uy = c[3] - c[1]; val vx = c[6] - c[0]; val vy = c[7] - c[1]
        val sU = hypot(ux, uy) / r.width().coerceAtLeast(1); val sV = hypot(vx, vy) / r.height().coerceAtLeast(1)
        if (abs(sU - sV) > 0.02f * sU) return null
        val cross = ux * vy - uy * vx
        if (cross <= 0f) return null                                   // flipped
        if (abs(ux * vx + uy * vy) > 0.02f * hypot(ux, uy) * hypot(vx, vy)) return null   // skewed
        val theta = Math.toDegrees(atan2(uy, ux).toDouble()).toFloat()
        // map the old box centre through the same similarity
        val (w, h) = com.prosketch.app.engine.TextRenderer.boxSize(spec)
        val oc = floatArrayOf(spec.x + w * spec.scale / 2f, spec.y + h * spec.scale / 2f)
        val cosT = cos(Math.toRadians(theta.toDouble())).toFloat(); val sinT = sin(Math.toRadians(theta.toDouble())).toFloat()
        val px = (oc[0] - r.left) * sU; val py = (oc[1] - r.top) * sU
        val nx = c[0] + px * cosT - py * sinT; val ny = c[1] + px * sinT + py * cosT
        val ns = spec.scale * sU
        return spec.copy(scale = ns, rotation = spec.rotation + theta, x = nx - w * ns / 2f, y = ny - h * ns / 2f)
    }

    fun commit() {
        val s = session ?: return
        session = null
        val doc = ed.doc
        val full = Rect(0, 0, doc.width, doc.height)
        val parts = ArrayList<com.prosketch.app.engine.HistoryEntry>()
        val layer = s.layer
        val spec = textBefore
        val newSpec = spec?.let { similarityText(s, it) }
        if (spec != null && newSpec != null) {
            val px = com.prosketch.app.engine.TextRenderer.render(newSpec, doc.width, doc.height)
            Bitmaps.paste(layer.bitmap, px, 0, 0); px.recycle()
            layer.text = newSpec
            parts.add(com.prosketch.app.engine.TextEntry(L.t("변형"), layer, spec, s.beforeLayer, newSpec, layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
        } else {
            s.drawFloating(Canvas(layer.bitmap))
            if (spec != null) {
                layer.text = null
                parts.add(com.prosketch.app.engine.TextEntry(L.t("변형"), layer, spec, s.beforeLayer, null, layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
                ed.hud(L.t("텍스트가 래스터화되었습니다"))
            } else parts.add(PixelEntry(L.t("변형"), layer, full, s.beforeLayer, layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
        }
        layer.touch()
        for (e in extras) {
            s.drawFloating(Canvas(e.layer.bitmap), e.floating)
            parts.add(PixelEntry(L.t("변형"), e.layer, full, e.before, e.layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
            e.layer.touch(); e.floating.recycle()
        }
        extras = emptyList(); textBefore = null
        val fm = s.floatingMask
        if (fm != null && s.beforeSelection != null) {
            val nm = SelectionOps.newMask(doc.width, doc.height)
            s.drawFloating(Canvas(nm), fm, Paint(Paint.FILTER_BITMAP_FLAG))
            parts.add(SelectionEntry(L.t("변형"), s.beforeSelection, nm.copy(Bitmap.Config.ALPHA_8, false)))
            ed.applySelection(nm, record = false)
        }
        ed.history.push(if (parts.size == 1) parts[0] else CompoundEntry(L.t("변형"), parts))
        if (ed.mode == Mode.TRANSFORM) ed.mode = Mode.PAINT
        ed.afterCommit()
        changed()
    }

    fun cancel() {
        val s = session ?: return
        session = null
        Bitmaps.paste(s.layer.bitmap, s.beforeLayer, 0, 0)
        s.layer.touch()
        for (e in extras) { Bitmaps.paste(e.layer.bitmap, e.before, 0, 0); e.layer.touch(); e.floating.recycle() }
        extras = emptyList(); textBefore = null
        ed.mode = Mode.PAINT
        ed.bump()
        changed()
    }

    fun setMode(m: TransformMode) { session?.mode = m; changed() }
    fun flipH() { session?.flip(true); changed() }
    fun flipV() { session?.flip(false); changed() }
    fun rotate45() { session?.rotate(45f); changed() }
    fun reset() { session?.reset(); changed() }

    /** Exact numbers from the transform toolbar (Procreate's numeric input). */
    fun setSize(wPx: Float, hPx: Float) {
        val s = session ?: return
        val c = s.center()
        val ang = s.angle()
        s.rotate(-ang, c)
        val cw = s.widthPx().coerceAtLeast(1f); val ch = s.heightPx().coerceAtLeast(1f)
        val sx = wPx / cw; val sy = hPx / ch
        for (i in 0 until 4) {
            s.corners[i * 2] = c.x + (s.corners[i * 2] - c.x) * sx
            s.corners[i * 2 + 1] = c.y + (s.corners[i * 2 + 1] - c.y) * sy
        }
        s.rotate(ang, c)
        changed()
    }

    fun setRotation(deg: Float) { val s = session ?: return; s.rotate(deg - s.angle()); changed() }

    /** Tap outside the box: nudge one step toward the tap (finer when zoomed in). */
    fun nudge(dx: Float, dy: Float) { session?.translate(dx, dy); changed() }

    fun fitToScreen() {
        val s = session ?: return
        val xs = floatArrayOf(s.corners[0], s.corners[2], s.corners[4], s.corners[6])
        val ys = floatArrayOf(s.corners[1], s.corners[3], s.corners[5], s.corners[7])
        val bw = (xs.max() - xs.min()).coerceAtLeast(1f); val bh = (ys.max() - ys.min()).coerceAtLeast(1f)
        val k = min(ed.doc.width / bw, ed.doc.height / bh)
        val c = s.center()
        s.scaleAbout(k, c)
        val c2 = s.center()
        s.translate(ed.doc.width / 2f - c2.x, ed.doc.height / 2f - c2.y)
        changed()
    }
}

// ====================================================================== Adjustments & Liquify

class AdjustTool(private val ed: Editor) {
    var type by mutableStateOf<AdjustmentType?>(null)
    val params = mutableStateMapOf<String, Float>()
    var curves by mutableStateOf(CurveSet())
    var curveChannel by mutableIntStateOf(0)
    var balance by mutableStateOf(ColorBalanceSet())
    var balanceTone by mutableIntStateOf(1)
    var gradientIndex by mutableIntStateOf(1)
    var version by mutableIntStateOf(0)
    var computing by mutableStateOf(false)
    /** Pencil mode: the adjustment is painted on with the brush instead of covering the layer. */
    var pencil by mutableStateOf(false)
    /** Show the untouched layer while held (before / after). */
    var showOriginal by mutableStateOf(false)
    /** Clone: source disc position (document px) and whether it stays put. */
    var cloneSource by mutableStateOf<PointF?>(null)
    /** Called when the user drags the disc: the next stroke defines a new offset. */
    fun moveCloneSource(p: PointF) { cloneSource = p; cloneOffset = null }
    var cloneLocked by mutableStateOf(false)

    var layer: Layer? = null
        private set
    private var src: IntArray? = null
    private var srcBitmap: Bitmap? = null
    private var preview: Bitmap? = null
    private var pencilMask: Bitmap? = null
    private var cloneResult: Bitmap? = null
    private var selBytes: ByteArray? = null
    private var job: Job? = null
    private var pending = false
    // Progressive preview: a quick low-resolution result while values change, full resolution once they settle.
    private var lowSrc: IntArray? = null
    private var lowSel: ByteArray? = null
    private var lowW = 0
    private var lowH = 0
    private var lowPreview: Bitmap? = null
    private var gen = 0                 // bumped on every parameter change
    private var fullGen = -1            // generation shown in [preview] at full resolution
    private var fullJob: Job? = null
    private var fullToken: Filters.CancelToken? = null
    private val previewFull get() = fullGen == gen
    var liquify: LiquifySession? = null
        private set
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    private val dstOut = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val plus = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }

    fun begin(t: AdjustmentType) { ed.safely(t.label) { beginImpl(t) } ?: run { end(); ed.invalidate() } }

    private fun beginImpl(t: AdjustmentType) {
        ed.exitModes()
        val l = ed.doc.active ?: return
        if (l.isGroup) { ed.hud(L.t("조정은 그룹이 아닌 레이어에 적용합니다")); return }
        if (ed.doc.effectiveLocked(l)) { ed.hud(L.t("잠긴 레이어입니다")); return }
        if (!ed.doc.effectiveVisible(l)) { ed.hud(L.t("숨겨진 레이어입니다")); return }
        if (l.isText) ed.rasterizeText(l)
        layer = l
        type = t
        params.clear()
        t.params.forEach { params[it.key] = it.default }
        selBytes = ed.selection?.let { SelectionOps.toBytes(it) }
        ed.panel = null
        if (!t.supportsPencil) pencil = false
        showOriginal = false
        val w = ed.doc.width; val h = ed.doc.height
        when (t) {
            AdjustmentType.LIQUIFY -> {
                liquify = LiquifySession(l, l.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                ed.mode = Mode.LIQUIFY
            }
            AdjustmentType.CLONE -> {
                srcBitmap = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                cloneResult = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (cloneSource == null || !cloneLocked) cloneSource = PointF(w / 3f, h / 2f)
                ed.mode = Mode.ADJUST
                ed.hud(L.t("원을 소스 위치로 옮긴 뒤 브러시로 칠하세요 · 원을 길게 누르면 고정"))
            }
            else -> {
                src = IntArray(w * h).also { l.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
                srcBitmap = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                preview = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                prepareLowRes(l.bitmap, w, h)
                pencilMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                ed.mode = Mode.ADJUST
                if (t == AdjustmentType.RECOLOR) ed.hud(L.t("십자선을 바꿀 색 위로 옮기세요 · 현재 색상으로 칠해집니다"))
                recompute()
            }
        }
        version++
        ed.invalidate()
    }

    fun primaryParam() = type?.params?.firstOrNull { !it.hidden && it.choices == null }

    fun setParam(key: String, v: Float) {
        params[key] = v
        version++
        recompute()
    }

    /** Horizontal finger swipe across the canvas changes the first parameter (Procreate-style). */
    fun dragPrimary(dxFraction: Float) {
        val p = primaryParam() ?: return
        val cur = params[p.key] ?: p.default
        val nv = (cur + dxFraction * (p.max - p.min)).coerceIn(p.min, p.max)
        setParam(p.key, nv)
        ed.hud("${p.label}: ${formatParam(p, nv)}")
    }

    /** Motion blur and chromatic displacement follow the drag direction and length. */
    fun usesDragVector(): Boolean = type == AdjustmentType.MOTION_BLUR || (type == AdjustmentType.CHROMATIC && (params["mode"] ?: 0f) >= 0.5f)

    fun dragVector(dxDoc: Float, dyDoc: Float) {
        val w = ed.doc.width.toFloat(); val h = ed.doc.height.toFloat()
        when (type) {
            AdjustmentType.MOTION_BLUR -> {
                val len = hypot(dxDoc, dyDoc) / max(w, h)
                params["dist"] = (len * 250f).coerceIn(0f, 100f)
                params["angle"] = ((Math.toDegrees(atan2(dyDoc, dxDoc).toDouble()).toFloat() + 360f) % 360f)
                ed.hud(L.tf("거리 {0}% · {1}°", params["dist"]!!.toInt(), params["angle"]!!.toInt()))
            }
            AdjustmentType.CHROMATIC -> {
                params["dx"] = (dxDoc / w).coerceIn(-1f, 1f); params["dy"] = (dyDoc / h).coerceIn(-1f, 1f)
                ed.hud(L.tf("변위 {0}px", hypot(dxDoc, dyDoc).toInt()))
            }
            else -> return
        }
        version++
        recompute()
    }

    fun focal(): PointF? {
        val t = type ?: return null
        if (!t.hasFocal) return null
        if (t == AdjustmentType.CHROMATIC && (params["mode"] ?: 0f) >= 0.5f) return null
        return PointF((params["fx"] ?: 0.5f) * ed.doc.width, (params["fy"] ?: 0.5f) * ed.doc.height)
    }

    fun setFocal(x: Float, y: Float) {
        params["fx"] = (x / ed.doc.width).coerceIn(-0.5f, 1.5f); params["fy"] = (y / ed.doc.height).coerceIn(-0.5f, 1.5f)
        version++; recompute()
    }

    fun recolorTarget(): PointF? = if (type == AdjustmentType.RECOLOR) PointF((params["tx"] ?: 0.5f) * ed.doc.width, (params["ty"] ?: 0.5f) * ed.doc.height) else null

    fun setRecolorTarget(x: Float, y: Float) {
        params["tx"] = (x / ed.doc.width).coerceIn(0f, 1f); params["ty"] = (y / ed.doc.height).coerceIn(0f, 1f)
        version++; recompute()
    }

    fun colorChanged() { if (type == AdjustmentType.RECOLOR) recompute() }

    fun formatParam(p: com.prosketch.app.engine.FilterParam, v: Float): String =
        if (p.unit == "%") "${v.toInt()}%" else if (p.isSwitch) (if (v >= 0.5f) L.t("켬") else L.t("끔")) else "${v.toInt()}${p.unit}"

    fun curvesChanged() { version++; recompute() }
    fun balanceChanged() { version++; recompute() }

    fun reset() {
        val t = type ?: return
        when (t) {
            AdjustmentType.LIQUIFY -> { liquify?.reset(); ed.invalidate(); return }
            AdjustmentType.CLONE -> { srcBitmap?.let { s -> cloneResult?.let { Bitmaps.paste(it, s, 0, 0) } }; ed.invalidate(); return }
            else -> {}
        }
        t.params.forEach { params[it.key] = it.default }
        curves = CurveSet(); balance = ColorBalanceSet()
        pencilMask?.eraseColor(0)
        version++
        recompute()
    }

    /** Longest side of the quick preview. Filter sizes are relative to the canvas, so it looks the same. */
    private val lowSide = 720

    private fun prepareLowRes(bmp: Bitmap, w: Int, h: Int) {
        val k = lowSide.toFloat() / max(w, h)
        if (k >= 0.75f) { lowSrc = null; lowSel = null; lowPreview = null; return }   // small canvas: full resolution is quick
        lowW = max(1, (w * k).roundToInt()); lowH = max(1, (h * k).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, lowW, lowH, true)
        lowSrc = IntArray(lowW * lowH).also { small.getPixels(it, 0, lowW, 0, 0, lowW, lowH) }
        if (small !== bmp) small.recycle()
        lowSel = ed.selection?.let { sel ->
            val ss = Bitmap.createScaledBitmap(sel, lowW, lowH, true)
            SelectionOps.toBytes(ss).also { if (ss !== sel) ss.recycle() }
        }
        lowPreview = Bitmap.createBitmap(lowW, lowH, Bitmap.Config.ARGB_8888)
    }

    private class Snapshot(
        val t: AdjustmentType, val p: Map<String, Float>, val cs: CurveSet, val bal: ColorBalanceSet,
        val grad: GradientPreset, val color: Int,
    )

    private fun snapshot() = Snapshot(type!!, HashMap(params), curves.copy(), balance.copy(),
        GradientPresets.all.getOrElse(gradientIndex) { GradientPresets.all[0] }, ed.color)

    private fun runFilter(s: Snapshot, src: IntArray, w: Int, h: Int, sel: ByteArray?): IntArray =
        Filters.apply(s.t, src, w, h, s.p, s.cs, s.bal, s.grad, s.color).also { Filters.maskBlend(src, it, sel) }

    private fun recompute() {
        val t = type ?: return
        if (t == AdjustmentType.LIQUIFY || t == AdjustmentType.CLONE) return
        gen++
        // a full-resolution pass for older values is now useless
        fullToken?.cancelled = true; fullJob?.cancel(); fullJob = null
        computing = true
        quickPass()
    }

    /** Low-resolution pass for the latest values; serialised so a burst of changes runs it at most twice. */
    private fun quickPass() {
        val low = lowSrc
        if (low == null) { scheduleFull(0L); return }
        if (job?.isActive == true) { pending = true; return }
        pending = false
        val snap = snapshot()
        val myGen = gen
        val lw = lowW; val lh = lowH; val sel = lowSel
        job = ed.scope.launch(Dispatchers.Default) {
            val out = runCatching { runFilter(snap, low, lw, lh, sel) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (type == null) return@withContext
                if (out != null) { lowPreview?.setPixels(out, 0, lw, 0, 0, lw, lh); ed.invalidate() }
                if (pending) quickPass() else if (myGen == gen) scheduleFull(220L)
            }
        }
    }

    /** Computes the full-resolution result after [delayMs] unless the values change again. */
    private fun scheduleFull(delayMs: Long) {
        val s = src ?: return
        val w = ed.doc.width; val h = ed.doc.height
        val snap = snapshot()
        val myGen = gen
        val sel = selBytes
        val tok = Filters.CancelToken()
        fullToken?.cancelled = true
        fullToken = tok
        fullJob = ed.scope.launch(Dispatchers.Default) {
            if (delayMs > 0) delay(delayMs)
            if (tok.cancelled) return@launch
            val out = runCatching { Filters.cancellable(tok) { runFilter(snap, s, w, h, sel) } }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (tok.cancelled || myGen != gen || type == null) return@withContext
                preview?.setPixels(out, 0, w, 0, 0, w, h)
                fullGen = myGen
                computing = false
                ed.invalidate()
            }
        }
    }

    /** The filtered layer: full resolution when ready, otherwise the quick preview stretched to the canvas. */
    private fun drawFiltered(c: Canvas, paint: Paint?) {
        val lp = lowPreview
        if (previewFull || lp == null) { preview?.let { c.drawBitmap(it, 0f, 0f, paint) }; return }
        val p = Paint(paint ?: Paint()).apply { isFilterBitmap = true }
        c.drawBitmap(lp, null, RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat()), p)
    }

    // ---- brush strokes in Pencil mode and Clone
    private var stroke: BrushStroke? = null
    private var strokeErase = false
    private var cloneOffset: PointF? = null
    private var strokeFirst: PointF? = null
    private var strokeLast: PointF? = null

    fun strokeDown(x: Float, y: Float, pressure: Float, stylus: Boolean, screenToDoc: Float) {
        val t = type ?: return
        val toolT = if (ed.tool == Tool.ERASER) Tool.ERASER else Tool.BRUSH
        val brush = ed.brushFor(toolT)
        val size = ed.brushDocSize(screenToDoc, toolT)
        val opacity = brush.opacityFor(ed.toolOpacity[toolT] ?: 1f)
        strokeErase = toolT == Tool.ERASER && t != AdjustmentType.CLONE
        ed.strokeBuffer.clear()
        val p = InputPoint(x, y, if (stylus) ed.pressureCurve.map(pressure) else 1f)
        stroke = BrushStroke(brush, StrokeKind.PAINT, Color.WHITE, size, opacity, ed.strokeBuffer, listOf(android.graphics.Matrix()), stylus, screenToDoc, null).also { it.begin(p) }
        strokeFirst = PointF(x, y); strokeLast = PointF(x, y)
        if (t == AdjustmentType.CLONE) {
            // locked: always sample from the disc; unlocked: keep the offset found at the first stroke
            val s = cloneSource ?: PointF(x, y)
            if (cloneLocked || cloneOffset == null) cloneOffset = PointF(x - s.x, y - s.y)
            else cloneOffset?.let { o -> cloneSource = PointF(x - o.x, y - o.y) }
        }
        ed.invalidate()
    }

    fun strokeMove(x: Float, y: Float, pressure: Float, stylus: Boolean) {
        val s = stroke ?: return
        s.add(InputPoint(x, y, if (stylus) ed.pressureCurve.map(pressure) else 1f))
        strokeLast = PointF(x, y)
        if (type == AdjustmentType.CLONE && !cloneLocked) cloneOffset?.let { o -> cloneSource = PointF(x - o.x, y - o.y) }
        ed.invalidate()
    }

    private fun strokePaint(s: BrushStroke) = Paint().apply { alpha = (s.compositeOpacity * 255f).toInt().coerceIn(0, 255) }

    /** Draws the current stroke's coverage (white) with its opacity. */
    private fun drawStrokeMask(c: Canvas, s: BrushStroke) {
        val bounds = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        c.saveLayer(bounds, strokePaint(s))
        ed.strokeBuffer.drawTo(c, null)
        ed.selection?.let { c.drawBitmap(it, 0f, 0f, dstIn) }
        c.restore()
    }

    /** Source pixels shifted to where the stroke paints, confined to the stroke. */
    private fun drawCloneStroke(c: Canvas, s: BrushStroke) {
        val off = cloneOffset ?: return
        val sb = srcBitmap ?: return
        val bounds = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        c.saveLayer(bounds, null)
        drawStrokeMask(c, s)
        c.drawBitmap(sb, off.x, off.y, Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC_IN })
        c.restore()
    }

    fun strokeUp() {
        val s = stroke ?: return
        s.end()
        if (type == AdjustmentType.CLONE) cloneResult?.let { drawCloneStroke(Canvas(it), s) }
        else pencilMask?.let { m ->
            val c = Canvas(m)
            if (strokeErase) { c.saveLayer(null, dstOut); drawStrokeMask(c, s); c.restore() } else drawStrokeMask(c, s)
        }
        stroke = null
        ed.strokeBuffer.clear()
        ed.invalidate()
    }

    val stroking get() = stroke != null

    fun strokeCancel() { stroke = null; ed.strokeBuffer.clear(); ed.invalidate() }

    /** Original × (1 − mask) + filtered × mask, exact in premultiplied space. */
    private fun drawPencilComposite(c: Canvas) {
        val sb = srcBitmap ?: return; if (preview == null) return; val m = pencilMask ?: return
        val bounds = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        val s = stroke
        c.saveLayer(bounds, null)
        c.drawBitmap(sb, 0f, 0f, null)
        c.saveLayer(bounds, dstOut); c.drawBitmap(m, 0f, 0f, null); if (s != null && !strokeErase) drawStrokeMask(c, s); c.restore()
        c.saveLayer(bounds, plus)
        drawFiltered(c, null)
        c.saveLayer(bounds, dstIn)
        c.drawBitmap(m, 0f, 0f, null)
        if (s != null) { if (strokeErase) { c.saveLayer(null, dstOut); drawStrokeMask(c, s); c.restore() } else drawStrokeMask(c, s) }
        c.restore()
        c.restore()
        c.restore()
    }

    fun drawLive(canvas: Canvas, l: Layer, paint: Paint?): Boolean {
        if (l !== layer) return false
        liquify?.let { it.draw(canvas, paint ?: Paint(Paint.FILTER_BITMAP_FLAG)); return true }
        val bounds = RectF(0f, 0f, ed.doc.width.toFloat(), ed.doc.height.toFloat())
        if (showOriginal) { srcBitmap?.let { canvas.drawBitmap(it, 0f, 0f, paint); return true } }
        if (type == AdjustmentType.CLONE) {
            val cr = cloneResult ?: return false
            canvas.saveLayer(bounds, paint)
            canvas.drawBitmap(cr, 0f, 0f, null)
            stroke?.let { drawCloneStroke(canvas, it) }
            canvas.restore()
            return true
        }
        if (pencil) { canvas.saveLayer(bounds, paint); drawPencilComposite(canvas); canvas.restore(); return true }
        if (preview == null) return false
        drawFiltered(canvas, paint)
        return true
    }

    /** Commits the adjustment to the layer. */
    fun apply() {
        val l = layer ?: return
        val t = type ?: return
        if (stroke != null) strokeUp()
        val full = Rect(0, 0, ed.doc.width, ed.doc.height)
        when {
            t == AdjustmentType.LIQUIFY -> {
                val lq = liquify!!
                if (lq.changed) {
                    val before = lq.source
                    val result = lq.render()
                    ed.selection?.let { sel ->
                        // keep pixels outside the selection unchanged
                        val c = Canvas(result)
                        c.drawBitmap(sel, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
                        val base = before.copy(Bitmap.Config.ARGB_8888, true)
                        Canvas(base).apply { drawBitmap(sel, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT }); drawBitmap(result, 0f, 0f, null) }
                        result.recycle()
                        Bitmaps.paste(l.bitmap, base, 0, 0); base.recycle()
                    } ?: run { Bitmaps.paste(l.bitmap, result, 0, 0); result.recycle() }
                    ed.history.push(PixelEntry(L.t("픽셀 유동화"), l, full, before, l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
                    l.touch(); ed.afterCommit()
                }
            }
            t == AdjustmentType.CLONE -> {
                val cr = cloneResult ?: return
                val before = srcBitmap ?: return
                Bitmaps.paste(l.bitmap, cr, 0, 0)
                ed.history.push(PixelEntry(L.t("복제"), l, full, before.copy(Bitmap.Config.ARGB_8888, false), l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
                l.touch(); ed.afterCommit()
            }
            else -> {
                // make sure the preview reflects the latest parameters
                // the async job finishes on the main thread, so never block on it here: recompute synchronously instead
                if (!previewFull) finalizeSync()
                val pv = preview ?: return
                val before = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (pencil) {
                    val out = Bitmap.createBitmap(ed.doc.width, ed.doc.height, Bitmap.Config.ARGB_8888)
                    drawPencilComposite(Canvas(out))
                    Bitmaps.paste(l.bitmap, out, 0, 0); out.recycle()
                } else Bitmaps.paste(l.bitmap, pv, 0, 0)
                ed.history.push(PixelEntry(t.label, l, full, before, l.bitmap.copy(Bitmap.Config.ARGB_8888, false)))
                l.touch(); ed.afterCommit()
            }
        }
        end()
    }

    private fun finalizeSync() {
        if (type == null) return
        val s = src ?: return
        val w = ed.doc.width; val h = ed.doc.height
        // stop background passes first so the final one gets every core
        fullToken?.cancelled = true; fullJob?.cancel(); job?.cancel(); pending = false
        val out = runFilter(snapshot(), s, w, h, selBytes)
        preview?.setPixels(out, 0, w, 0, 0, w, h)
        fullGen = gen
    }

    fun cancel() { end(); ed.invalidate() }

    private fun end() {
        job?.cancel(); job = null; pending = false
        fullToken?.cancelled = true; fullJob?.cancel(); fullJob = null; fullToken = null
        lowSrc = null; lowSel = null; lowPreview = null; fullGen = -1
        stroke = null; ed.strokeBuffer.clear()
        preview?.recycle(); preview = null
        pencilMask?.recycle(); pencilMask = null
        cloneResult?.recycle(); cloneResult = null
        srcBitmap = null
        src = null; selBytes = null; layer = null
        liquify = null
        type = null
        computing = false
        showOriginal = false
        if (ed.mode == Mode.ADJUST || ed.mode == Mode.LIQUIFY) ed.mode = Mode.PAINT
        version++
    }

    // liquify parameters forwarded to the session (kept as Compose state for sliders)
    var lqMode by mutableStateOf(LiquifyMode.PUSH)
    var lqSize by mutableFloatStateOf(0.3f)
    var lqPressure by mutableFloatStateOf(0.6f)
    var lqDistortion by mutableFloatStateOf(0f)
    var lqMomentum by mutableFloatStateOf(0f)

    fun syncLiquify() {
        liquify?.apply { mode = lqMode; size = lqSize; pressure = lqPressure; distortion = lqDistortion; momentum = lqMomentum }
    }
}
