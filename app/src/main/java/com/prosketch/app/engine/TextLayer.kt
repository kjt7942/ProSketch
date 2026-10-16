package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * Editable ("vector") text. A text layer keeps this spec and re-renders its pixels from it, so the
 * words, font and style stay editable until the layer is rasterized.
 */
data class TextSpec(
    val text: String,
    val font: String = "sans-serif",     // system family, or "file:<path>" for an imported font
    val size: Float = 120f,               // px
    val color: Int = Color.BLACK,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: Int = 0,                   // 0 left, 1 centre, 2 right, 3 justified
    val tracking: Float = 0f,             // letter spacing in em
    val leading: Float = 1f,              // line height multiplier
    val baseline: Float = 0f,             // px, positive moves text down inside its box
    val underline: Boolean = false,
    val outline: Boolean = false,
    val vertical: Boolean = false,
    val allCaps: Boolean = false,
    val x: Float = 0f,                    // box position (document px, before rotation)
    val y: Float = 0f,
    val boxWidth: Float = 1000f,          // wrap width (when not [autoWidth])
    val autoWidth: Boolean = true,        // box grows with the longest line
    val rotation: Float = 0f,             // degrees about the box centre
    val scale: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text); put("font", font); put("size", size.toDouble()); put("color", color.toLong() and 0xFFFFFFFFL)
        put("bold", bold); put("italic", italic); put("align", align); put("tracking", tracking.toDouble())
        put("leading", leading.toDouble()); put("baseline", baseline.toDouble()); put("underline", underline)
        put("outline", outline); put("vertical", vertical); put("caps", allCaps)
        put("x", x.toDouble()); put("y", y.toDouble()); put("box", boxWidth.toDouble()); put("autoWidth", autoWidth)
        put("rotation", rotation.toDouble()); put("scale", scale.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = TextSpec(
            text = o.optString("text"), font = o.optString("font", "sans-serif"), size = o.optDouble("size", 120.0).toFloat(),
            color = o.optLong("color", 0xFF000000L).toInt(), bold = o.optBoolean("bold"), italic = o.optBoolean("italic"),
            align = o.optInt("align"), tracking = o.optDouble("tracking", 0.0).toFloat(), leading = o.optDouble("leading", 1.0).toFloat(),
            baseline = o.optDouble("baseline", 0.0).toFloat(), underline = o.optBoolean("underline"), outline = o.optBoolean("outline"),
            vertical = o.optBoolean("vertical"), allCaps = o.optBoolean("caps"),
            x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(), boxWidth = o.optDouble("box", 1000.0).toFloat(), autoWidth = o.optBoolean("autoWidth", false),
            rotation = o.optDouble("rotation", 0.0).toFloat(), scale = o.optDouble("scale", 1.0).toFloat(),
        )
    }
}

object TextRenderer {
    /** Built-in families offered in the font list (Android system fonts, Hangul falls back to the system CJK font). */
    val systemFamilies = listOf(
        "sans-serif" to "고딕 (Sans)",
        "sans-serif-medium" to "고딕 Medium",
        "sans-serif-light" to "고딕 Light",
        "sans-serif-thin" to "고딕 Thin",
        "sans-serif-black" to "고딕 Black",
        "sans-serif-condensed" to "고딕 Condensed",
        "serif" to "명조 (Serif)",
        "monospace" to "고정폭 (Mono)",
        "serif-monospace" to "고정폭 Serif",
        "casual" to "캐주얼",
        "cursive" to "필기체",
        "sans-serif-smallcaps" to "스몰 캡스",
    )

    private val fileCache = HashMap<String, Typeface>()

    fun typeface(spec: TextSpec): Typeface {
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = if (spec.font.startsWith("file:")) {
            val path = spec.font.removePrefix("file:")
            fileCache.getOrPut(path) { runCatching { Typeface.createFromFile(File(path)) }.getOrDefault(Typeface.SANS_SERIF) }
        } else Typeface.create(spec.font, Typeface.NORMAL)
        return Typeface.create(base, style)
    }

