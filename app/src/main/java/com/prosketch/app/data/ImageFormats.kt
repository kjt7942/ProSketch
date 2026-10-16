package com.prosketch.app.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Animated PNG writer: each frame is compressed by Android's PNG encoder and repackaged as APNG chunks. */
class Apng(private val out: OutputStream, private val fps: Int, private val frameCount: Int, private val plays: Int) {
    private var seq = 0
    private var index = 0
    private var headerWritten = false

    private fun chunk(type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    private fun parse(png: ByteArray): List<Pair<String, ByteArray>> {
        val b = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
        b.position(8)
        val out = ArrayList<Pair<String, ByteArray>>()
        while (b.remaining() >= 12) {
            val len = b.int
            val t = ByteArray(4); b.get(t)
            val data = ByteArray(len); b.get(data)
            b.int // crc
            out.add(String(t, Charsets.US_ASCII) to data)
        }
        return out
    }

    /** [beats] = how many 1/fps periods the frame stays on screen. */
    fun addFrame(bmp: Bitmap, beats: Int) {
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val chunks = parse(bytes)
        if (!headerWritten) {
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk("IHDR", chunks.first { it.first == "IHDR" }.second)
            chunk("acTL", ByteBuffer.allocate(8).putInt(frameCount).putInt(plays).array())
            headerWritten = true
        }
        val fc = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
            .putInt(seq++).putInt(bmp.width).putInt(bmp.height).putInt(0).putInt(0)
            .putShort(beats.coerceIn(1, 65535).toShort()).putShort(fps.coerceIn(1, 255).toShort())
            .put(0) /* dispose: none */ .put(0) /* blend: source */
        chunk("fcTL", fc.array())
        for ((t, d) in chunks) if (t == "IDAT") {
            if (index == 0) chunk("IDAT", d)
            else chunk("fdAT", ByteBuffer.allocate(4 + d.size).putInt(seq++).put(d).array())
        }
        index++
    }

    fun finish() { chunk("IEND", ByteArray(0)); out.flush() }
}

/** Baseline uncompressed RGBA TIFF (little-endian, unassociated alpha). */
object Tiff {
    fun write(bmp: Bitmap, out: OutputStream) {
        val w = bmp.width; val h = bmp.height
        val tags = 11
        val ifdOffset = 8
        val ifdSize = 2 + tags * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val dataOffset = bpsOffset + 8
        val dataLen = w * h * 4
        val hdr = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        hdr.putShort(tags.toShort())
        fun tag(id: Int, type: Int, count: Int, value: Int) { hdr.putShort(id.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        tag(256, 4, 1, w)                 // ImageWidth
        tag(257, 4, 1, h)                 // ImageLength
        tag(258, 3, 4, bpsOffset)         // BitsPerSample → 8,8,8,8
        tag(259, 3, 1, 1)                 // Compression: none
        tag(262, 3, 1, 2)                 // Photometric: RGB
        tag(273, 4, 1, dataOffset)        // StripOffsets
        tag(277, 3, 1, 4)                 // SamplesPerPixel
        tag(278, 4, 1, h)                 // RowsPerStrip
        tag(279, 4, 1, dataLen)           // StripByteCounts
        tag(284, 3, 1, 1)                 // PlanarConfiguration: chunky
        tag(338, 3, 1, 2)                 // ExtraSamples: unassociated alpha
        hdr.putInt(0)                     // no next IFD
        repeat(4) { hdr.putShort(8) }
        out.write(hdr.array())
        val row = IntArray(w)
        val rb = ByteArray(w * 4)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                rb[x * 4] = (c shr 16).toByte(); rb[x * 4 + 1] = (c shr 8).toByte(); rb[x * 4 + 2] = c.toByte(); rb[x * 4 + 3] = (c ushr 24).toByte()
            }
            out.write(rb)
        }
        out.flush()
    }
}
