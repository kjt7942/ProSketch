package com.prosketch.app.engine

import com.prosketch.app.L

import android.graphics.Color
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One adjustment parameter. [choices] makes it a segmented choice (value = index); [hidden] params are
 * set on the canvas (focal point, direction); [visibleWhen] shows it only for some choice values.
 */
data class FilterParam(
    val key: String, val label: String, val min: Float, val max: Float, val default: Float, val unit: String = "%",
    val choices: List<String>? = null, val hidden: Boolean = false, val visibleWhen: Pair<String, Set<Int>>? = null,
) {
    fun visible(p: Map<String, Float>) = !hidden && (visibleWhen == null || ((p[visibleWhen.first] ?: 0f).roundToInt() in visibleWhen.second))
    val isSwitch get() = choices == null && unit.isEmpty() && max <= 1f
}

private fun choice(key: String, label: String, options: List<String>, default: Int = 0) =
    FilterParam(key, label, 0f, (options.size - 1).toFloat(), default.toFloat(), "", choices = options)

enum class AdjustmentType(val label: String, val params: List<FilterParam>, val section: Int) {
    HSB(L.t("색조, 채도, 밝기"), listOf(
        FilterParam("hue", L.t("색조"), -180f, 180f, 0f, "°"),
        FilterParam("sat", L.t("채도"), -100f, 100f, 0f),
        FilterParam("bri", L.t("밝기"), -100f, 100f, 0f)), 0),
    COLOR_BALANCE(L.t("색상 균형"), listOf(
        FilterParam("cr", L.t("청록 ↔ 빨강"), -100f, 100f, 0f),
        FilterParam("mg", L.t("자홍 ↔ 녹색"), -100f, 100f, 0f),
        FilterParam("yb", L.t("노랑 ↔ 파랑"), -100f, 100f, 0f)), 0),
    CURVES(L.t("커브"), emptyList(), 0),
    GRADIENT_MAP(L.t("그라디언트 맵"), listOf(FilterParam("amount", L.t("강도"), 0f, 100f, 100f)), 0),
    RECOLOR(L.t("재채색"), listOf(
        FilterParam("flood", L.t("채우기 범위"), 0f, 100f, 25f),
        FilterParam("tx", "", 0f, 1f, 0.5f, hidden = true), FilterParam("ty", "", 0f, 1f, 0.5f, hidden = true)), 0),
    BRIGHTNESS_CONTRAST(L.t("밝기/대비"), listOf(
        FilterParam("bri", L.t("밝기"), -100f, 100f, 0f),
        FilterParam("con", L.t("대비"), -100f, 100f, 0f)), 0),
    POSTERIZE(L.t("포스터화"), listOf(FilterParam("levels", L.t("단계"), 2f, 32f, 6f, "")), 0),
    INVERT(L.t("색상 반전"), listOf(FilterParam("amount", L.t("강도"), 0f, 100f, 100f)), 0),
    GAUSSIAN_BLUR(L.t("가우시안 흐림 효과"), listOf(FilterParam("radius", L.t("흐림"), 0f, 100f, 15f)), 1),
    MOTION_BLUR(L.t("움직임 흐림 효과"), listOf(
        FilterParam("dist", L.t("거리"), 0f, 100f, 20f),
        FilterParam("angle", L.t("각도"), 0f, 360f, 0f, "°")), 1),
    PERSPECTIVE_BLUR(L.t("원근 흐림 효과"), listOf(
        FilterParam("amount", L.t("흐림"), 0f, 100f, 25f),
        choice("mode", L.t("방식"), listOf(L.t("위치"), L.t("방향"))),
        FilterParam("angle", L.t("방향"), 0f, 360f, 0f, "°", visibleWhen = "mode" to setOf(1)),
        FilterParam("fx", "", 0f, 1f, 0.5f, hidden = true), FilterParam("fy", "", 0f, 1f, 0.5f, hidden = true)), 1),
    SHARPEN(L.t("선명하게"), listOf(FilterParam("amount", L.t("선명도"), 0f, 100f, 40f)), 2),
    NOISE(L.t("노이즈"), listOf(
        FilterParam("amount", L.t("노이즈"), 0f, 100f, 30f),
        choice("style", L.t("모양"), listOf(L.t("구름"), L.t("물결"), L.t("능선"))),
        FilterParam("scale", L.t("크기"), 1f, 100f, 25f),
        FilterParam("octaves", L.t("옥타브"), 1f, 8f, 4f, ""),
        FilterParam("turb", L.t("난류"), 0f, 100f, 0f),
        choice("multi", L.t("채널"), listOf(L.t("단색"), L.t("다색"))),
        FilterParam("additive", L.t("더하기 (원본 위에 겹침)"), 0f, 1f, 1f, "")), 2),
    BLOOM(L.t("블룸"), listOf(
        FilterParam("amount", L.t("블룸"), 0f, 100f, 40f),
        FilterParam("threshold", L.t("전환"), 0f, 100f, 55f),
        FilterParam("size", L.t("크기"), 0f, 100f, 35f),
        FilterParam("burn", L.t("번"), 0f, 100f, 50f)), 2),
    GLITCH(L.t("글리치"), listOf(
        FilterParam("amount", L.t("글리치"), 0f, 100f, 35f),
        choice("mode", L.t("효과"), listOf(L.t("아티팩트"), L.t("웨이브"), L.t("시그널"), L.t("분산"))),
        FilterParam("block", L.t("블록 크기"), 1f, 100f, 30f, visibleWhen = "mode" to setOf(0, 2)),
        FilterParam("amp", L.t("진폭"), 0f, 100f, 40f, visibleWhen = "mode" to setOf(1)),
        FilterParam("freq", L.t("주파수"), 0f, 100f, 40f, visibleWhen = "mode" to setOf(1)),
        FilterParam("rs", L.t("빨강 이동"), -100f, 100f, 30f, visibleWhen = "mode" to setOf(3)),
        FilterParam("gs", L.t("녹색 이동"), -100f, 100f, -20f, visibleWhen = "mode" to setOf(3)),
        FilterParam("bs", L.t("파랑 이동"), -100f, 100f, 10f, visibleWhen = "mode" to setOf(3)),
        FilterParam("zoom", L.t("확대"), 1f, 100f, 50f),
        FilterParam("seed", L.t("변형"), 0f, 100f, 1f, "")), 2),
    HALFTONE(L.t("하프톤"), listOf(
        FilterParam("size", L.t("하프톤"), 1f, 100f, 25f),
        choice("style", L.t("방식"), listOf(L.t("풀 컬러"), L.t("스크린 프린트"), L.t("신문")))), 2),
    CHROMATIC(L.t("색수차"), listOf(
        FilterParam("amount", L.t("색수차"), 0f, 100f, 25f),
        choice("mode", L.t("방식"), listOf(L.t("원근"), L.t("변위"))),
        FilterParam("transition", L.t("전환"), 0f, 100f, 30f, visibleWhen = "mode" to setOf(0)),
        FilterParam("falloff", L.t("감쇠"), 0f, 100f, 30f, visibleWhen = "mode" to setOf(0)),
        FilterParam("blur", L.t("흐림"), 0f, 100f, 0f, visibleWhen = "mode" to setOf(1)),
        FilterParam("transparency", L.t("투명도"), 0f, 100f, 0f, visibleWhen = "mode" to setOf(1)),
        FilterParam("fx", "", 0f, 1f, 0.5f, hidden = true), FilterParam("fy", "", 0f, 1f, 0.5f, hidden = true),
        FilterParam("dx", "", -1f, 1f, 0.01f, hidden = true), FilterParam("dy", "", -1f, 1f, 0f, hidden = true)), 2),
    PIXELATE(L.t("모자이크"), listOf(FilterParam("size", L.t("크기"), 1f, 100f, 12f, "px")), 2),
    LIQUIFY(L.t("픽셀 유동화"), emptyList(), 3),
    CLONE(L.t("복제"), emptyList(), 3);

