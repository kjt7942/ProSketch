package com.prosketch.app.engine

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicLong

/**
 * Layer blend modes in Procreate's order. Modes with a native Skia [mode] render on the GPU; the
 * others ([mode] == null) are composited exactly on the CPU by [SoftBlend].
 */
enum class LayerBlend(val label: String, val short: String, val mode: BlendMode?, val psdKey: String, val group: Int) {
    NORMAL(L.t("보통"), "N", BlendMode.SRC_OVER, "norm", 0),
    MULTIPLY(L.t("곱하기"), "M", BlendMode.MULTIPLY, "mul ", 1),
    DARKEN(L.t("어둡게"), "D", BlendMode.DARKEN, "dark", 1),
    COLOR_BURN(L.t("색상 번"), "Cb", BlendMode.COLOR_BURN, "idiv", 1),
    LINEAR_BURN(L.t("선형 번"), "Lb", null, "lbrn", 1),
    DARKER_COLOR(L.t("더 어두운 색상"), "Dc", null, "dkCl", 1),
    LIGHTEN(L.t("밝게"), "L", BlendMode.LIGHTEN, "lite", 2),
    SCREEN(L.t("스크린"), "S", BlendMode.SCREEN, "scrn", 2),
    COLOR_DODGE(L.t("색상 닷지"), "Cd", BlendMode.COLOR_DODGE, "div ", 2),
    ADD(L.t("추가"), "A", BlendMode.PLUS, "lddg", 2),
    LIGHTER_COLOR(L.t("더 밝은 색상"), "Lc", null, "lgCl", 2),
    OVERLAY(L.t("오버레이"), "O", BlendMode.OVERLAY, "over", 3),
    SOFT_LIGHT(L.t("소프트 라이트"), "Sl", BlendMode.SOFT_LIGHT, "sLit", 3),
    HARD_LIGHT(L.t("하드 라이트"), "Hl", BlendMode.HARD_LIGHT, "hLit", 3),
    VIVID_LIGHT(L.t("선명한 라이트"), "Vl", null, "vLit", 3),
    LINEAR_LIGHT(L.t("선형 라이트"), "Ll", null, "lLit", 3),
    PIN_LIGHT(L.t("핀 라이트"), "Pl", null, "pLit", 3),
    HARD_MIX(L.t("하드 혼합"), "Hm", null, "hMix", 3),
    DIFFERENCE(L.t("차이"), "Di", BlendMode.DIFFERENCE, "diff", 4),
    EXCLUSION(L.t("제외"), "E", BlendMode.EXCLUSION, "smud", 4),
    SUBTRACT(L.t("빼기"), "Su", null, "fsub", 4),
    DIVIDE(L.t("나누기"), "Dv", null, "fdiv", 4),
    HUE(L.t("색조"), "H", BlendMode.HUE, "hue ", 5),
    SATURATION(L.t("채도"), "Sa", BlendMode.SATURATION, "sat ", 5),
    COLOR(L.t("색상"), "C", BlendMode.COLOR, "colr", 5),
    LUMINOSITY(L.t("광도"), "Lu", BlendMode.LUMINOSITY, "lum ", 5);

    val isCustom get() = mode == null
    /** Mode used where only native modes are possible (e.g. while recording a GPU display list). */
    val nativeOrNormal: BlendMode get() = mode ?: BlendMode.SRC_OVER

    companion object {
        fun fromName(n: String?): LayerBlend = entries.firstOrNull { it.name == n } ?: NORMAL
        fun fromPsd(key: String): LayerBlend = entries.firstOrNull { it.psdKey == key } ?: NORMAL
    }
}

private val idGen = AtomicLong(System.currentTimeMillis())
fun newId(): Long = idGen.incrementAndGet()

/**
 * A layer. Pixel layers own a full-canvas premultiplied ARGB_8888 bitmap. Group layers own a 1×1
 * placeholder; their descendants sit directly below them in [Document.layers] (PSD order).
 * A layer mask is itself a [Layer] (opaque greyscale, white = visible) whose [owner] is the masked
 * layer, so every painting tool, filter and transform can edit it unchanged.
 */