    fun paint(spec: TextSpec): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(spec)
        textSize = spec.size
        color = spec.color or (0xFF shl 24)
        letterSpacing = spec.tracking
        isUnderlineText = spec.underline
        if (spec.outline) { style = Paint.Style.STROKE; strokeWidth = max(1.5f, spec.size / 28f); strokeJoin = Paint.Join.ROUND }
    }

    private fun content(spec: TextSpec) = if (spec.allCaps) spec.text.uppercase() else spec.text

    /** Wrap width actually used: the longest line for auto-width boxes. */
    fun wrapWidth(spec: TextSpec, tp: TextPaint = paint(spec)): Float =
        if (!spec.autoWidth) spec.boxWidth
        else ceil(content(spec).split('\n').maxOf { tp.measureText(it) } + spec.size * 0.15f).coerceAtLeast(spec.size * 0.5f)

    private fun layout(spec: TextSpec, tp: TextPaint): StaticLayout {
        val txt = content(spec)
        val align = when (spec.align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val b = StaticLayout.Builder.obtain(txt, 0, txt.length, tp, max(1, wrapWidth(spec, tp).toInt()))
            .setAlignment(align).setLineSpacing(0f, spec.leading.coerceIn(0.5f, 4f)).setIncludePad(false)
        if (spec.align == 3) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    /** Unrotated, unscaled size of the text box. */
    fun boxSize(spec: TextSpec): Pair<Float, Float> {
        val tp = paint(spec)
        if (spec.vertical) {
            val cols = content(spec).split('\n')
            val lineH = spec.size * spec.leading.coerceIn(0.5f, 4f) * 1.1f
            val h = cols.maxOf { it.length.coerceAtLeast(1) } * lineH
            return cols.size * spec.size * 1.25f to h + spec.baseline.coerceAtLeast(0f)
        }
        val l = layout(spec, tp)
        return wrapWidth(spec, tp) to l.height.toFloat() + spec.baseline.coerceAtLeast(0f)
    }

    /** Matrix from box space (0,0 = top-left of the box) to document space. */
    fun boxMatrix(spec: TextSpec): Matrix {
        val (w, h) = boxSize(spec)
        return Matrix().apply {
            postScale(spec.scale, spec.scale)
            postTranslate(spec.x, spec.y)
            postRotate(spec.rotation, spec.x + w * spec.scale / 2f, spec.y + h * spec.scale / 2f)
        }
    }

    /** The four corners of the box in document space (x0,y0, x1,y1, x2,y2, x3,y3). */
    fun corners(spec: TextSpec): FloatArray {
        val (w, h) = boxSize(spec)
        val pts = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        boxMatrix(spec).mapPoints(pts)
        return pts
    }

    fun boundsOf(spec: TextSpec): RectF {
        val c = corners(spec)
        return RectF(minOf(c[0], c[2], c[4], c[6]), minOf(c[1], c[3], c[5], c[7]), maxOf(c[0], c[2], c[4], c[6]), maxOf(c[1], c[3], c[5], c[7]))
    }

    /** Draws the text into [canvas] in document coordinates. */
    fun draw(canvas: Canvas, spec: TextSpec) {
        val tp = paint(spec)
        canvas.save()
        canvas.concat(boxMatrix(spec))
        canvas.translate(0f, spec.baseline)
        if (spec.vertical) {
            val cols = content(spec).split('\n')
            val colW = spec.size * 1.25f
            val lineH = spec.size * spec.leading.coerceIn(0.5f, 4f) * 1.1f
            tp.textAlign = Paint.Align.CENTER
            cols.forEachIndexed { ci, col ->
                val cx = (cols.size - 1 - ci) * colW + colW / 2f   // columns run right → left
                var cy = spec.size
                var i = 0
                while (i < col.length) {
                    val cp = col.codePointAt(i)
                    val s = String(Character.toChars(cp))
                    canvas.drawText(s, cx, cy, tp)
                    cy += lineH
                    i += Character.charCount(cp)
                }
            }
        } else layout(spec, tp).draw(canvas)
        canvas.restore()
    }

    /** Renders into a fresh full-document bitmap. */
    fun render(spec: TextSpec, docW: Int, docH: Int): Bitmap {
        val b = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), spec)
        return b
    }

    /** Default wrap width for new text: wide enough for the words, at most 90 % of the canvas. */
    fun fitWidth(spec: TextSpec, docW: Int): Float {
        val tp = paint(spec)
        val widest = content(spec).split('\n').maxOf { tp.measureText(it) }
        return ceil(widest + spec.size * 0.2f).coerceIn(spec.size, docW * 0.9f)
    }
}