    /** Types that can be painted on with a brush instead of applied to the whole layer. */
    val supportsPencil get() = this != LIQUIFY && this != CLONE && this != RECOLOR
    /** Types with a focal point disc on the canvas. */
    val hasFocal get() = this == PERSPECTIVE_BLUR || this == CHROMATIC
}

data class GradientPreset(val name: String, val colors: IntArray, val stops: FloatArray)

object GradientPresets {
    val all = listOf(
        GradientPreset(L.t("느와르"), intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), floatArrayOf(0f, 1f)),
        GradientPreset(L.t("석양"), intArrayOf(0xFF1A0B3D.toInt(), 0xFFB0305C.toInt(), 0xFFF28B30.toInt(), 0xFFFFE9A8.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f)),
        GradientPreset(L.t("네온"), intArrayOf(0xFF0B0221.toInt(), 0xFF6A00F4.toInt(), 0xFFFF2E97.toInt(), 0xFF00F5D4.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f)),
        GradientPreset(L.t("빈티지"), intArrayOf(0xFF2B1B17.toInt(), 0xFF7A5C3E.toInt(), 0xFFD9B98C.toInt(), 0xFFF6EEDC.toInt()), floatArrayOf(0f, 0.35f, 0.75f, 1f)),
        GradientPreset(L.t("심해"), intArrayOf(0xFF000814.toInt(), 0xFF003566.toInt(), 0xFF0096C7.toInt(), 0xFFCAF0F8.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset(L.t("화산"), intArrayOf(0xFF0D0D0D.toInt(), 0xFF6A040F.toInt(), 0xFFE85D04.toInt(), 0xFFFFBA08.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset(L.t("민트"), intArrayOf(0xFF081C15.toInt(), 0xFF2D6A4F.toInt(), 0xFF74C69D.toInt(), 0xFFD8F3DC.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset(L.t("세피아"), intArrayOf(0xFF1C120C.toInt(), 0xFF8C6239.toInt(), 0xFFF5E6C8.toInt()), floatArrayOf(0f, 0.55f, 1f)),
        GradientPreset(L.t("듀오톤"), intArrayOf(0xFF2E1760.toInt(), 0xFFFFC857.toInt()), floatArrayOf(0f, 1f)),
    )
}

/** Tone curve per channel: control points in 0..1. */
class CurveSet {
    val master = mutableListOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
    val red = mutableListOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
    val green = mutableListOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
    val blue = mutableListOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))

    fun channel(i: Int) = when (i) { 1 -> red; 2 -> green; 3 -> blue; else -> master }

    fun copy(): CurveSet = CurveSet().also { c ->
        for (i in 0..3) { c.channel(i).clear(); channel(i).forEach { c.channel(i).add(it.copyOf()) } }
    }

    /** Monotone cubic (Fritsch–Carlson) LUT. */
    fun lut(points: List<FloatArray>): IntArray {
        val p = points.sortedBy { it[0] }
        val n = p.size
        val lut = IntArray(256)
        if (n < 2) { for (i in 0..255) lut[i] = i; return lut }
        val xs = FloatArray(n) { p[it][0] }; val ys = FloatArray(n) { p[it][1] }
        val d = FloatArray(n - 1) { i -> val dx = xs[i + 1] - xs[i]; if (dx <= 1e-6f) 0f else (ys[i + 1] - ys[i]) / dx }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        for (v in 0..255) {
            val x = v / 255f
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var k = 0
                    while (k < n - 2 && x > xs[k + 1]) k++
                    val h = xs[k + 1] - xs[k]
                    val t = (x - xs[k]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
                }
            }
            lut[v] = (y.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return lut
    }
}

/** Colour balance values for shadows / midtones / highlights (each cr, mg, yb in -100..100). */
class ColorBalanceSet {
    val v = Array(3) { FloatArray(3) }
    var preserveLuminosity = true
    fun copy() = ColorBalanceSet().also { c -> for (i in 0..2) v[i].copyInto(c.v[i]); c.preserveLuminosity = preserveLuminosity }
}

/**
 * Pixel filters. All arrays are unpremultiplied ARGB (as returned by Bitmap.getPixels).
 */
object Filters {
    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "filters").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    /** Lets a running filter be abandoned: checked between row chunks. */
    class CancelToken { @Volatile var cancelled = false }
    private val token = ThreadLocal<CancelToken?>()

    /** Runs [block] so that cancelling [t] aborts it with a CancellationException. */
    fun <T> cancellable(t: CancelToken, block: () -> T): T {
        token.set(t)
        try { return block() } finally { token.remove() }
    }

    fun parallelRows(h: Int, minChunk: Int = 16, block: (Int, Int) -> Unit) {
        val tok = token.get()
        if (tok?.cancelled == true) throw java.util.concurrent.CancellationException()
        val chunk = max(minChunk, (h + threads * 4 - 1) / (threads * 4))
        val tasks = ArrayList<Callable<Unit>>()
        var y = 0
        while (y < h) {
            val y0 = y; val y1 = min(h, y + chunk)
            tasks.add(Callable { if (tok?.cancelled != true) block(y0, y1) })
            y = y1
        }
        pool.invokeAll(tasks).forEach { it.get() }
        if (tok?.cancelled == true) throw java.util.concurrent.CancellationException()
    }

    inline fun a(c: Int) = c ushr 24
    inline fun r(c: Int) = (c shr 16) and 0xFF
    inline fun g(c: Int) = (c shr 8) and 0xFF
    inline fun b(c: Int) = c and 0xFF
    inline fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Generic entry. Pixel-sized params scale with the canvas (relative to 1000 px). [color] is used by Recolor. */
    fun apply(
        type: AdjustmentType, src: IntArray, w: Int, h: Int, params: Map<String, Float>,
        curves: CurveSet?, balance: ColorBalanceSet?, gradient: GradientPreset?, color: Int = Color.BLACK,
    ): IntArray {
        val out = IntArray(src.size)
        val docScale = max(w, h) / 1000f
        val ds = docScale.coerceAtLeast(0.5f)
        fun p(k: String) = params[k] ?: type.params.firstOrNull { it.key == k }?.default ?: 0f
        fun i(k: String) = p(k).roundToInt()
        when (type) {
            AdjustmentType.HSB -> hsb(src, out, w, h, p("hue"), p("sat") / 100f, p("bri") / 100f)
            AdjustmentType.COLOR_BALANCE -> colorBalance(src, out, w, h, balance ?: ColorBalanceSet())
            AdjustmentType.CURVES -> curves(src, out, w, h, curves ?: CurveSet())
            AdjustmentType.GRADIENT_MAP -> gradientMap(src, out, w, h, gradient ?: GradientPresets.all[0], p("amount") / 100f)
            AdjustmentType.RECOLOR -> recolor(src, out, w, h, (p("tx") * w).toInt(), (p("ty") * h).toInt(), p("flood") / 100f, color)
            AdjustmentType.BRIGHTNESS_CONTRAST -> brightnessContrast(src, out, w, h, p("bri") / 100f, p("con") / 100f)
            AdjustmentType.POSTERIZE -> posterize(src, out, w, h, i("levels"))
            AdjustmentType.INVERT -> invert(src, out, w, h, p("amount") / 100f)
            AdjustmentType.GAUSSIAN_BLUR -> gaussian(src, out, w, h, p("radius") / 100f * 120f * ds)
            AdjustmentType.MOTION_BLUR -> motionBlur(src, out, w, h, p("dist") / 100f * 220f * ds, p("angle"))
            AdjustmentType.PERSPECTIVE_BLUR -> perspectiveBlur(src, out, w, h, p("amount") / 100f, i("mode") == 1, p("fx") * w, p("fy") * h, p("angle"))
            AdjustmentType.SHARPEN -> sharpen(src, out, w, h, p("amount") / 100f, 1.2f * docScale.coerceIn(0.6f, 3f))
            AdjustmentType.NOISE -> noise(src, out, w, h, p("amount") / 100f, i("style"), (6f + p("scale") / 100f * 320f) * ds,
                i("octaves").coerceIn(1, 8), p("turb") / 100f, i("multi") == 1, p("additive") >= 0.5f)
            AdjustmentType.BLOOM -> bloom(src, out, w, h, p("amount") / 100f, p("threshold") / 100f, p("size") / 100f * 80f * ds, p("burn") / 100f)
            AdjustmentType.GLITCH -> glitch(src, out, w, h, p("amount") / 100f, i("mode"), (p("block") / 100f * 120f * ds).coerceAtLeast(2f),
                p("amp") / 100f, p("freq") / 100f, p("rs") / 100f, p("gs") / 100f, p("bs") / 100f, 0.25f + p("zoom") / 100f * 3.75f, i("seed"))
            AdjustmentType.HALFTONE -> halftone(src, out, w, h, (p("size") / 100f * 60f * ds).coerceAtLeast(3f), i("style"))
            AdjustmentType.CHROMATIC -> if (i("mode") == 0)
                chromatic(src, out, w, h, p("amount") / 100f * 40f * ds, p("fx") * w, p("fy") * h, p("transition") / 100f, p("falloff") / 100f)
            else chromaticDisplace(src, out, w, h, p("dx") * w * (p("amount") / 100f) * 2f, p("dy") * h * (p("amount") / 100f) * 2f,
                p("blur") / 100f * 30f * ds, p("transparency") / 100f)
            AdjustmentType.PIXELATE -> pixelate(src, out, w, h, max(1, (p("size") * ds).roundToInt()))
            AdjustmentType.LIQUIFY, AdjustmentType.CLONE -> src.copyInto(out)
        }
        return out
    }

    /** Mix [result] back towards [src] outside the selection mask (bytes 0..255, row-major w*h). */
    fun maskBlend(src: IntArray, result: IntArray, mask: ByteArray?) {
        mask ?: return
        for (i in src.indices) {
            val m = mask[i].toInt() and 0xFF
            if (m == 255) continue
            if (m == 0) { result[i] = src[i]; continue }
            val t = m / 255f
            val s = src[i]; val o = result[i]
            result[i] = argb(
                (a(s) + (a(o) - a(s)) * t).toInt(), (r(s) + (r(o) - r(s)) * t).toInt(),
                (g(s) + (g(o) - g(s)) * t).toInt(), (b(s) + (b(o) - b(s)) * t).toInt(),
            )
        }
    }

    private fun hsb(src: IntArray, out: IntArray, w: Int, h: Int, hue: Float, sat: Float, bri: Float) = parallelRows(h) { y0, y1 ->
        val hsv = FloatArray(3)
        for (i in y0 * w until y1 * w) {
            val c = src[i]
            if (a(c) == 0) { out[i] = c; continue }
            Color.RGBToHSV(r(c), g(c), b(c), hsv)
            hsv[0] = ((hsv[0] + hue) % 360f + 360f) % 360f
            hsv[1] = if (sat >= 0) hsv[1] + (1 - hsv[1]) * sat * hsv[1].coerceAtLeast(0.15f) else hsv[1] * (1 + sat)
            hsv[1] = hsv[1].coerceIn(0f, 1f)
            hsv[2] = if (bri >= 0) hsv[2] + (1 - hsv[2]) * bri else hsv[2] * (1 + bri)
            out[i] = (Color.HSVToColor(hsv) and 0xFFFFFF) or (c and 0xFF000000.toInt())
        }
    }

    private fun colorBalance(src: IntArray, out: IntArray, w: Int, h: Int, cb: ColorBalanceSet) = parallelRows(h) { y0, y1 ->
        for (i in y0 * w until y1 * w) {
            val c = src[i]
            if (a(c) == 0) { out[i] = c; continue }
            var rr = r(c) / 255f; var gg = g(c) / 255f; var bb = b(c) / 255f
            val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
            // tonal weights (GIMP-like)
            val ws = (1f - (l / 0.33f)).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }.coerceAtLeast(((0.5f - l) * 2f).coerceIn(0f, 1f) * 0.5f)
            val wh = ((l - 0.66f) / 0.34f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }.coerceAtLeast(((l - 0.5f) * 2f).coerceIn(0f, 1f) * 0.5f)
            val wm = (1f - abs(l - 0.5f) * 2f).coerceIn(0f, 1f)
            val weights = floatArrayOf(ws, wm, wh)
            for (t in 0..2) {
                val k = weights[t] * 0.4f
                rr += cb.v[t][0] / 100f * k
                gg += cb.v[t][1] / 100f * k
                bb += cb.v[t][2] / 100f * k
            }
            if (cb.preserveLuminosity) {
                val nl = 0.299f * rr + 0.587f * gg + 0.114f * bb
                val d = l - nl
                rr += d; gg += d; bb += d
            }
            out[i] = argb(a(c), (rr * 255).toInt(), (gg * 255).toInt(), (bb * 255).toInt())
        }
    }

    private fun curves(src: IntArray, out: IntArray, w: Int, h: Int, cs: CurveSet) {
        val m = cs.lut(cs.master); val lr = cs.lut(cs.red); val lg = cs.lut(cs.green); val lb = cs.lut(cs.blue)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                out[i] = argb(a(c), lr[m[r(c)]], lg[m[g(c)]], lb[m[b(c)]])
            }
        }
    }

