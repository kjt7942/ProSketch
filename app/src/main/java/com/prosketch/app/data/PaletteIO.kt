package com.prosketch.app.data

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Palette files: Procreate .swatches (zip with Swatches.json, HSB 0..1), Adobe ASE and ACO,
 * plus palette extraction from an image (k-means in RGB).
 */
object PaletteIO {
    class Imported(val name: String, val colors: List<Int>)

    fun read(bytes: ByteArray, fileName: String): List<Imported> {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".swatches") || (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) -> readSwatches(bytes)
            lower.endsWith(".ase") || String(bytes, 0, min(4, bytes.size), Charsets.US_ASCII) == "ASEF" -> listOf(readAse(bytes, fileName))
            lower.endsWith(".aco") -> listOf(readAco(bytes, fileName))
            else -> error(L.t("지원하지 않는 팔레트 형식입니다 (.swatches, .ase, .aco)"))
        }.filter { it.colors.isNotEmpty() }
    }

    private fun readSwatches(bytes: ByteArray): List<Imported> {
        var json: String? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".json", ignoreCase = true)) { json = z.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val text = json ?: error(L.t("Swatches.json이 없습니다"))
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val sw = o.optJSONArray("swatches") ?: JSONArray()
            val colors = (0 until sw.length()).mapNotNull { k ->
                val s = sw.optJSONObject(k) ?: return@mapNotNull null
                Color.HSVToColor(floatArrayOf(
                    (s.optDouble("hue", 0.0) * 360.0).toFloat(), s.optDouble("saturation", 0.0).toFloat(), s.optDouble("brightness", 0.0).toFloat(),
                ))
            }
            Imported(o.optString("name", L.t("팔레트")), colors)
        }
    }

    private fun readAse(bytes: ByteArray, fileName: String): Imported {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.position(4); b.short; b.short
        val blocks = b.int
        val colors = ArrayList<Int>()
        var group: String? = null
        repeat(blocks) {
            if (b.remaining() < 6) return@repeat
            val type = b.short.toInt() and 0xFFFF
            val len = b.int
            val start = b.position()
            if (type == 0x0001 || type == 0xC001) {
                val n = b.short.toInt() and 0xFFFF
                val name = buildString { repeat(n) { val c = b.char; if (c != '\u0000') append(c) } }
                if (type == 0xC001) { if (group == null) group = name }
                else {
                    val model = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
                    val c = when (model) {
                        "RGB " -> Color.rgb((b.float * 255).toInt().coerceIn(0, 255), (b.float * 255).toInt().coerceIn(0, 255), (b.float * 255).toInt().coerceIn(0, 255))
                        "CMYK" -> { val cc = b.float; val m = b.float; val y = b.float; val k = b.float; cmyk(cc, m, y, k) }
                        "Gray" -> { val g = (b.float * 255).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
                        "LAB " -> { val l = b.float; val aa = b.float; val bb = b.float; lab(l * 100f, aa, bb) }
                        else -> null
                    }
                    if (c != null) colors.add(c)
                }
            }
            b.position(start + len)
        }
        return Imported(group ?: fileName.substringBeforeLast('.'), colors)
    }

    private fun readAco(bytes: ByteArray, fileName: String): Imported {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.short
        val n = b.short.toInt() and 0xFFFF
        val colors = ArrayList<Int>()
        repeat(n) {
            if (b.remaining() < 10) return@repeat
            val space = b.short.toInt() and 0xFFFF
            val w = b.short.toInt() and 0xFFFF; val x = b.short.toInt() and 0xFFFF; val y = b.short.toInt() and 0xFFFF; b.short
            colors.add(when (space) {
                0 -> Color.rgb(w / 257, x / 257, y / 257)
                1 -> Color.HSVToColor(floatArrayOf(w / 182.04f, x / 65535f, y / 65535f))
                2 -> cmyk(1f - w / 65535f, 1f - x / 65535f, 1f - y / 65535f, 0f)
                8 -> { val g = 255 - (w * 255 / 10000).coerceIn(0, 255); Color.rgb(g, g, g) }
                else -> return@repeat
            })
        }
        return Imported(fileName.substringBeforeLast('.'), colors)
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float) =
        Color.rgb(((1 - c) * (1 - k) * 255).toInt().coerceIn(0, 255), ((1 - m) * (1 - k) * 255).toInt().coerceIn(0, 255), ((1 - y) * (1 - k) * 255).toInt().coerceIn(0, 255))

    private fun lab(l: Float, a: Float, bb: Float): Int {
        var y = (l + 16f) / 116f; var x = a / 500f + y; var z = y - bb / 200f
        fun f(t: Float) = if (t * t * t > 0.008856f) t * t * t else (t - 16f / 116f) / 7.787f
        x = 0.95047f * f(x); y = f(y); z = 1.08883f * f(z)
        fun g(c: Float) = (if (c > 0.0031308f) 1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f else 12.92f * c).coerceIn(0f, 1f)
        val r = g(x * 3.2406f + y * -1.5372f + z * -0.4986f); val gg = g(x * -0.9689f + y * 1.8758f + z * 0.0415f); val bl = g(x * 0.0557f + y * -0.2040f + z * 1.0570f)
        return Color.rgb((r * 255).toInt(), (gg * 255).toInt(), (bl * 255).toInt())
    }

    /** Procreate-compatible .swatches file. */
    fun writeSwatches(name: String, colors: List<Int>, out: OutputStream) {
        val sw = JSONArray()
        for (c in colors) {
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            sw.put(JSONObject().put("hue", hsv[0] / 360.0).put("saturation", hsv[1].toDouble()).put("brightness", hsv[2].toDouble()).put("alpha", 1).put("colorSpace", 0))
        }
        val json = JSONArray().put(JSONObject().put("name", name).put("swatches", sw)).toString()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("Swatches.json")); z.write(json.toByteArray()); z.closeEntry() }
    }

    /** [k] dominant colours of an image (k-means on up to 6 000 samples), ordered dark → light. */
    fun fromImage(bmp: Bitmap, k: Int = 15): List<Int> {
        val s = min(1f, 160f / max(bmp.width, bmp.height))
        val small = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
        val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        if (small !== bmp) small.recycle()
        val pts = px.filter { (it ushr 24) > 200 }.ifEmpty { return emptyList() }
        val rnd = Random(7)
        val sample = if (pts.size > 6000) List(6000) { pts[rnd.nextInt(pts.size)] } else pts
        val kk = min(k, sample.distinct().size)
        // k-means++ seeding
        val cents = ArrayList<FloatArray>()
        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())
        fun d2(a: FloatArray, b: FloatArray) = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])
        val v = sample.map { rgb(it) }
        cents.add(v[rnd.nextInt(v.size)])
        while (cents.size < kk) {
            val dist = v.map { p -> cents.minOf { d2(p, it) } }
            var r = rnd.nextFloat() * dist.sum()
            var idx = 0
            while (idx < dist.size - 1 && r > dist[idx]) { r -= dist[idx]; idx++ }
            cents.add(v[idx])
        }
        repeat(12) {
            val sums = Array(kk) { FloatArray(4) }
            for (p in v) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in cents.indices) { val d = d2(p, cents[c]); if (d < bd) { bd = d; best = c } }
                sums[best][0] += p[0]; sums[best][1] += p[1]; sums[best][2] += p[2]; sums[best][3] += 1f
            }
            for (c in cents.indices) if (sums[c][3] > 0) cents[c] = floatArrayOf(sums[c][0] / sums[c][3], sums[c][1] / sums[c][3], sums[c][2] / sums[c][3])
        }
        return cents.map { Color.rgb(it[0].toInt(), it[1].toInt(), it[2].toInt()) }.distinct()
            .sortedBy { 0.299 * Color.red(it) + 0.587 * Color.green(it) + 0.114 * Color.blue(it) }
    }

}
