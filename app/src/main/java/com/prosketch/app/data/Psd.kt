package com.prosketch.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.prosketch.app.engine.Bitmaps
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.Document
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LayerBlend
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Layered Photoshop document writer/reader (8-bit RGB, RLE). */
object Psd {

    // ------------------------------------------------------------------ writer

    private class LayerBlob(
        val rect: Rect, val channelData: List<ByteArray>, val blend: String, val opacity: Int,
        val clipping: Boolean, val flags: Int, val name: String,
    )

    fun write(doc: Document, out: OutputStream) {
        val w = doc.width; val h = doc.height
        val blobs = ArrayList<LayerBlob>()
        if (doc.backgroundVisible) {
            val bg = Layer.blank(w, h, "배경")
            bg.bitmap.eraseColor(doc.backgroundColor or (0xFF shl 24))
            blobs.add(encodeLayer(bg, Rect(0, 0, w, h), "norm", 255, false, 0))
            bg.bitmap.recycle()
        }
        for (l in doc.layers) {
            val r = Bitmaps.opaqueBounds(l.bitmap) ?: Rect(0, 0, 0, 0)
            var flags = 0
            if (l.alphaLock) flags = flags or 1
            if (!l.visible) flags = flags or 2
            blobs.add(encodeLayer(l, r, l.blend.psdKey, (l.opacity * 255).toInt().coerceIn(0, 255), l.clipping, flags))
        }

        val layerInfo = ByteArrayOutputStream()
        DataOutputStream(layerInfo).use { d ->
            d.writeShort(blobs.size)
            for (b in blobs) {
                d.writeInt(b.rect.top); d.writeInt(b.rect.left); d.writeInt(b.rect.bottom); d.writeInt(b.rect.right)
                d.writeShort(4)
                val ids = intArrayOf(-1, 0, 1, 2)
                for (c in 0 until 4) { d.writeShort(ids[c]); d.writeInt(b.channelData[c].size) }
                d.writeBytes("8BIM"); d.writeBytes(b.blend)
                d.writeByte(b.opacity); d.writeByte(if (b.clipping) 1 else 0); d.writeByte(b.flags); d.writeByte(0)
                val extra = ByteArrayOutputStream()
                DataOutputStream(extra).use { e ->
                    e.writeInt(0) // mask data
                    e.writeInt(0) // blending ranges
                    val ascii = b.name.filter { it.code in 32..126 }.take(31).ifEmpty { "Layer" }
                    val nameBytes = ascii.toByteArray(Charsets.US_ASCII)
                    e.writeByte(nameBytes.size); e.write(nameBytes)
                    var padded = 1 + nameBytes.size
                    while (padded % 4 != 0) { e.writeByte(0); padded++ }
                    // unicode name
                    val uni = ByteArrayOutputStream()
                    DataOutputStream(uni).use { u -> u.writeInt(b.name.length); for (ch in b.name) u.writeChar(ch.code) }
                    var ub = uni.toByteArray()
                    if (ub.size % 4 != 0) ub = ub.copyOf(ub.size + (4 - ub.size % 4))
                    e.writeBytes("8BIM"); e.writeBytes("luni"); e.writeInt(ub.size); e.write(ub)
                }
                val eb = extra.toByteArray()
                d.writeInt(eb.size); d.write(eb)
            }
            for (b in blobs) for (c in 0 until 4) d.write(b.channelData[c])
        }
        var li = layerInfo.toByteArray()
        if (li.size % 2 != 0) li = li.copyOf(li.size + 1)

        val composite = Compositor.flatten(doc)
        val compData = encodeComposite(composite)
        composite.recycle()

        DataOutputStream(out.buffered(1 shl 16)).use { d ->
            d.writeBytes("8BPS"); d.writeShort(1); d.write(ByteArray(6)); d.writeShort(3)
            d.writeInt(h); d.writeInt(w); d.writeShort(8); d.writeShort(3)
            d.writeInt(0)      // colour mode data
            d.writeInt(0)      // image resources
            d.writeInt(4 + li.size + 4)
            d.writeInt(li.size); d.write(li)
            d.writeInt(0)      // global layer mask info
            d.write(compData)
        }
    }

    private fun encodeLayer(l: Layer, r: Rect, blend: String, opacity: Int, clip: Boolean, flags: Int): LayerBlob {
        val w = r.width(); val h = r.height()
        if (w <= 0 || h <= 0) {
            val empty = ByteArray(2) // compression 0, no data
            return LayerBlob(Rect(0, 0, 0, 0), List(4) { empty }, blend, opacity, clip, flags, l.name)
        }
        val px = IntArray(w * h)
        l.bitmap.getPixels(px, 0, w, r.left, r.top, w, h)
        val shifts = intArrayOf(24, 16, 8, 0)
        val data = shifts.map { s -> rleChannel(px, w, h, s) }
        return LayerBlob(r, data, blend, opacity, clip, flags, l.name)
    }

