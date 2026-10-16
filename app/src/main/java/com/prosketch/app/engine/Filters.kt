package com.prosketch.app.engine

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

data class FilterParam(val key: String, val label: String, val min: Float, val max: Float, val default: Float, val unit: String = "%")

enum class AdjustmentType(val label: String, val params: List<FilterParam>, val section: Int) {
    HSB("색조, 채도, 밝기", listOf(
        FilterParam("hue", "색조", -180f, 180f, 0f, "°"),
        FilterParam("sat", "채도", -100f, 100f, 0f),
        FilterParam("bri", "밝기", -100f, 100f, 0f)), 0),
    COLOR_BALANCE("색상 균형", listOf(
        FilterParam("cr", "청록 ↔ 빨강", -100f, 100f, 0f),
        FilterParam("mg", "자홍 ↔ 녹색", -100f, 100f, 0f),
        FilterParam("yb", "노랑 ↔ 파랑", -100f, 100f, 0f)), 0),
    CURVES("커브", emptyList(), 0),
    GRADIENT_MAP("그라디언트 맵", listOf(FilterParam("amount", "강도", 0f, 100f, 100f)), 0),
    BRIGHTNESS_CONTRAST("밝기/대비", listOf(
        FilterParam("bri", "밝기", -100f, 100f, 0f),
        FilterParam("con", "대비", -100f, 100f, 0f)), 0),
    POSTERIZE("포스터화", listOf(FilterParam("levels", "단계", 2f, 32f, 6f, "")), 0),
    INVERT("색상 반전", listOf(FilterParam("amount", "강도", 0f, 100f, 100f)), 0),
    GAUSSIAN_BLUR("가우시안 흐림 효과", listOf(FilterParam("radius", "흐림", 0f, 100f, 15f)), 1),
    MOTION_BLUR("움직임 흐림 효과", listOf(
        FilterParam("dist", "거리", 0f, 100f, 20f),
        FilterParam("angle", "각도", 0f, 360f, 0f, "°")), 1),
    SHARPEN("선명하게", listOf(FilterParam("amount", "선명도", 0f, 100f, 40f)), 2),
    NOISE("노이즈", listOf(
        FilterParam("amount", "양", 0f, 100f, 25f),
        FilterParam("mono", "흑백 노이즈", 0f, 1f, 1f, "")), 2),
    BLOOM("블룸", listOf(
        FilterParam("threshold", "전환", 0f, 100f, 55f),
        FilterParam("size", "크기", 0f, 100f, 35f),
        FilterParam("burn", "번", 0f, 100f, 50f)), 2),
    GLITCH("글리치", listOf(
        FilterParam("amount", "양", 0f, 100f, 35f),
        FilterParam("block", "블록 크기", 1f, 100f, 30f),
        FilterParam("seed", "변형", 0f, 100f, 1f, "")), 2),
    HALFTONE("하프톤", listOf(
        FilterParam("size", "크기", 1f, 100f, 25f),
        FilterParam("mono", "흑백", 0f, 1f, 0f, "")), 2),
    CHROMATIC("색수차", listOf(
        FilterParam("amount", "변위", 0f, 100f, 25f),
        FilterParam("falloff", "감쇠", 0f, 100f, 50f)), 2),
    PIXELATE("모자이크", listOf(FilterParam("size", "크기", 1f, 100f, 12f, "px")), 2),
    LIQUIFY("픽셀 유동화", emptyList(), 3);
}

data class GradientPreset(val name: String, val colors: IntArray, val stops: FloatArray)

