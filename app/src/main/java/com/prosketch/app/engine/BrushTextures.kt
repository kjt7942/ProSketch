package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A brush tip stored as a mip chain of ALPHA_8 bitmaps (index 0 = largest). */
class TipMips(val levels: List<Bitmap>) {
    val base: Bitmap get() = levels[0]

    /** Pick the smallest level that is still >= the target diameter, for clean minification. */
    fun forSize(diameter: Float): Bitmap {
        var pick = levels[0]
        for (b in levels) if (b.width >= diameter * 1.1f) pick = b
        return pick
    }
}

object BrushTextures {
    private const val TIP = 256
    private const val GRAIN = 512
    private val tipCache = HashMap<String, TipMips>()
    private val grainCache = HashMap<String, Bitmap>()
    private val maskCache = HashMap<String, ByteArray>()
    var customDir: File? = null

    @Synchronized
    fun tip(b: Brush): TipMips {
        val key = "${b.tip}|${(b.tipHardness * 40).toInt()}|${b.customTip}"
        return tipCache.getOrPut(key) { buildMips(makeTip(b.tip, b.tipHardness, b.customTip)) }
    }

    @Synchronized
    fun grain(b: Brush): Bitmap? {
        if (b.grain == GrainType.NONE) return null
        val key = "${b.grain}|${(b.grainDepth * 50).toInt()}|${(b.grainContrast * 50).toInt()}|${b.customGrain}"
        return grainCache.getOrPut(key) { makeGrain(b.grain, b.grainDepth, b.grainContrast, b.customGrain) }
    }