    private fun rleChannel(px: IntArray, w: Int, h: Int, shift: Int): ByteArray {
        val row = ByteArray(w)
        val counts = ShortArray(h)
        val body = ByteArrayOutputStream(w * h / 2)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = (px[y * w + x] shr shift).toByte()
            val before = body.size()
            packBits(row, body)
            counts[y] = (body.size() - before).toShort()
        }
        val out = ByteArrayOutputStream(2 + h * 2 + body.size())
        DataOutputStream(out).use { d ->
            d.writeShort(1)
            for (c in counts) d.writeShort(c.toInt())
            body.writeTo(d)
        }
        return out.toByteArray()
    }

    private fun encodeComposite(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bodies = ArrayList<ByteArray>()
        val counts = ArrayList<Short>()
        val row = ByteArray(w)
        for (s in intArrayOf(16, 8, 0)) {
            val body = ByteArrayOutputStream()
            for (y in 0 until h) {
                for (x in 0 until w) row[x] = (px[y * w + x] shr s).toByte()
                val b = body.size(); packBits(row, body); counts.add((body.size() - b).toShort())
            }
            bodies.add(body.toByteArray())
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeShort(1)
            for (c in counts) d.writeShort(c.toInt())
            for (b in bodies) d.write(b)
        }
        return out.toByteArray()
    }

    fun packBits(src: ByteArray, out: ByteArrayOutputStream) {
        val n = src.size
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && src[i + run] == src[i]) run++
            if (run >= 3) {
                out.write(-(run - 1) and 0xFF); out.write(src[i].toInt() and 0xFF)
                i += run
            } else {
                val start = i
                var len = 0
                while (i < n && len < 128) {
                    if (i + 2 < n && src[i] == src[i + 1] && src[i] == src[i + 2]) break
                    i++; len++
                }
                out.write(len - 1)
                out.write(src, start, len)
            }
        }
    }

    // ------------------------------------------------------------------ reader

    class Imported(val width: Int, val height: Int, val layers: List<Layer>)

    fun read(bytes: ByteArray): Imported {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun sig(): String { val a = ByteArray(4); b.get(a); return String(a, Charsets.US_ASCII) }
        require(sig() == "8BPS") { "PSD 파일이 아닙니다" }
        val version = b.short.toInt()
        require(version == 1) { "PSB(대형 문서)는 지원하지 않습니다" }
        b.position(b.position() + 6)
        val channels = b.short.toInt()
        val h = b.int; val w = b.int
        val depth = b.short.toInt(); val mode = b.short.toInt()
        require(depth == 8) { "8비트 PSD만 지원합니다 (현재 ${depth}비트)" }
        require(mode == 3 || mode == 1) { "RGB 또는 회색조 PSD만 지원합니다" }
        b.position(b.position() + b.int)  // colour mode data
        b.position(b.position() + b.int)  // image resources
        val lmLen = b.int.toLong() and 0xFFFFFFFFL
        val lmEnd = b.position() + lmLen
        val layers = ArrayList<Layer>()
        if (lmLen > 0) {
            val liLen = b.int.toLong() and 0xFFFFFFFFL
            if (liLen > 0) {
                var count = b.short.toInt()
                if (count < 0) count = -count
                class Rec(val r: Rect, val chans: List<Pair<Int, Int>>, val blend: String, val opacity: Int, val clip: Int, val flags: Int, var name: String, var section: Int)
                val recs = ArrayList<Rec>()
                repeat(count) {
                    val top = b.int; val left = b.int; val bottom = b.int; val right = b.int
                    val nc = b.short.toInt()
                    val ch = (0 until nc).map { b.short.toInt() to b.int }
                    sig()
                    val blend = sig()
                    val op = b.get().toInt() and 0xFF; val clip = b.get().toInt() and 0xFF
                    val fl = b.get().toInt() and 0xFF; b.get()
                    val extraLen = b.int
                    val extraEnd = b.position() + extraLen
                    b.position(b.position() + b.int)  // mask
                    b.position(b.position() + b.int)  // blending ranges
                    val nl = b.get().toInt() and 0xFF
                    val nb = ByteArray(nl); b.get(nb)
                    var name = String(nb, Charsets.ISO_8859_1)
                    var padded = 1 + nl
                    while (padded % 4 != 0) { b.get(); padded++ }
                    var section = 0
                    while (b.position() + 12 <= extraEnd) {
                        val s = sig()
                        if (s != "8BIM" && s != "8B64") break
                        val key = sig()
                        val len = b.int
                        val start = b.position()
                        if (key == "luni" && len >= 4) {
                            val n = b.int
                            val sb = StringBuilder()
                            repeat(n) { sb.append(b.char) }
                            name = sb.toString()
                        } else if (key == "lsct" && len >= 4) {
                            section = b.int
                        }
                        b.position(start + len)
                    }
                    b.position(extraEnd)
                    recs.add(Rec(Rect(left, top, right, bottom), ch, blend, op, clip, fl, name, section))
                }
                for (rec in recs) {
                    val rw = rec.r.width(); val rh = rec.r.height()
                    val planes = HashMap<Int, ByteArray>()
                    for ((id, len) in rec.chans) {
                        val start = b.position()
                        if (rw > 0 && rh > 0 && len > 2) planes[id] = readChannel(b, rw, rh) else b.position(start + len)
                        b.position(start + len)
                    }
                    if (rec.section in 1..3) continue  // group markers
                    val layer = Layer.blank(w, h, rec.name.ifBlank { "레이어" })
                    if (rw > 0 && rh > 0) {
                        val px = IntArray(rw * rh)
                        val pa = planes[-1]; val pr = planes[0]; val pg = planes[1] ?: pr; val pb = planes[2] ?: pr
                        for (i in px.indices) {
                            val a = pa?.get(i)?.toInt()?.and(0xFF) ?: 255
                            val r = pr?.get(i)?.toInt()?.and(0xFF) ?: 0
                            val g = pg?.get(i)?.toInt()?.and(0xFF) ?: 0
                            val bl = pb?.get(i)?.toInt()?.and(0xFF) ?: 0
                            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                        }
                        // clip to canvas
                        val tmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                        tmp.setPixels(px, 0, rw, 0, 0, rw, rh)
                        Canvas(layer.bitmap).drawBitmap(tmp, rec.r.left.toFloat(), rec.r.top.toFloat(), null)
                        tmp.recycle()
                    }
                    layer.blend = LayerBlend.fromPsd(rec.blend)
                    layer.opacity = rec.opacity / 255f
                    layer.clipping = rec.clip != 0 && layers.isNotEmpty()
                    layer.visible = rec.flags and 2 == 0
                    layer.alphaLock = rec.flags and 1 != 0
                    layers.add(layer)
                }
            }
        }
        b.position(lmEnd.toInt())
        if (layers.isEmpty()) {
            // flattened PSD: use the composite image
            val comp = b.short.toInt()
            val planes = Array(channels) { ByteArray(w * h) }
            if (comp == 1) {
                val counts = IntArray(channels * h) { b.short.toInt() and 0xFFFF }
                var k = 0
                for (c in 0 until channels) for (y in 0 until h) {
                    unpack(b, counts[k++], planes[c], y * w, w)
                }
            } else for (c in 0 until channels) b.get(planes[c])
            val px = IntArray(w * h)
            val gray = mode == 1
            for (i in px.indices) {
                val r = planes[0][i].toInt() and 0xFF
                val g = if (gray) r else planes[1][i].toInt() and 0xFF
                val bl = if (gray) r else planes[2][i].toInt() and 0xFF
                val a = if (!gray && channels > 3) planes[3][i].toInt() and 0xFF else if (gray && channels > 1) planes[1][i].toInt() and 0xFF else 255
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
            }
            val l = Layer.blank(w, h, "이미지")
            l.bitmap.setPixels(px, 0, w, 0, 0, w, h)
            layers.add(l)
        }
        return Imported(w, h, layers)
    }

    private fun readChannel(b: ByteBuffer, w: Int, h: Int): ByteArray {
        val comp = b.short.toInt()
        val out = ByteArray(w * h)
        when (comp) {
            0 -> b.get(out)
            1 -> {
                val counts = IntArray(h) { b.short.toInt() and 0xFFFF }
                for (y in 0 until h) unpack(b, counts[y], out, y * w, w)
            }
            else -> throw IllegalArgumentException("ZIP 압축 PSD는 지원하지 않습니다")
        }
        return out
    }

    private fun unpack(b: ByteBuffer, len: Int, out: ByteArray, off: Int, w: Int) {
        val end = b.position() + len
        var o = 0
        while (b.position() < end && o < w) {
            val n = b.get().toInt()
            if (n >= 0) {
                val c = minOf(n + 1, w - o)
                b.get(out, off + o, c); o += c
                if (c < n + 1) b.position(b.position() + (n + 1 - c))
            } else if (n != -128) {
                val v = b.get()
                val c = minOf(-n + 1, w - o)
                for (k in 0 until c) out[off + o + k] = v
                o += c
            }
        }
        b.position(end)
    }
}