object GradientPresets {
    val all = listOf(
        GradientPreset("느와르", intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), floatArrayOf(0f, 1f)),
        GradientPreset("석양", intArrayOf(0xFF1A0B3D.toInt(), 0xFFB0305C.toInt(), 0xFFF28B30.toInt(), 0xFFFFE9A8.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f)),
        GradientPreset("네온", intArrayOf(0xFF0B0221.toInt(), 0xFF6A00F4.toInt(), 0xFFFF2E97.toInt(), 0xFF00F5D4.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f)),
        GradientPreset("빈티지", intArrayOf(0xFF2B1B17.toInt(), 0xFF7A5C3E.toInt(), 0xFFD9B98C.toInt(), 0xFFF6EEDC.toInt()), floatArrayOf(0f, 0.35f, 0.75f, 1f)),
        GradientPreset("심해", intArrayOf(0xFF000814.toInt(), 0xFF003566.toInt(), 0xFF0096C7.toInt(), 0xFFCAF0F8.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset("화산", intArrayOf(0xFF0D0D0D.toInt(), 0xFF6A040F.toInt(), 0xFFE85D04.toInt(), 0xFFFFBA08.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset("민트", intArrayOf(0xFF081C15.toInt(), 0xFF2D6A4F.toInt(), 0xFF74C69D.toInt(), 0xFFD8F3DC.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f)),
        GradientPreset("세피아", intArrayOf(0xFF1C120C.toInt(), 0xFF8C6239.toInt(), 0xFFF5E6C8.toInt()), floatArrayOf(0f, 0.55f, 1f)),
        GradientPreset("듀오톤", intArrayOf(0xFF2E1760.toInt(), 0xFFFFC857.toInt()), floatArrayOf(0f, 1f)),
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

    fun parallelRows(h: Int, block: (Int, Int) -> Unit) {
        val chunk = max(16, (h + threads - 1) / threads)
        val tasks = ArrayList<Callable<Unit>>()
        var y = 0
        while (y < h) {
            val y0 = y; val y1 = min(h, y + chunk)
            tasks.add(Callable { block(y0, y1) })
            y = y1
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    inline fun a(c: Int) = c ushr 24
    inline fun r(c: Int) = (c shr 16) and 0xFF
    inline fun g(c: Int) = (c shr 8) and 0xFF
    inline fun b(c: Int) = c and 0xFF
    inline fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Generic entry. [scale] = document size relative to 1000 px, so pixel-sized params scale with the canvas. */
    fun apply(
        type: AdjustmentType, src: IntArray, w: Int, h: Int, params: Map<String, Float>,
        curves: CurveSet?, balance: ColorBalanceSet?, gradient: GradientPreset?,
    ): IntArray {
        val out = IntArray(src.size)
        val docScale = max(w, h) / 1000f
        fun p(k: String) = params[k] ?: type.params.firstOrNull { it.key == k }?.default ?: 0f
        when (type) {
            AdjustmentType.HSB -> hsb(src, out, w, h, p("hue"), p("sat") / 100f, p("bri") / 100f)
            AdjustmentType.COLOR_BALANCE -> colorBalance(src, out, w, h, balance ?: ColorBalanceSet())
            AdjustmentType.CURVES -> curves(src, out, w, h, curves ?: CurveSet())
            AdjustmentType.GRADIENT_MAP -> gradientMap(src, out, w, h, gradient ?: GradientPresets.all[0], p("amount") / 100f)
            AdjustmentType.BRIGHTNESS_CONTRAST -> brightnessContrast(src, out, w, h, p("bri") / 100f, p("con") / 100f)
            AdjustmentType.POSTERIZE -> posterize(src, out, w, h, p("levels").roundToInt())
            AdjustmentType.INVERT -> invert(src, out, w, h, p("amount") / 100f)
            AdjustmentType.GAUSSIAN_BLUR -> gaussian(src, out, w, h, p("radius") / 100f * 120f * docScale.coerceAtLeast(0.5f))
            AdjustmentType.MOTION_BLUR -> motionBlur(src, out, w, h, p("dist") / 100f * 220f * docScale.coerceAtLeast(0.5f), p("angle"))
            AdjustmentType.SHARPEN -> sharpen(src, out, w, h, p("amount") / 100f, 1.2f * docScale.coerceIn(0.6f, 3f))
            AdjustmentType.NOISE -> noise(src, out, w, h, p("amount") / 100f, p("mono") >= 0.5f)
            AdjustmentType.BLOOM -> bloom(src, out, w, h, p("threshold") / 100f, p("size") / 100f * 80f * docScale.coerceAtLeast(0.5f), p("burn") / 100f)
            AdjustmentType.GLITCH -> glitch(src, out, w, h, p("amount") / 100f, (p("block") / 100f * 120f * docScale.coerceAtLeast(0.5f)).coerceAtLeast(2f), p("seed").toInt())
            AdjustmentType.HALFTONE -> halftone(src, out, w, h, (p("size") / 100f * 60f * docScale.coerceAtLeast(0.5f)).coerceAtLeast(3f), p("mono") >= 0.5f)
            AdjustmentType.CHROMATIC -> chromatic(src, out, w, h, p("amount") / 100f * 40f * docScale.coerceAtLeast(0.5f), p("falloff") / 100f)
            AdjustmentType.PIXELATE -> pixelate(src, out, w, h, max(1, (p("size") * docScale.coerceAtLeast(0.5f)).roundToInt()))
            AdjustmentType.LIQUIFY -> src.copyInto(out)
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

    private fun toPremulPlanes(src: IntArray): Array<FloatArray> {
        val n = src.size
        val pa = FloatArray(n); val pr = FloatArray(n); val pg = FloatArray(n); val pb = FloatArray(n)
        for (i in 0 until n) {
            val c = src[i]; val al = a(c) / 255f
            pa[i] = al; pr[i] = r(c) * al; pg[i] = g(c) * al; pb[i] = b(c) * al
        }
        return arrayOf(pa, pr, pg, pb)
    }

    private fun fromPremulPlanes(pl: Array<FloatArray>, out: IntArray) {
        val (pa, pr, pg, pb) = pl
        for (i in out.indices) {
            val al = pa[i]
            if (al <= 1f / 512f) { out[i] = 0; continue }
            out[i] = argb((al * 255f + 0.5f).toInt(), (pr[i] / al + 0.5f).toInt(), (pg[i] / al + 0.5f).toInt(), (pb[i] / al + 0.5f).toInt())
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
        // process in column bands for parallelism
        val bands = threads
        val band = (w + bands - 1) / bands
        val tasks = (0 until bands).map { bI ->
            Callable {
                val x0 = bI * band; val x1 = min(w, x0 + band)
                val inv = 1f / (2 * r + 1)
                for (x in x0 until x1) {
                    var acc = 0f
                    for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) {
                        dst[y * w + x] = acc * inv
                        acc += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
                    }
                }
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
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
        val pl = toPremulPlanes(src)
        val taps = dist.toInt().coerceIn(3, 48)
        val dx = cos(Math.toRadians(angleDeg.toDouble())).toFloat()
        val dy = sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        val offs = IntArray(taps * 2)
        for (t in 0 until taps) {
            val f = (t / (taps - 1f) - 0.5f) * dist
            offs[t * 2] = (dx * f).roundToInt(); offs[t * 2 + 1] = (dy * f).roundToInt()
        }
        val res = Array(4) { FloatArray(w * h) }
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                for (t in 0 until taps) {
                    val xx = (x + offs[t * 2]).coerceIn(0, w - 1); val yy = (y + offs[t * 2 + 1]).coerceIn(0, h - 1)
                    val j = yy * w + xx
                    sa += pl[0][j]; sr += pl[1][j]; sg += pl[2][j]; sb += pl[3][j]
                }
                val i = y * w + x
                res[0][i] = sa / taps; res[1][i] = sr / taps; res[2][i] = sg / taps; res[3][i] = sb / taps
            }
        }
        fromPremulPlanes(res, out)
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

    private fun noise(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, mono: Boolean) = parallelRows(h) { y0, y1 ->
        val rnd = Random(y0 * 7 + 1)
        val k = amount * 160f
        for (i in y0 * w until y1 * w) {
            val c = src[i]
            if (a(c) == 0) { out[i] = c; continue }
            if (mono) {
                val n = ((rnd.nextFloat() - 0.5f) * k).toInt()
                out[i] = argb(a(c), r(c) + n, g(c) + n, b(c) + n)
            } else {
                out[i] = argb(a(c), r(c) + ((rnd.nextFloat() - 0.5f) * k).toInt(), g(c) + ((rnd.nextFloat() - 0.5f) * k).toInt(), b(c) + ((rnd.nextFloat() - 0.5f) * k).toInt())
            }
        }
    }

    private fun bloom(src: IntArray, out: IntArray, w: Int, h: Int, threshold: Float, size: Float, burn: Float) {
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
        val gain = 0.5f + burn * 2.5f
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]; val g2 = bl[i]
                val ga = a(g2) / 255f * gain
                // screen blend
                fun scr(x: Int, y: Int): Int { val yy = (y * ga).coerceAtMost(255f); return (255f - (255f - x) * (255f - yy) / 255f).toInt() }
                val outA = max(a(c), (a(g2) * min(1f, gain)).toInt().coerceAtMost(255))
                if (a(c) == 0) { out[i] = argb(outA, r(g2), g(g2), b(g2)); continue }
                out[i] = argb(outA, scr(r(c), r(g2)), scr(g(c), g(g2)), scr(b(c), b(g2)))
            }
        }
    }

    private fun glitch(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, block: Float, seed: Int) {
        src.copyInto(out)
        if (amount <= 0f) return
        val rnd = Random(seed * 977 + 3)
        val bands = (h / block).toInt().coerceAtLeast(1)
        val maxShift = (w * 0.12f * amount).toInt().coerceAtLeast(1)
        for (bI in 0 until bands) {
            if (rnd.nextFloat() > amount * 0.8f) continue
            val y0 = (bI * block).toInt(); val bh = (block * (0.3f + rnd.nextFloat())).toInt().coerceAtLeast(1)
            val shift = rnd.nextInt(-maxShift, maxShift + 1)
            val channelMode = rnd.nextInt(3)
            for (y in y0 until min(h, y0 + bh)) {
                for (x in 0 until w) {
                    val sx = (x - shift).coerceIn(0, w - 1)
                    val s = src[y * w + sx]; val o = src[y * w + x]
                    out[y * w + x] = when (channelMode) {
                        0 -> s
                        1 -> argb(max(a(s), a(o)), r(s), g(o), b(o))
                        else -> argb(max(a(s), a(o)), r(o), g(o), b(s))
                    }
                }
            }
        }
    }

    private fun halftone(src: IntArray, out: IntArray, w: Int, h: Int, cell: Float, mono: Boolean) {
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
                    if (mono) {
                        cv.drawRect(x.toFloat(), y.toFloat(), (x + cs).toFloat(), (y + cs).toFloat(), paint.apply { color = Color.argb(avgA, 255, 255, 255) })
                        paint.color = Color.argb(avgA, 0, 0, 0)
                        cv.drawCircle(x + cs / 2f, y + cs / 2f, rad, paint)
                    } else {
                        paint.color = Color.argb(avgA, ar, ag, ab)
                        cv.drawCircle(x + cs / 2f, y + cs / 2f, cs * 0.5f * 1.414f * (0.35f + 0.65f * sqrt(1f - l * 0.85f)), paint)
                    }
                }
                x += cs
            }
            y += cs
        }
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycle()
    }

    private fun chromatic(src: IntArray, out: IntArray, w: Int, h: Int, amount: Float, falloff: Float) {
        val cx = w / 2f; val cy = h / 2f
        val maxR = hypot(cx, cy)
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val d = hypot(dx, dy) / maxR
                val k = amount * d.pow(0.5f + falloff * 2f)
                val nx = if (d > 0) dx / (d * maxR) else 0f; val ny = if (d > 0) dy / (d * maxR) else 0f
                val rx = (x + nx * k).roundToInt().coerceIn(0, w - 1); val ry = (y + ny * k).roundToInt().coerceIn(0, h - 1)
                val bx = (x - nx * k).roundToInt().coerceIn(0, w - 1); val by = (y - ny * k).roundToInt().coerceIn(0, h - 1)
                val c = src[y * w + x]; val cr = src[ry * w + rx]; val cb = src[by * w + bx]
                out[y * w + x] = argb(max(a(c), max(a(cr), a(cb))), r(cr), g(c), b(cb))
            }
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