    fun grainShader(b: Brush): BitmapShader? {
        val g = grain(b) ?: return null
        return BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(b.grainScale, b.grainScale) })
        }
    }

    /** Tip coverage as a byte array of a given (square) size, used by the smudge engine. */
    @Synchronized
    fun tipMask(b: Brush, size: Int): ByteArray {
        val key = "${b.tip}|${(b.tipHardness * 40).toInt()}|${b.customTip}|$size"
        return maskCache.getOrPut(key) {
            if (maskCache.size > 64) maskCache.clear()
            val src = tip(b).forSize(size.toFloat())
            val scaled = Bitmap.createScaledBitmap(src, size, size, true)
            val buf = ByteArray(scaled.rowBytes * size)
            scaled.copyPixelsToBuffer(ByteBuffer.wrap(buf))
            if (scaled.rowBytes == size) buf else ByteArray(size * size).also { out ->
                for (y in 0 until size) System.arraycopy(buf, y * scaled.rowBytes, out, y * size, size)
            }
        }
    }

    private fun buildMips(base: Bitmap): TipMips {
        val list = ArrayList<Bitmap>()
        list.add(base)
        var s = base.width / 2
        var prev = base
        while (s >= 4) {
            val next = Bitmap.createScaledBitmap(prev, s, s, true)
            list.add(next)
            prev = next
            s /= 2
        }
        return TipMips(list)
    }

    private fun a8(size: Int, fill: (Int, Int) -> Float): Bitmap {
        val bytes = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            bytes[y * size + x] = (fill(x, y).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        return fromBytes(bytes, size)
    }

    fun fromBytes(bytes: ByteArray, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        if (bmp.rowBytes == size) {
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        } else {
            val padded = ByteArray(bmp.rowBytes * size)
            for (y in 0 until size) System.arraycopy(bytes, y * size, padded, y * bmp.rowBytes, size)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
        }
        return bmp
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Radial profile: hardness 1 = crisp anti-aliased disc, 0 = gaussian-like airbrush. */
    private fun radial(r: Float, hardness: Float): Float {
        if (r >= 1f) return 0f
        val edge = 1.5f / (TIP / 2f)
        if (hardness >= 0.99f) return 1f - smooth(1f - edge, 1f, r)
        val inner = hardness * 0.95f
        if (r <= inner) return 1f
        val t = (r - inner) / (1f - inner)
        val g = exp(-t * t * 4.5f)
        // force to zero at the rim
        return (g - exp(-4.5f)) / (1f - exp(-4.5f))
    }

    private fun makeTip(shape: TipShape, hardness: Float, custom: String?): Bitmap {
        val n = TIP
        val c = n / 2f
        val rnd = Random(shape.ordinal * 7919 + 13)
        return when (shape) {
            TipShape.ROUND -> a8(n) { x, y -> radial(hypot(x + 0.5f - c, y + 0.5f - c) / c, hardness) }
            TipShape.SQUARE -> a8(n) { x, y ->
                val d = max(kotlin.math.abs(x + 0.5f - c), kotlin.math.abs(y + 0.5f - c)) / c
                radial(d, max(hardness, 0.6f))
            }
            TipShape.FLAT -> a8(n) { x, y ->
                val d = max(kotlin.math.abs(x + 0.5f - c) / c, hypot(0f, (y + 0.5f - c) / c))
                radial(d, max(0.5f, hardness))
            }
            TipShape.PENCIL -> {
                val noise = ValueNoise(n, 32, 101)
                a8(n) { x, y ->
                    val r = hypot(x + 0.5f - c, y + 0.5f - c) / c
                    val base = radial(r, hardness.coerceAtMost(0.9f))
                    val grainy = 0.55f + 0.45f * noise.fbm(x.toFloat(), y.toFloat(), 3)
                    val speck = if (rnd.nextFloat() < 0.18f) 0.4f else 1f
                    base * grainy * speck
                }
            }
            TipShape.CHARCOAL -> {
                val noise = ValueNoise(n, 16, 202)
                a8(n) { x, y ->
                    val dx = x + 0.5f - c; val dy = y + 0.5f - c
                    val ang = kotlin.math.atan2(dy, dx)
                    val wobble = 0.82f + 0.18f * noise.sample((ang / (2 * PI).toFloat() + 0.5f) * n, 7f)
                    val r = hypot(dx, dy) / c / wobble
                    val inside = radial(r, max(hardness, 0.55f))
                    inside * (0.45f + 0.55f * noise.fbm(x.toFloat(), y.toFloat(), 4))
                }
            }
            TipShape.BRISTLE -> {
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                repeat(90) {
                    val a = rnd.nextFloat() * 2 * PI.toFloat()
                    val rr = sqrt(rnd.nextFloat()) * c * 0.82f
                    val x = c + cos(a) * rr; val y = c + sin(a) * rr
                    p.alpha = 120 + rnd.nextInt(135)
                    cv.drawCircle(x, y, n * (0.025f + rnd.nextFloat() * 0.05f), p)
                }
                softenEdge(bmp)
            }
            TipShape.SPLATTER -> {
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                cv.drawCircle(c, c, n * 0.2f, p)
                repeat(40) {
                    val a = rnd.nextFloat() * 2 * PI.toFloat()
                    val rr = (0.25f + rnd.nextFloat() * 0.7f) * c * 0.95f
                    val sz = n * (0.01f + rnd.nextFloat() * 0.06f) * (1.1f - rr / c)
                    cv.drawCircle(c + cos(a) * rr, c + sin(a) * rr, sz, p)
                }
                bmp
            }
            TipShape.SPRAY -> {
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                repeat(900) {
                    val a = rnd.nextFloat() * 2 * PI.toFloat()
                    val rr = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat()) / 3f * c * 0.98f
                    p.alpha = 90 + rnd.nextInt(165)
                    cv.drawCircle(c + cos(a) * rr, c + sin(a) * rr, n * (0.004f + rnd.nextFloat() * 0.008f), p)
                }
                bmp
            }
            TipShape.LEAF -> {
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val path = Path().apply {
                    moveTo(c, n * 0.04f)
                    cubicTo(n * 0.88f, n * 0.3f, n * 0.75f, n * 0.75f, c, n * 0.96f)
                    cubicTo(n * 0.25f, n * 0.75f, n * 0.12f, n * 0.3f, c, n * 0.04f)
                    close()
                }
                cv.drawPath(path, p)
                p.blendMode = android.graphics.BlendMode.DST_OUT
                p.strokeWidth = n * 0.015f
                p.style = Paint.Style.STROKE
                cv.drawLine(c, n * 0.1f, c, n * 0.92f, p)
                bmp
            }
            TipShape.STAR -> {
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val path = Path()
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4 - PI.toFloat() / 2
                    val r = if (i % 2 == 0) c * 0.98f else c * 0.12f
                    val x = c + cos(a) * r; val y = c + sin(a) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                cv.drawPath(path, p)
                val glow = a8(n) { x, y -> radial(hypot(x + 0.5f - c, y + 0.5f - c) / c, 0f) * 0.55f }
                cv.drawBitmap(glow, 0f, 0f, Paint().apply { blendMode = android.graphics.BlendMode.LIGHTEN })
                bmp
            }
            TipShape.CUSTOM -> loadCustom(custom, n) ?: makeTip(TipShape.ROUND, hardness, null)
        }
    }

    private fun softenEdge(bmp: Bitmap): Bitmap {
        val n = bmp.width; val c = n / 2f
        val mask = a8(n) { x, y -> 1f - smooth(0.8f, 1f, hypot(x + 0.5f - c, y + 0.5f - c) / c) }
        Canvas(bmp).drawBitmap(mask, 0f, 0f, Paint().apply { blendMode = android.graphics.BlendMode.DST_IN })
        return bmp
    }

    /** Custom image → alpha mask (dark = paint, light = transparent; transparent images use alpha). */
    private fun loadCustom(path: String?, n: Int): Bitmap? {
        path ?: return null
        val f = File(path)
        if (!f.exists()) return null
        val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val sq = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        sq.getPixels(px, 0, n, 0, 0, n, n)
        var anyTransparent = false
        for (p in px) if (Color.alpha(p) < 250) { anyTransparent = true; break }
        val bytes = ByteArray(n * n)
        for (i in px.indices) {
            val p = px[i]
            val v = if (anyTransparent) Color.alpha(p) / 255f
            else 1f - (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            bytes[i] = (v * 255).toInt().coerceIn(0, 255).toByte()
        }
        return fromBytes(bytes, n)
    }

    /** Grain textures are white with alpha = intensity, so they can modulate any colour via DST_IN. */
    private fun makeGrain(type: GrainType, depth: Float, contrast: Float, custom: String?): Bitmap {
        val n = GRAIN
        val values = FloatArray(n * n)
        val rnd = Random(type.ordinal * 31 + 5)
        when (type) {
            GrainType.PAPER -> {
                val nz = ValueNoise(n, 64, 11)
                for (y in 0 until n) for (x in 0 until n) values[y * n + x] = nz.fbm(x.toFloat(), y.toFloat(), 5)
            }
            GrainType.CANVAS -> {
                val nz = ValueNoise(n, 64, 12)
                val period = 8f
                for (y in 0 until n) for (x in 0 until n) {
                    val wx = 0.5f + 0.5f * sin(2 * PI.toFloat() * x / period)
                    val wy = 0.5f + 0.5f * sin(2 * PI.toFloat() * y / period)
                    val weave = if (((x / period).toInt() + (y / period).toInt()) % 2 == 0) wx else wy
                    values[y * n + x] = 0.6f * weave + 0.4f * nz.fbm(x.toFloat(), y.toFloat(), 3)
                }
            }
            GrainType.PENCIL -> {
                val nz = ValueNoise(n, 128, 13)
                for (y in 0 until n) for (x in 0 until n) {
                    val fine = rnd.nextFloat()
                    values[y * n + x] = 0.5f * fine + 0.5f * nz.fbm(x * 1.6f, y * 0.6f, 4)
                }
            }
            GrainType.SPONGE -> {
                val cells = 64
                val pts = Array(cells) { floatArrayOf(rnd.nextFloat() * n, rnd.nextFloat() * n) }
                for (y in 0 until n) for (x in 0 until n) {
                    var d1 = Float.MAX_VALUE
                    for (p in pts) {
                        var dx = kotlin.math.abs(x - p[0]); var dy = kotlin.math.abs(y - p[1])
                        if (dx > n / 2) dx = n - dx
                        if (dy > n / 2) dy = n - dy
                        val d = dx * dx + dy * dy
                        if (d < d1) d1 = d
                    }
                    values[y * n + x] = (sqrt(d1) / (n / 9f)).coerceIn(0f, 1f)
                }
            }
            GrainType.NOISE -> for (i in values.indices) values[i] = rnd.nextFloat()
            GrainType.WATERCOLOR -> {
                val nz = ValueNoise(n, 32, 14)
                for (y in 0 until n) for (x in 0 until n) {
                    val v = nz.fbm(x.toFloat(), y.toFloat(), 6)
                    values[y * n + x] = 0.35f + 0.65f * smooth(0.25f, 0.75f, v)
                }
            }
            GrainType.CHALK -> {
                val nz = ValueNoise(n, 96, 15)
                for (y in 0 until n) for (x in 0 until n) {
                    val v = nz.fbm(x.toFloat(), y.toFloat(), 4)
                    values[y * n + x] = if (rnd.nextFloat() < 0.12f) 0f else smooth(0.3f, 0.6f, v)
                }
            }
            GrainType.CUSTOM -> {
                val f = custom?.let { File(it) }
                val src = if (f != null && f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                if (src != null) {
                    val sq = Bitmap.createScaledBitmap(src, n, n, true)
                    val px = IntArray(n * n)
                    sq.getPixels(px, 0, n, 0, 0, n, n)
                    for (i in px.indices) {
                        val p = px[i]
                        values[i] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                    }
                } else values.fill(1f)
            }
            GrainType.NONE -> values.fill(1f)
        }
        // contrast & depth
        val k = 1f + contrast * 3f
        val px = IntArray(n * n)
        for (i in values.indices) {
            var v = ((values[i] - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
            v = 1f - depth * (1f - v)
            px[i] = Color.argb((v * 255).toInt(), 255, 255, 255)
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }
}

/** Tileable 2D value noise. */
class ValueNoise(private val size: Int, private val cell: Int, seed: Int) {
    private val g = cell.coerceAtLeast(2)
    private val grid: FloatArray
    private val gw = maxOf(1, size / g)

    init {
        val r = Random(seed)
        grid = FloatArray(gw * gw) { r.nextFloat() }
    }

    fun sample(x: Float, y: Float, scale: Float = 1f): Float {
        val fx = x / g * scale; val fy = y / g * scale
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
        fun v(ix: Int, iy: Int): Float = grid[(Math.floorMod(iy, gw)) * gw + Math.floorMod(ix, gw)]
        val a = v(x0, y0); val b = v(x0 + 1, y0); val c = v(x0, y0 + 1); val d = v(x0 + 1, y0 + 1)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    fun fbm(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (o in 0 until octaves) {
            sum += amp * sample(x, y, freq)
            norm += amp
            amp *= 0.5f; freq *= 2f
        }
        return min(1f, sum / norm)
    }
}