class Layer(
    var bitmap: Bitmap,
    var name: String,
    val id: Long = newId(),
    val isGroup: Boolean = false,
) {
    var visible = true
    var opacity = 1f
    var blend = LayerBlend.NORMAL
    var alphaLock = false
    var clipping = false
    var isReference = false
    var locked = false
    var isPrivate = false        // excluded from time-lapse and the gallery thumbnail
    var parentId = 0L            // 0 = top level
    var expanded = true          // groups: children shown in the layers panel
    var passThrough = true       // groups: children blend straight into what is below the group
    var text: TextSpec? = null   // editable text layer; pixels are re-rendered from it
    var holdFrames = 0           // Animation Assist: extra frames this top-level frame is held for
    var mask: Layer? = null
        set(v) { field = v; v?.owner = this }
    var owner: Layer? = null     // set on mask layers
        private set
    val isMask get() = owner != null
    val isText get() = text != null

    /** Incremented on any change of pixels or properties; used for render caches & saving. */
    var version = 0L
        private set
    var savedVersion = -1L

    private var thumb: Bitmap? = null
    private var thumbVersion = -1L

    fun touch() { version++; owner?.touch() }

    /** Version including the mask, for render-cache signatures. */
    val renderVersion get() = version * 31 + (mask?.version ?: -1L)

    fun props() = LayerProps(name, visible, opacity, blend, alphaLock, clipping, isReference, locked, isPrivate, passThrough, expanded)
    fun applyProps(p: LayerProps) {
        name = p.name; visible = p.visible; opacity = p.opacity; blend = p.blend
        alphaLock = p.alphaLock; clipping = p.clipping; isReference = p.isReference; locked = p.locked
        isPrivate = p.isPrivate; passThrough = p.passThrough; expanded = p.expanded
        touch()
    }

    fun thumbnail(maxSize: Int = 160): Bitmap {
        val t = thumb
        if (t != null && thumbVersion == version) return t
        val src = bitmap
        val s = maxSize.toFloat() / maxOf(src.width, src.height)
        val nt = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * s).toInt()), maxOf(1, (src.height * s).toInt()), true)
        thumb = nt
        thumbVersion = version
        return nt
    }

    /** Deep copy of this layer and its mask (a group copy has no children; see [Document.duplicateBlock]). */
    fun copy(newName: String = name): Layer {
        val bmp = if (isGroup) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val l = Layer(bmp, newName, isGroup = isGroup)
        l.applyProps(props().copy(name = newName))
        l.parentId = parentId
        l.text = text
        l.holdFrames = holdFrames
        mask?.let { l.mask = it.copy(it.name) }
        return l
    }

    companion object {
        fun blank(w: Int, h: Int, name: String): Layer =
            Layer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), name)

        fun group(name: String): Layer = Layer(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), name, isGroup = true)

        /** A mask that reveals everything. */
        fun whiteMask(w: Int, h: Int): Layer =
            Layer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }, L.t("마스크"))
    }
}

data class LayerProps(
    val name: String, val visible: Boolean, val opacity: Float, val blend: LayerBlend,
    val alphaLock: Boolean, val clipping: Boolean, val isReference: Boolean, val locked: Boolean,
    val isPrivate: Boolean = false, val passThrough: Boolean = true, val expanded: Boolean = true,
)

/** A sibling drawn as one unit: a base layer (or group) plus the clipping layers stacked on it. */
class ClipItem(val base: Layer, val clips: List<Layer>) {
    val layers get() = listOf(base) + clips
}

/** Where a layer sits in the tree, captured for undo of structural edits. */
data class TreeState(val parentId: Long, val mask: Layer?)