    fun gradientLut(gp: GradientPreset): IntArray {
        val lut = IntArray(256)
        for (v in 0..255) {
            val t = v / 255f
            var k = 0
            while (k < gp.stops.size - 2 && t > gp.stops[k + 1]) k++
            val s0 = gp.stops[k]; val s1 = gp.stops[min(k + 1, gp.stops.size - 1)]
            val f = if (s1 > s0) ((t - s0) / (s1 - s0)).coerceIn(0f, 1f) else 0f
            val c0 = gp.colors[k]; val c1 = gp.colors[min(k + 1, gp.colors.size - 1)]
            lut[v] = argb(255, (r(c0) + (r(c1) - r(c0)) * f).toInt(), (g(c0) + (g(c1) - g(c0)) * f).toInt(), (b(c0) + (b(c1) - b(c0)) * f).toInt())
        }
        return lut
    }

    private fun gradientMap(src: IntArray, out: IntArray, w: Int, h: Int, gp: GradientPreset, amount: Float) {
        val lut = gradientLut(gp)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                val l = ((0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c))).toInt().coerceIn(0, 255)
                val m = lut[l]
                out[i] = argb(a(c), (r(c) + (r(m) - r(c)) * amount).toInt(), (g(c) + (g(m) - g(c)) * amount).toInt(), (b(c) + (b(m) - b(c)) * amount).toInt())
            }
        }
    }

    private fun brightnessContrast(src: IntArray, out: IntArray, w: Int, h: Int, bri: Float, con: Float) {
        val k = if (con >= 0) 1f + con * 3f else 1f + con
        val lut = IntArray(256) { v -> ((((v / 255f) + bri * 0.5f - 0.5f) * k + 0.5f) * 255f).roundToInt().coerceIn(0, 255) }
        parallelRows(h) { y0, y1 -> for (i in y0 * w until y1 * w) { val c = src[i]; out[i] = argb(a(c), lut[r(c)], lut[g(c)], lut[b(c)]) } }
    }

    private fun posterize(src: IntArray, out: IntArray, w: Int, h: Int, levels: Int) {
        val n = levels.coerceIn(2, 64) - 1
        val lut = IntArray(256) { v -> ((v / 255f * n).roundToInt() * 255f / n).roundToInt() }
        parallelRows(h) { y0, y1 -> for (i in y0 * w until y1 * w) { val c = src[i]; out[i] = argb(a(c), lut[r(c)], lut[g(c)], lut[b(c)]) } }
    }

    private fun invert(src: IntArray, out: IntArray, w: Int, h: Int, amt: Float) = parallelRows(h) { y0, y1 ->
        for (i in y0 * w until y1 * w) {
            val c = src[i]
            out[i] = argb(a(c), (r(c) + (255 - 2 * r(c)) * amt).toInt(), (g(c) + (255 - 2 * g(c)) * amt).toInt(), (b(c) + (255 - 2 * b(c)) * amt).toInt())
        }
    }

    // ---------- blur core (premultiplied, 3-pass box ≈ gaussian) ----------

    /** Runs [block] over 0 until [n] in parallel slices. */
    private inline fun parallelRange(n: Int, crossinline block: (Int, Int) -> Unit) {
        val slice = 4096
        parallelRows((n + slice - 1) / slice) { r0, r1 -> block(r0 * slice, min(n, r1 * slice)) }
    }

    /** Premultiplied, packed ARGB: one memory read per tap for the many-tap blurs. */
    private fun premulPacked(src: IntArray): IntArray {
        val pm = IntArray(src.size)
        parallelRange(src.size) { i0, i1 ->
            for (i in i0 until i1) {
                val c = src[i]; val al = a(c)
                pm[i] = if (al == 255) c else if (al == 0) 0 else
                    (al shl 24) or ((r(c) * al + 127) / 255 shl 16) or ((g(c) * al + 127) / 255 shl 8) or ((b(c) * al + 127) / 255)
            }
        }
        return pm
    }

    /** Averages of premultiplied sums back to straight ARGB. */
    private fun unpremul(sa: Int, sr: Int, sg: Int, sb: Int, n: Int): Int {
        val al = (sa + n / 2) / n
        if (al <= 0) return 0
        val d = sa.toFloat() / 255f   // = n · alpha
        return argb(al, (sr / d + 0.5f).toInt(), (sg / d + 0.5f).toInt(), (sb / d + 0.5f).toInt())
    }

    private fun toPremulPlanes(src: IntArray): Array<FloatArray> {
        val n = src.size
        val pa = FloatArray(n); val pr = FloatArray(n); val pg = FloatArray(n); val pb = FloatArray(n)
        parallelRange(n) { i0, i1 ->
            for (i in i0 until i1) {
                val c = src[i]; val al = a(c) / 255f
                pa[i] = al; pr[i] = r(c) * al; pg[i] = g(c) * al; pb[i] = b(c) * al
            }
        }
        return arrayOf(pa, pr, pg, pb)
    }

    private fun fromPremulPlanes(pl: Array<FloatArray>, out: IntArray) {
        val (pa, pr, pg, pb) = pl
        parallelRange(out.size) { i0, i1 ->
            for (i in i0 until i1) {
                val al = pa[i]
                if (al <= 1f / 512f) { out[i] = 0; continue }
                out[i] = argb((al * 255f + 0.5f).toInt(), (pr[i] / al + 0.5f).toInt(), (pg[i] / al + 0.5f).toInt(), (pb[i] / al + 0.5f).toInt())
            }
        }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) = parallelRows(h) { y0, y1 ->
        val inv = 1f / (2 * r + 1)
        for (y in y0 until y1) {
            val o = y * w
            var acc = 0f
            for (k in -r..r) acc += src[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[o + x] = acc * inv
                acc += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        // Column bands, walked row by row so memory is read sequentially (a column walk thrashes the cache).
        val bands = threads * 2
        val band = (w + bands - 1) / bands
        val inv = 1f / (2 * r + 1)
        parallelRows(bands, minChunk = 1) { b0, b1 ->
            for (bI in b0 until b1) {
                val x0 = bI * band; val x1 = min(w, x0 + band)
                if (x0 >= x1) continue
                val bw = x1 - x0
                val acc = FloatArray(bw)
                for (k in -r..r) { val o = k.coerceIn(0, h - 1) * w + x0; for (j in 0 until bw) acc[j] += src[o + j] }
                for (y in 0 until h) {
                    val o = y * w + x0
                    val add = (y + r + 1).coerceAtMost(h - 1) * w + x0
                    val sub = (y - r).coerceAtLeast(0) * w + x0
                    for (j in 0 until bw) {
                        dst[o + j] = acc[j] * inv
                        acc[j] += src[add + j] - src[sub + j]
                    }
                }
            }
        }
    }

    fun blurPlanes(pl: Array<FloatArray>, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.4f) return
        // box sizes for 3 passes (Kovesi)
        val n = 3
        val wIdeal = sqrt(12 * sigma * sigma / n + 1)
        var wl = floor(wIdeal).toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)
        val m = mIdeal.roundToInt()
        val tmp = FloatArray(w * h)
        for (p in pl) {
            for (i in 0 until n) {
                val r = ((if (i < m) wl else wu) - 1) / 2
                if (r <= 0) continue
                boxH(p, tmp, w, h, r)
                boxV(tmp, p, w, h, r)
            }
        }
    }

    fun gaussian(src: IntArray, out: IntArray, w: Int, h: Int, radius: Float) {
        if (radius < 0.5f) { src.copyInto(out); return }
        val pl = toPremulPlanes(src)
        blurPlanes(pl, w, h, radius / 2f)
        fromPremulPlanes(pl, out)
    }

    private fun motionBlur(src: IntArray, out: IntArray, w: Int, h: Int, dist: Float, angleDeg: Float) {
        if (dist < 1f) { src.copyInto(out); return }
        val pm = premulPacked(src)
        val taps = dist.toInt().coerceIn(3, 48)
        val dx = cos(Math.toRadians(angleDeg.toDouble())).toFloat()
        val dy = sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        val offs = IntArray(taps * 2)
        for (t in 0 until taps) {
            val f = (t / (taps - 1f) - 0.5f) * dist
            offs[t * 2] = (dx * f).roundToInt(); offs[t * 2 + 1] = (dy * f).roundToInt()
        }
        val wMax = w - 1; val hMax = h - 1
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                for (t in 0 until taps) {
                    var xx = x + offs[t * 2]; if (xx < 0) xx = 0 else if (xx > wMax) xx = wMax
                    var yy = y + offs[t * 2 + 1]; if (yy < 0) yy = 0 else if (yy > hMax) yy = hMax
                    val c = pm[yy * w + xx]
                    sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                out[y * w + x] = unpremul(sa, sr, sg, sb, taps)
            }
        }
    }

    private fun sharpen(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, radius: Float) {
        val blurred = IntArray(src.size)
        gaussian(src, blurred, w, h, radius * 2f)
        val k = amount * 2.5f
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]; val bl = blurred[i]
                out[i] = argb(a(c), (r(c) + (r(c) - r(bl)) * k).toInt(), (g(c) + (g(c) - g(bl)) * k).toInt(), (b(c) + (b(c) - b(bl)) * k).toInt())
            }
        }
    }

    // ---------- value noise (fractal) ----------

    private fun hash2(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 144269504
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun vnoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
        val a = hash2(x0, y0, seed); val b = hash2(x0 + 1, y0, seed)
        val c = hash2(x0, y0 + 1, seed); val d = hash2(x0 + 1, y0 + 1, seed)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    /** Fractal noise in 0..1. style 0 = clouds (fBm), 1 = billows, 2 = ridges. */
    private fun fractal(x: Float, y: Float, seed: Int, style: Int, octaves: Int, turbulence: Float): Float {
        var px = x; var py = y
        if (turbulence > 0f) {
            px += (vnoise(x * 0.7f + 17f, y * 0.7f, seed + 91) - 0.5f) * 4f * turbulence
            py += (vnoise(x * 0.7f, y * 0.7f + 31f, seed + 57) - 0.5f) * 4f * turbulence
        }
        var sum = 0f; var amp = 1f; var norm = 0f; var f = 1f
        for (o in 0 until octaves) {
            val n = vnoise(px * f, py * f, seed + o * 1013)
            sum += amp * when (style) { 1 -> abs(n * 2f - 1f); 2 -> 1f - abs(n * 2f - 1f); else -> n }
            norm += amp; amp *= 0.5f; f *= 2f
        }
        return (sum / norm).coerceIn(0f, 1f)
    }

    private fun noise(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, style: Int, cell: Float, octaves: Int, turbulence: Float, multi: Boolean, additive: Boolean) =
        run {
            // The field is smooth below its finest octave, so sample it on a grid at half that detail and interpolate.
            val step = max(1, (cell / (1 shl octaves) / 2f).toInt())
            val gw = w / step + 2; val gh = h / step + 2
            val inv = 1f / cell
            val seeds = if (multi) intArrayOf(11, 23, 37) else intArrayOf(11)
            val grids = Array(seeds.size) { FloatArray(gw * gh) }
            parallelRows(gh, minChunk = 1) { g0, g1 ->
                for (gy in g0 until g1) for (gx in 0 until gw) for (k in seeds.indices)
                    grids[k][gy * gw + gx] = fractal(gx * step * inv, gy * step * inv, seeds[k], style, octaves, turbulence)
            }
            fun field(k: Int, x: Int, y: Int): Float {
                if (step == 1) return grids[k][y * gw + x]
                val gx = x / step; val gy = y / step
                val tx = (x - gx * step).toFloat() / step; val ty = (y - gy * step).toFloat() / step
                val g = grids[k]; val o = gy * gw + gx
                val top = g[o] + (g[o + 1] - g[o]) * tx
                val bot = g[o + gw] + (g[o + gw + 1] - g[o + gw]) * tx
                return top + (bot - top) * ty
            }
            noiseApply(src, out, w, h, amount, additive, multi) { k, x, y -> field(k, x, y) }
        }

    private inline fun noiseApply(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, additive: Boolean, multi: Boolean,
                                  crossinline field: (Int, Int, Int) -> Float) =
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = src[i]
                val nr = field(0, x, y)
                val ng = if (multi) field(1, x, y) else nr
                val nb = if (multi) field(2, x, y) else nr
                if (additive) {
                    if (a(c) == 0) { out[i] = c; continue }
                    val k = amount * 255f
                    out[i] = argb(a(c), (r(c) + (nr - 0.5f) * k).toInt(), (g(c) + (ng - 0.5f) * k).toInt(), (b(c) + (nb - 0.5f) * k).toInt())
                } else {
                    // opaque noise mixed over the image; can cover it completely at 100 %
                    val oa = (a(c) + (255 - a(c)) * amount).toInt()
                    out[i] = argb(oa, (r(c) + (nr * 255f - r(c)) * amount).toInt(), (g(c) + (ng * 255f - g(c)) * amount).toInt(), (b(c) + (nb * 255f - b(c)) * amount).toInt())
                }
            }
        }

    private fun bloom(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, threshold: Float, size: Float, burn: Float) {
        if (amount <= 0f) { src.copyInto(out); return }
        val bright = IntArray(src.size)
        val th = threshold * 255f
        for (i in src.indices) {
            val c = src[i]
            val l = 0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)
            val k = ((l - th) / (255f - th + 1f)).coerceIn(0f, 1f)
            bright[i] = if (k <= 0f) 0 else argb((a(c) * k).toInt(), r(c), g(c), b(c))
        }
        val bl = IntArray(src.size)
        gaussian(bright, bl, w, h, size.coerceAtLeast(1f))
        val gain = amount * (0.5f + burn * 2.5f)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]; val g2 = bl[i]
                val ga = a(g2) / 255f * gain
                fun scr(x: Int, y: Int): Int { val yy = (y * ga).coerceAtMost(255f); return (255f - (255f - x) * (255f - yy) / 255f).toInt() }
                val outA = max(a(c), (a(g2) * min(1f, gain)).toInt().coerceAtMost(255))
                if (a(c) == 0) { out[i] = argb(outA, r(g2), g(g2), b(g2)); continue }
                out[i] = argb(outA, scr(r(c), r(g2)), scr(g(c), g(g2)), scr(b(c), b(g2)))
            }
        }
    }

    private fun sample(src: IntArray, w: Int, h: Int, x: Int, y: Int) = src[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    private fun glitch(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, mode: Int, block0: Float, amp: Float, freq: Float,
                       rs: Float, gs: Float, bs: Float, zoom: Float, seed: Int) {
        src.copyInto(out)
        if (amount <= 0f) return
        val rnd = Random(seed * 977 + 3)
        val block = (block0 * zoom).coerceAtLeast(2f)
        when (mode) {
            1 -> { // wave: rows and columns ripple
                if (amp <= 0f || freq <= 0f) return
                val a1 = amp * amount * w * 0.08f
                val period = (h * (1.05f - freq) * 0.5f / zoom).coerceAtLeast(6f)
                parallelRows(h) { y0, y1 ->
                    for (y in y0 until y1) {
                        val ox = (a1 * sin(2f * PI.toFloat() * y / period)).roundToInt()
                        for (x in 0 until w) {
                            val oy = (a1 * 0.35f * sin(2f * PI.toFloat() * x / (period * 1.7f))).roundToInt()
                            out[y * w + x] = sample(src, w, h, x - ox, y - oy)
                        }
                    }
                }
            }
            2 -> { // signal: scan lines, dropped rows and channel slips
                val lineH = (block / 6f).roundToInt().coerceAtLeast(1)
                var y = 0
                while (y < h) {
                    val hit = rnd.nextFloat() < amount * 0.55f
                    val shift = if (hit) rnd.nextInt(-(w * 0.06f * amount).toInt() - 1, (w * 0.06f * amount).toInt() + 2) else 0
                    val dim = if ((y / lineH) % 2 == 0) 1f - amount * 0.25f else 1f
                    for (yy in y until min(h, y + lineH)) for (x in 0 until w) {
                        val s = sample(src, w, h, x - shift, yy); val o = src[yy * w + x]
                        val base = if (hit) argb(max(a(s), a(o)), r(s), g(o), b(sample(src, w, h, x + shift / 2, yy))) else o
                        out[yy * w + x] = argb(a(base), (r(base) * dim).toInt(), (g(base) * dim).toInt(), (b(base) * dim).toInt())
                    }
                    y += lineH
                }
            }
            3 -> { // diverge: independent R/G/B offsets
                val k = amount * w * 0.03f * zoom
                val rx = (rs * k).roundToInt(); val ry = (rs * k).roundToInt()
                val gx = (gs * k).roundToInt(); val gy = (-gs * k).roundToInt()
                val by = (bs * k).roundToInt()
                parallelRows(h) { y0, y1 ->
                    for (y in y0 until y1) for (x in 0 until w) {
                        val cr = sample(src, w, h, x - rx, y - ry); val cg = sample(src, w, h, x - gx, y - gy); val cb = sample(src, w, h, x, y - by)
                        out[y * w + x] = argb(max(a(cr), max(a(cg), a(cb))), r(cr), g(cg), b(cb))
                    }
                }
            }
            else -> { // artifact: displaced blocks
                val bands = (h / block).toInt().coerceAtLeast(1)
                val maxShift = (w * 0.12f * amount).toInt().coerceAtLeast(1)
                for (bI in 0 until bands) {
                    if (rnd.nextFloat() > amount * 0.8f) continue
                    val y0 = (bI * block).toInt(); val bh = (block * (0.3f + rnd.nextFloat())).toInt().coerceAtLeast(1)
                    val bx0 = rnd.nextInt(0, max(1, w - 1)); val bw = (block * (2f + rnd.nextFloat() * 10f)).toInt().coerceAtLeast(2)
                    val shift = rnd.nextInt(-maxShift, maxShift + 1)
                    val channelMode = rnd.nextInt(3)
                    for (y in y0 until min(h, y0 + bh)) for (x in max(0, bx0 - bw) until min(w, bx0 + bw)) {
                        val s = sample(src, w, h, x - shift, y); val o = src[y * w + x]
                        out[y * w + x] = when (channelMode) {
                            0 -> s
                            1 -> argb(max(a(s), a(o)), r(s), g(o), b(o))
                            else -> argb(max(a(s), a(o)), r(o), g(o), b(s))
                        }
                    }
                }
            }
        }
    }

    /** style 0 = full colour (dots over a lightened original), 1 = screen print (colour dots on white), 2 = newspaper (black dots). */
    private fun halftone(src: IntArray, out: IntArray, w: Int, h: Int, cell: Float, style: Int) {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cs = cell.toInt().coerceAtLeast(2)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (yy in y until min(h, y + cs) step 2) for (xx in x until min(w, x + cs) step 2) {
                    val c = src[yy * w + xx]; val al = a(c)
                    sa += al; sr += r(c) * al; sg += g(c) * al; sb += b(c) * al; n++
                }
                if (sa > 0) {
                    val ar = (sr / sa).toInt(); val ag = (sg / sa).toInt(); val ab = (sb / sa).toInt()
                    val avgA = (sa / n).toInt()
                    val l = (0.299f * ar + 0.587f * ag + 0.114f * ab) / 255f
                    val rad = cs * 0.5f * 1.414f * sqrt(1f - l).coerceIn(0f, 1f)
                    val l2 = x.toFloat(); val t2 = y.toFloat(); val r2 = (x + cs).toFloat(); val b2 = (y + cs).toFloat()
                    when (style) {
                        2 -> {
                            cv.drawRect(l2, t2, r2, b2, paint.apply { color = Color.argb(avgA, 255, 255, 255) })
                            paint.color = Color.argb(avgA, 0, 0, 0)
                            cv.drawCircle(x + cs / 2f, y + cs / 2f, rad, paint)
                        }
                        1 -> {
                            cv.drawRect(l2, t2, r2, b2, paint.apply { color = Color.argb(avgA, 255, 255, 255) })
                            paint.color = Color.argb(avgA, ar, ag, ab)
                            cv.drawCircle(x + cs / 2f, y + cs / 2f, cs * 0.5f * 1.414f * (0.3f + 0.7f * sqrt(1f - l * 0.85f)), paint)
                        }
                        else -> {
                            cv.drawRect(l2, t2, r2, b2, paint.apply { color = Color.argb(avgA, (ar + 255) / 2, (ag + 255) / 2, (ab + 255) / 2) })
                            paint.color = Color.argb(avgA, (ar * 0.8f).toInt(), (ag * 0.8f).toInt(), (ab * 0.8f).toInt())
                            cv.drawCircle(x + cs / 2f, y + cs / 2f, cs * 0.5f * 1.414f * (0.35f + 0.65f * sqrt(1f - l * 0.85f)), paint)
                        }
                    }
                }
                x += cs
            }
            y += cs
        }
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycle()
    }

    /** Perspective: red/blue separate outward from a focal point; clear zone set by falloff, edge by transition. */
    private fun chromatic(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, cx: Float, cy: Float, transition: Float, falloff: Float) {
        val maxR = max(hypot(cx, cy), max(hypot(w - cx, cy), max(hypot(cx, h - cy), hypot(w - cx, h - cy))))
        val start = falloff * 0.6f
        val span = (0.05f + (1f - transition) * 0.9f)
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val dist = hypot(dx, dy)
                val d = dist / maxR
                val t = ((d - start) / span).coerceIn(0f, 1f)
                val k = amount * t * t * (3 - 2 * t) * (0.4f + d)
                val nx = if (dist > 0) dx / dist else 0f; val ny = if (dist > 0) dy / dist else 0f
                val cr = sample(src, w, h, (x + nx * k).roundToInt(), (y + ny * k).roundToInt())
                val cb = sample(src, w, h, (x - nx * k).roundToInt(), (y - ny * k).roundToInt())
                val c = src[y * w + x]
                out[y * w + x] = argb(max(a(c), max(a(cr), a(cb))), r(cr), g(c), b(cb))
            }
        }
    }

    /** Displace: red shifted by (dx,dy) and blue the opposite way, optionally blurred and faded. */
    private fun chromaticDisplace(src: IntArray, out: IntArray, w: Int, h: Int, dx: Float, dy: Float, blur: Float, transparency: Float) {
        val ox = dx.roundToInt(); val oy = dy.roundToInt()
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val c = src[y * w + x]
                val cr = sample(src, w, h, x - ox, y - oy); val cb = sample(src, w, h, x + ox, y + oy)
                out[y * w + x] = argb(max(a(c), max(a(cr), a(cb))), r(cr), g(c), b(cb))
            }
        }
        if (blur >= 1f) { val tmp = out.copyOf(); gaussian(tmp, out, w, h, blur) }
        if (transparency > 0f) for (i in out.indices) {
            val s = src[i]; val o = out[i]; val t = transparency
            out[i] = argb((a(o) + (a(s) - a(o)) * t).toInt(), (r(o) + (r(s) - r(o)) * t).toInt(), (g(o) + (g(s) - g(o)) * t).toInt(), (b(o) + (b(s) - b(o)) * t).toInt())
        }
    }

    /** Positional = zoom blur towards the focal point; directional = streaks growing along [angleDeg] on one side. */
    private fun perspectiveBlur(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, directional: Boolean, fx: Float, fy: Float, angleDeg: Float) {
        if (amount <= 0f) { src.copyInto(out); return }
        val pm = premulPacked(src)
        val taps = 20
        val ux = cos(Math.toRadians(angleDeg.toDouble())).toFloat(); val uy = sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        val k = amount * 0.35f
        val off = if (directional) 0.5f else 0f
        val wMax = w - 1; val hMax = h - 1
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                var vx: Float; var vy: Float
                if (directional) {
                    val s = (x - fx) * ux + (y - fy) * uy
                    if (s <= 0f) { out[i] = src[i]; continue }
                    vx = ux * s * k; vy = uy * s * k
                } else { vx = (fx - x) * k; vy = (fy - y) * k }
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                for (t in 0 until taps) {
                    val f = t / (taps - 1f) - off
                    var xx = (x + vx * f + 0.5f).toInt(); if (xx < 0) xx = 0 else if (xx > wMax) xx = wMax
                    var yy = (y + vy * f + 0.5f).toInt(); if (yy < 0) yy = 0 else if (yy > hMax) yy = hMax
                    val c = pm[yy * w + xx]
                    sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                out[i] = unpremul(sa, sr, sg, sb, taps)
            }
        }
    }

    /**
     * Recolor: the contiguous area around (tx,ty) whose colour is within [flood] of it takes [color],
     * keeping each pixel's shading (brightness offset from the picked colour) and alpha.
     */
    private fun recolor(src: IntArray, out: IntArray, w: Int, h: Int, tx: Int, ty: Int, flood: Float, color: Int) {
        src.copyInto(out)
        val reg = FloodFill.region(src, w, h, tx.coerceIn(0, w - 1), ty.coerceIn(0, h - 1), flood, fringe = 1) ?: return
        val seed = src[ty.coerceIn(0, h - 1) * w + tx.coerceIn(0, w - 1)]
        val ls = 0.299f * r(seed) + 0.587f * g(seed) + 0.114f * b(seed)
        val cr = r(color); val cg = g(color); val cb = b(color)
        for (y in reg.bounds.top until reg.bounds.bottom) for (x in reg.bounds.left until reg.bounds.right) {
            val i = y * w + x
            val m = reg.mask[i].toInt() and 0xFF
            if (m == 0) continue
            val c = src[i]
            val d = (0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)) - ls
            val nr = (cr + d).toInt(); val ng = (cg + d).toInt(); val nb = (cb + d).toInt()
            val t = m / 255f
            out[i] = argb(a(c), (r(c) + (nr - r(c)) * t).toInt(), (g(c) + (ng - g(c)) * t).toInt(), (b(c) + (nb - b(c)) * t).toInt())
        }
    }

    private fun pixelate(src: IntArray, out: IntArray, w: Int, h: Int, size: Int) {
        if (size <= 1) { src.copyInto(out); return }
        parallelRows((h + size - 1) / size) { b0, b1 ->
            for (by in b0 until b1) {
                val y0 = by * size; val y1 = min(h, y0 + size)
                var x0 = 0
                while (x0 < w) {
                    val x1 = min(w, x0 + size)
                    var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                    for (y in y0 until y1) for (x in x0 until x1) {
                        val c = src[y * w + x]; val al = a(c)
                        sa += al; sr += r(c) * al; sg += g(c) * al; sb += b(c) * al; n++
                    }
                    val col = if (sa == 0L) 0 else argb((sa / n).toInt(), (sr / sa).toInt(), (sg / sa).toInt(), (sb / sa).toInt())
                    for (y in y0 until y1) for (x in x0 until x1) out[y * w + x] = col
                    x0 = x1
                }
            }
        }
    }

    @Suppress("unused")
    private fun gaussianWeight(x: Float, s: Float) = exp(-x * x / (2 * s * s)) / (sqrt(2 * PI.toFloat()) * s)
}
