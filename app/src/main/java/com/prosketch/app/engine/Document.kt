package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicLong

/** Layer blend modes. Native Skia blend modes are used, so they render identically on GPU and CPU. */
enum class LayerBlend(val label: String, val short: String, val mode: BlendMode, val psdKey: String) {
    NORMAL("보통", "N", BlendMode.SRC_OVER, "norm"),
    MULTIPLY("곱하기", "M", BlendMode.MULTIPLY, "mul "),
    DARKEN("어둡게", "D", BlendMode.DARKEN, "dark"),
    COLOR_BURN("색상 번", "Cb", BlendMode.COLOR_BURN, "idiv"),
    LIGHTEN("밝게", "L", BlendMode.LIGHTEN, "lite"),
    SCREEN("스크린", "S", BlendMode.SCREEN, "scrn"),
    COLOR_DODGE("색상 닷지", "Cd", BlendMode.COLOR_DODGE, "div "),
    ADD("추가", "A", BlendMode.PLUS, "lddg"),
    OVERLAY("오버레이", "O", BlendMode.OVERLAY, "over"),
    SOFT_LIGHT("소프트 라이트", "Sl", BlendMode.SOFT_LIGHT, "sLit"),
    HARD_LIGHT("하드 라이트", "Hl", BlendMode.HARD_LIGHT, "hLit"),
    DIFFERENCE("차이", "Di", BlendMode.DIFFERENCE, "diff"),
    EXCLUSION("제외", "E", BlendMode.EXCLUSION, "smud"),
    HUE("색조", "H", BlendMode.HUE, "hue "),
    SATURATION("채도", "Sa", BlendMode.SATURATION, "sat "),
    COLOR("색상", "C", BlendMode.COLOR, "colr"),
    LUMINOSITY("광도", "Lu", BlendMode.LUMINOSITY, "lum ");

    /** Group used by the blend picker, mirroring Procreate's grouping. */
    val group: Int
        get() = when (this) {
            NORMAL -> 0
            MULTIPLY, DARKEN, COLOR_BURN -> 1
            LIGHTEN, SCREEN, COLOR_DODGE, ADD -> 2
            OVERLAY, SOFT_LIGHT, HARD_LIGHT -> 3
            DIFFERENCE, EXCLUSION -> 4
            HUE, SATURATION, COLOR, LUMINOSITY -> 5
        }

    companion object {
        fun fromName(n: String?): LayerBlend = entries.firstOrNull { it.name == n } ?: NORMAL
        fun fromPsd(key: String): LayerBlend = entries.firstOrNull { it.psdKey == key } ?: NORMAL
    }
}

private val idGen = AtomicLong(System.currentTimeMillis())
fun newId(): Long = idGen.incrementAndGet()

/** A raster layer. Pixel data lives in a full-canvas premultiplied ARGB_8888 bitmap. */
class Layer(
    var bitmap: Bitmap,
    var name: String,
    val id: Long = newId(),
) {
    var visible = true
    var opacity = 1f
    var blend = LayerBlend.NORMAL
    var alphaLock = false
    var clipping = false
    var isReference = false
    var locked = false

    /** Incremented on any change of pixels or properties; used for render caches & saving. */
    var version = 0L
        private set
    var savedVersion = -1L

    private var thumb: Bitmap? = null
    private var thumbVersion = -1L

    fun touch() { version++ }

    fun props() = LayerProps(name, visible, opacity, blend, alphaLock, clipping, isReference, locked)
    fun applyProps(p: LayerProps) {
        name = p.name; visible = p.visible; opacity = p.opacity; blend = p.blend
        alphaLock = p.alphaLock; clipping = p.clipping; isReference = p.isReference; locked = p.locked
        touch()
    }

    fun thumbnail(maxSize: Int = 160): Bitmap {
        val t = thumb
        if (t != null && thumbVersion == version) return t
        val s = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = maxOf(1, (bitmap.width * s).toInt())
        val h = maxOf(1, (bitmap.height * s).toInt())
        val nt = Bitmap.createScaledBitmap(bitmap, w, h, true)
        thumb = nt
        thumbVersion = version
        return nt
    }

    fun copy(newName: String = name): Layer {
        val l = Layer(bitmap.copy(Bitmap.Config.ARGB_8888, true), newName)
        l.applyProps(props().copy(name = newName))
        return l
    }

    companion object {
        fun blank(w: Int, h: Int, name: String): Layer =
            Layer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), name)
    }
}

data class LayerProps(
    val name: String, val visible: Boolean, val opacity: Float, val blend: LayerBlend,
    val alphaLock: Boolean, val clipping: Boolean, val isReference: Boolean, val locked: Boolean,
)

/** Groups a base layer with the clipping layers stacked directly above it. */
class LayerGroup(val baseIndex: Int, val endIndex: Int) {
    operator fun contains(i: Int) = i in baseIndex..endIndex
}

class Document(
    val id: String,
    var width: Int,
    var height: Int,
) {
    var name: String = "무제 작품"
    val layers = mutableListOf<Layer>()   // bottom → top
    var activeIndex = 0
    var backgroundColor: Int = Color.WHITE
    var backgroundVisible = true
    var backgroundVersion = 0L
    var strokeCount = 0
    var timeSpentMs = 0L
    var createdAt = System.currentTimeMillis()
    var guides = GuideConfig()
    var animation = AnimationConfig()
    var timelapseEnabled = true

    val active: Layer? get() = layers.getOrNull(activeIndex)

    fun indexOf(layer: Layer) = layers.indexOf(layer)

    /** Clipping groups from bottom to top. */
    fun groups(): List<LayerGroup> {
        val out = ArrayList<LayerGroup>()
        var i = 0
        while (i < layers.size) {
            var j = i + 1
            while (j < layers.size && layers[j].clipping) j++
            out.add(LayerGroup(i, j - 1))
            i = j
        }
        return out
    }

    fun maxLayers(totalMemBytes: Long): Int {
        val perLayer = width.toLong() * height * 4
        val budget = (totalMemBytes * 0.16).toLong()
        return (budget / perLayer).toInt().coerceIn(4, 200)
    }

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