class Document(
    val id: String,
    var width: Int,
    var height: Int,
) {
    var name: String = L.t("무제 작품")
    val layers = mutableListOf<Layer>()   // bottom → top; descendants directly below their group
    var activeIndex = 0
    /** The active layer's mask is edited instead of its pixels. */
    var editMask = false
    var backgroundColor: Int = Color.WHITE
    var backgroundVisible = true
    var backgroundVersion = 0L
    var strokeCount = 0
    var timeSpentMs = 0L
    var createdAt = System.currentTimeMillis()
    var guides = GuideConfig()
    var animation = AnimationConfig()
    var timelapseEnabled = true

    /** The layer selected in the panel (never a mask). */
    val activeLayer: Layer? get() = layers.getOrNull(activeIndex)

    /** What painting tools edit: the active layer, or its mask while [editMask] is on. */
    val active: Layer? get() = activeLayer?.let { l -> if (editMask) l.mask ?: l else l }

    fun indexOf(layer: Layer) = layers.indexOf(layer.owner ?: layer)

    fun byId(id: Long): Layer? = if (id == 0L) null else layers.firstOrNull { it.id == id }
    fun parentOf(l: Layer): Layer? = byId((l.owner ?: l).parentId)

    fun ancestors(l: Layer): Sequence<Layer> = generateSequence(parentOf(l)) { parentOf(it) }
    fun depth(l: Layer): Int = ancestors(l).count()
    fun isDescendant(l: Layer, group: Layer): Boolean = ancestors(l).any { it === group }

    /** Visible including every enclosing group. */
    fun effectiveVisible(l: Layer): Boolean { val b = l.owner ?: l; return b.visible && ancestors(b).all { it.visible } }
    /** Locked directly, through a locked group, or (for masks) through the masked layer. */
    fun effectiveLocked(l: Layer): Boolean { val b = l.owner ?: l; return b.locked || ancestors(b).any { it.locked } }

    /** Direct children of [parentId] (0 = top level), bottom → top. */
    fun children(parentId: Long): List<Layer> = layers.filter { it.parentId == parentId }

    /** Every pixel layer inside [l], or [l] itself when it is not a group. */
    fun pixelLayers(l: Layer): List<Layer> = if (!l.isGroup) listOf(l) else layers.filter { !it.isGroup && isDescendant(it, l) }

    /** Flat-index range of a group with all its descendants (just the index for a layer). */
    fun blockRange(i: Int): IntRange {
        val g = layers[i]
        if (!g.isGroup) return i..i
        var s = i
        while (s - 1 >= 0 && isDescendant(layers[s - 1], g)) s--
        return s..i
    }

    /** Top-level siblings, which are the frames of Animation Assist. */
    fun topLevel(): List<Layer> = children(0L)
    fun topAncestor(l: Layer): Layer = ancestors(l).lastOrNull() ?: (l.owner ?: l)

    /** Siblings of [parentId] grouped into clip items, bottom → top. */
    fun items(parentId: Long = 0L): List<ClipItem> {
        val sib = children(parentId)
        val out = ArrayList<ClipItem>()
        var i = 0
        while (i < sib.size) {
            val base = sib[i]
            var j = i + 1
            val clips = ArrayList<Layer>()
            while (j < sib.size && sib[j].clipping && !sib[j].isGroup) { clips.add(sib[j]); j++ }
            out.add(ClipItem(base, clips))
            i = j
        }
        return out
    }

    /** Every layer and mask a clip item draws, for cache signatures. */
    fun itemLayers(item: ClipItem): List<Layer> = item.layers.flatMap { l ->
        val own = if (l.isGroup) layers.filter { isDescendant(it, l) } + l else listOf(l)
        own + own.mapNotNull { it.mask }
    }

    fun usesCustomBlend(item: ClipItem): Boolean = itemLayers(item).any { it.blend.isCustom && !it.isMask }

    /**
     * Restores the tree invariant after structural edits: dangling parents become top level, and every
     * group's descendants sit contiguously directly below it, keeping their relative order.
     */
    fun normalize() {
        val active = activeLayer
        val groupIds = layers.filter { it.isGroup }.map { it.id }.toSet()
        for (l in layers) if (l.parentId != 0L && (l.parentId !in groupIds || l.parentId == l.id)) l.parentId = 0L
        for (l in layers) {   // break cycles
            val seen = HashSet<Long>()
            var p = l.parentId
            while (p != 0L) { if (!seen.add(p)) { l.parentId = 0L; break }; p = byId(p)?.parentId ?: 0L }
        }
        val out = ArrayList<Layer>(layers.size)
        fun emit(parent: Long) {
            for (l in layers.filter { it.parentId == parent }) {
                if (l.isGroup) emit(l.id)
                out.add(l)
            }
        }
        emit(0L)
        layers.clear(); layers.addAll(out)
        val ai = active?.let { layers.indexOf(it) } ?: -1
        activeIndex = if (ai >= 0) ai else activeIndex.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
        if (activeLayer?.mask == null) editMask = false
    }

    fun treeState(): Map<Layer, TreeState> = layers.associateWith { TreeState(it.parentId, it.mask) }
    fun applyTreeState(s: Map<Layer, TreeState>) { for ((l, t) in s) { l.parentId = t.parentId; l.mask = t.mask } }

    fun maxLayers(totalMemBytes: Long): Int {
        val perLayer = width.toLong() * height * 4
        val budget = (totalMemBytes * 0.16).toLong()
        return (budget / perLayer).toInt().coerceIn(4, 200)
    }

    /** Layers that cost a full bitmap: groups are free, masks count. */
    fun pixelBudgetUsed(): Int = layers.count { !it.isGroup } + layers.count { it.mask != null }

    fun touchBackground() { backgroundVersion++ }

    /** Same layer objects in a new list; safe to read from another thread while the editor continues. */
    fun shallowCopy(): Document = Document(id, width, height).also { d ->
        d.name = name; d.layers.addAll(layers); d.activeIndex = activeIndex
        d.backgroundColor = backgroundColor; d.backgroundVisible = backgroundVisible
        d.animation = animation; d.guides = guides
    }

    fun bounds() = Rect(0, 0, width, height)
}

object Bitmaps {
    val srcPaint = Paint().apply { blendMode = BlendMode.SRC }

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, -r.left.toFloat(), -r.top.toFloat(), srcPaint)
        return out
    }

    fun paste(dst: Bitmap, src: Bitmap, left: Int, top: Int) {
        Canvas(dst).drawBitmap(src, left.toFloat(), top.toFloat(), srcPaint)
    }

    /** Bounding box of pixels with non-zero alpha, or null if empty. */
    fun opaqueBounds(bmp: Bitmap): Rect? {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var rowHit = false
            for (x in 0 until w) {
                if (row[x] ushr 24 != 0) {
                    rowHit = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (rowHit) { if (y < minY) minY = y; maxY = y }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    fun maskBounds(mask: Bitmap): Rect? {
        val w = mask.width; val h = mask.height
        val buf = ByteArray(mask.rowBytes * h)
        mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf))
        val stride = mask.rowBytes
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val o = y * stride
            if (o >= buf.size) break
            for (x in 0 until w) {
                if (buf[o + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }
}
