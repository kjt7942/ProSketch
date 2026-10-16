package com.prosketch.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** Encodes a sequence of bitmaps to H.264 MP4 using YUV420 flexible input images. */
class Mp4Encoder(private val file: File, width: Int, height: Int, private val fps: Int, bitrate: Int = 0) {
    val w = (width / 2) * 2
    val h = (height / 2) * 2
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var frameIndex = 0L
    private val info = MediaCodec.BufferInfo()
    private val argb = IntArray(w * h)
    private val scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(scratch)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (bitrate > 0) bitrate else max(2_000_000, w * h * 6))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Adds one frame; the bitmap is letterboxed onto a white frame if its aspect differs. */
    fun addFrame(src: Bitmap, background: Int = Color.WHITE) {
        scratchCanvas.drawColor(background)
        val s = min(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * s; val dh = src.height * s
        scratchCanvas.save()
        scratchCanvas.translate((w - dw) / 2f, (h - dh) / 2f)
        scratchCanvas.scale(s, s)
        scratchCanvas.drawBitmap(src, 0f, 0f, paint)
        scratchCanvas.restore()
        scratch.getPixels(argb, 0, w, 0, 0, w, h)

        var idx: Int
        while (true) {
            idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) break
            drain(false)
        }
        val img = codec.getInputImage(idx)!!
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yb.put(y * yRow + x * yPix, yy.coerceIn(0, 255).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((y / 2) * uRow + (x / 2) * uPix, u.coerceIn(0, 255).toByte())
                    vb.put((y / 2) * vRow + (x / 2) * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
        val pts = frameIndex * 1_000_000L / fps
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
        frameIndex++
        drain(false)
    }

    private fun drain(end: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish() {
        var idx: Int
        while (true) { idx = codec.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
        codec.queueInputBuffer(idx, 0, 0, frameIndex * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release()
        if (started) muxer.stop()
        muxer.release()
        scratch.recycle()
    }
}

/** Animated GIF writer with a median-cut global palette and LZW compression. */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int, private val delayCs: Int, private val loop: Boolean) {
    private val o = BufferedOutputStream(out)
    private var palette = IntArray(256)
    private var lookup: ByteArray? = null
    private var headerWritten = false

    /** Builds the palette from sample frames (all frames scaled to w×h). */
    fun buildPalette(samples: List<Bitmap>) {
        val px = ArrayList<Int>()
        for (s in samples) {
            val arr = IntArray(w * h)
            s.getPixels(arr, 0, w, 0, 0, w, h)
            val step = max(1, arr.size / (60_000 / max(1, samples.size)))
            var i = 0
            while (i < arr.size) { px.add(arr[i] or (0xFF shl 24)); i += step }
        }
        palette = medianCut(px.toIntArray(), 255)
        // 5-bit RGB lookup cube → palette index
        val lut = ByteArray(32 * 32 * 32)
        for (r in 0 until 32) for (g in 0 until 32) for (b in 0 until 32) {
            val rr = r * 8 + 4; val gg = g * 8 + 4; val bb = b * 8 + 4
            var best = 0; var bd = Int.MAX_VALUE
            for (k in 0 until 255) {
                val c = palette[k]
                val dr = ((c shr 16) and 0xFF) - rr; val dg = ((c shr 8) and 0xFF) - gg; val db = (c and 0xFF) - bb
                val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (d < bd) { bd = d; best = k }
            }
            lut[(r shl 10) or (g shl 5) or b] = best.toByte()
        }
        lookup = lut
    }

    private fun medianCut(px: IntArray, n: Int): IntArray {
        class Box(val items: IntArray)
        val boxes = ArrayList<Box>()
        boxes.add(Box(px))
        while (boxes.size < n) {
            var bi = -1; var bestRange = -1; var bestCh = 0
            for ((i, bx) in boxes.withIndex()) {
                if (bx.items.size < 2) continue
                for (ch in 0..2) {
                    val s = 16 - ch * 8
                    var mn = 255; var mx = 0
                    for (c in bx.items) { val v = (c shr s) and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                    if (mx - mn > bestRange) { bestRange = mx - mn; bi = i; bestCh = ch }
                }
            }
            if (bi < 0 || bestRange <= 0) break
            val bx = boxes.removeAt(bi)
            val s = 16 - bestCh * 8
            val sorted = bx.items.sortedBy { (it shr s) and 0xFF }.toIntArray()
            val mid = sorted.size / 2
            boxes.add(Box(sorted.copyOfRange(0, mid))); boxes.add(Box(sorted.copyOfRange(mid, sorted.size)))
        }
        val pal = IntArray(256)
        for ((i, bx) in boxes.withIndex()) {
            var r = 0L; var g = 0L; var b = 0L
            for (c in bx.items) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            val k = max(1, bx.items.size)
            pal[i] = Color.rgb((r / k).toInt(), (g / k).toInt(), (b / k).toInt())
        }
        return pal
    }

    private fun writeShort(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    private fun header() {
        o.write("GIF89a".toByteArray())
        writeShort(w); writeShort(h)
        o.write(0xF7) // global colour table, 8 bits
        o.write(0); o.write(0)
        for (c in palette) { o.write((c shr 16) and 0xFF); o.write((c shr 8) and 0xFF); o.write(c and 0xFF) }
        if (loop) {
            o.write(0x21); o.write(0xFF); o.write(11); o.write("NETSCAPE2.0".toByteArray())
            o.write(3); o.write(1); writeShort(0); o.write(0)
        }
        headerWritten = true
    }

    fun addFrame(frame: Bitmap) {
        if (lookup == null) buildPalette(listOf(frame))
        if (!headerWritten) header()
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)
        val lut = lookup!!
        val idx = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            idx[i] = lut[(((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)]
        }
        // graphics control extension
        o.write(0x21); o.write(0xF9); o.write(4); o.write(0x04); writeShort(delayCs); o.write(0); o.write(0)
        // image descriptor
        o.write(0x2C); writeShort(0); writeShort(0); writeShort(w); writeShort(h); o.write(0)
        lzw(idx)
    }

    private fun lzw(data: ByteArray) {
        val minCode = 8
        o.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val block = ByteArray(255); var bl = 0
        var acc = 0; var bits = 0
        fun emitByte(b: Int) { block[bl++] = b.toByte(); if (bl == 255) { o.write(255); o.write(block, 0, 255); bl = 0 } }
        var codeSize = minCode + 1
        fun emit(code: Int) {
            acc = acc or (code shl bits); bits += codeSize
            while (bits >= 8) { emitByte(acc and 0xFF); acc = acc ushr 8; bits -= 8 }
        }
        val table = HashMap<Int, Int>(8192)
        var next = eoi + 1
        emit(clear)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear); table.clear(); next = eoi + 1; codeSize = minCode + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(eoi)
        if (bits > 0) emitByte(acc and 0xFF)
        if (bl > 0) { o.write(bl); o.write(block, 0, bl) }
        o.write(0)
    }

    fun finish() { if (!headerWritten) header(); o.write(0x3B); o.flush() }
}
