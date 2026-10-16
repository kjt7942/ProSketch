package com.prosketch.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.Document
import com.prosketch.app.engine.Layer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PROSKETCH("ProSketch", "prosketch", "application/zip"),
    PSD("PSD", "psd", "image/vnd.adobe.photoshop"),
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    PDF("PDF", "pdf", "application/pdf"),
    LAYERS_PNG("PNG 레이어 (ZIP)", "zip", "application/zip"),
    GIF("움직이는 GIF", "gif", "image/gif"),
    MP4("움직이는 MP4", "mp4", "video/mp4"),
    TIMELAPSE("타임랩스 MP4", "mp4", "video/mp4"),
    TIMELAPSE30("타임랩스 30초", "mp4", "video/mp4"),
}

object Exporter {

    fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|%]"), "_").trim().ifEmpty { "ProSketch" }

    /** Writes the export to a cache file. Runs on a background thread. Layer bitmaps are read, not modified. */
    fun export(ctx: Context, store: ProjectStore, doc: Document, fmt: ExportFormat, progress: (Float) -> Unit = {}): File {
        val dir = exportDir(ctx)
        val f = File(dir, safeName(doc.name) + (if (fmt == ExportFormat.TIMELAPSE || fmt == ExportFormat.TIMELAPSE30) "_timelapse" else "") + "." + fmt.ext)
        f.delete()
        when (fmt) {
            ExportFormat.PROSKETCH -> f.outputStream().use { store.exportZip(doc.id, it) }
            ExportFormat.PSD -> f.outputStream().use { Psd.write(doc, it) }
            ExportFormat.PNG -> {
                val b = Compositor.flatten(doc)
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            ExportFormat.JPEG -> {
                val b = Compositor.flatten(doc)
                val out = if (!doc.backgroundVisible) {
                    val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                    Canvas(o).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
                    o
                } else b
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                b.recycle(); if (out !== b) out.recycle()
            }
            ExportFormat.PDF -> {
                val pdf = PdfDocument()
                // 72 dpi points; keep the long side at most 14400pt
                val s = min(1f, 14400f / max(doc.width, doc.height))
                val pw = (doc.width * s).toInt(); val ph = (doc.height * s).toInt()
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
                val b = Compositor.flatten(doc)
                page.canvas.drawBitmap(b, null, android.graphics.Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                f.outputStream().use { pdf.writeTo(it) }
                pdf.close(); b.recycle()
            }
            ExportFormat.LAYERS_PNG -> {
                java.util.zip.ZipOutputStream(f.outputStream().buffered()).use { zip ->
                    doc.layers.forEachIndexed { i, l ->
                        zip.putNextEntry(java.util.zip.ZipEntry(String.format("%02d_%s.png", i + 1, safeName(l.name))))
                        l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                        progress((i + 1f) / doc.layers.size)
                    }
                }
            }
            ExportFormat.GIF -> exportAnimationGif(doc, f, progress)
            ExportFormat.MP4 -> exportAnimationMp4(doc, f, progress)
            ExportFormat.TIMELAPSE, ExportFormat.TIMELAPSE30 -> exportTimelapse(store, doc, f, fmt == ExportFormat.TIMELAPSE30, progress)
        }
        return f
    }

    private fun frameBitmap(doc: Document, frame: Int, scale: Float): Bitmap {
        val w = max(2, (doc.width * scale).toInt()); val h = max(2, (doc.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(if (doc.backgroundVisible) doc.backgroundColor or (0xFF shl 24) else Color.WHITE)
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (doc.animation.firstFrameBackground && frame != 0 && doc.layers.isNotEmpty()) drawFrameLayer(c, doc.layers[0], p)
        drawFrameLayer(c, doc.layers[frame], p)
        return out
    }

    private fun drawFrameLayer(c: Canvas, l: Layer, p: Paint) {
        p.alpha = (l.opacity * 255).toInt(); p.blendMode = l.blend.mode
        c.drawBitmap(l.bitmap, 0f, 0f, p)
    }

    private fun frameOrder(doc: Document): List<Int> {
        val n = doc.layers.size
        val start = if (doc.animation.firstFrameBackground && n > 1) 1 else 0
        val base = (start until n).toList()
        return when (doc.animation.loop) {
            com.prosketch.app.engine.AnimLoop.PINGPONG -> base + base.reversed().drop(1).dropLast(1)
            else -> base
        }
    }

    private fun exportAnimationGif(doc: Document, f: File, progress: (Float) -> Unit) {
        val scale = min(1f, 1080f / max(doc.width, doc.height))
        val order = frameOrder(doc)
        val first = frameBitmap(doc, order.first(), scale)
        f.outputStream().use { os ->
            val enc = GifEncoder(os, first.width, first.height, max(2, 100 / doc.animation.fps), doc.animation.loop != com.prosketch.app.engine.AnimLoop.ONESHOT)
            val samples = order.take(12).map { frameBitmap(doc, it, scale) }
            enc.buildPalette(samples)
            samples.forEach { it.recycle() }
            order.forEachIndexed { i, fr ->
                val b = if (i == 0) first else frameBitmap(doc, fr, scale)
                enc.addFrame(b); b.recycle()
                progress((i + 1f) / order.size)
            }
            enc.finish()
        }
    }

    private fun exportAnimationMp4(doc: Document, f: File, progress: (Float) -> Unit) {
        val scale = min(1f, 1920f / max(doc.width, doc.height))
        val order = frameOrder(doc)
        val w = max(16, (doc.width * scale).toInt()); val h = max(16, (doc.height * scale).toInt())
        val enc = Mp4Encoder(f, w, h, doc.animation.fps)
        val loops = if (doc.animation.loop == com.prosketch.app.engine.AnimLoop.ONESHOT) 1 else max(1, (doc.animation.fps * 6) / max(1, order.size))
        var k = 0
        repeat(loops) {
            for (fr in order) {
                val b = frameBitmap(doc, fr, scale)
                enc.addFrame(b); b.recycle()
                k++
                progress(k / (loops * order.size).toFloat())
            }
        }
        enc.finish()
    }

    private fun exportTimelapse(store: ProjectStore, doc: Document, f: File, thirty: Boolean, progress: (Float) -> Unit) {
        val frames = (store.timelapseDir(doc.id).listFiles() ?: emptyArray()).filter { it.name.endsWith(".jpg") }.sortedBy { it.name }
        require(frames.isNotEmpty()) { "녹화된 타임랩스가 없습니다" }
        val fps = 30
        var pick: List<File> = frames
        if (thirty && frames.size > fps * 30) {
            // keep more early frames, then thin out (similar to Procreate's 30s export)
            val target = fps * 30
            pick = (0 until target).map { i ->
                val t = i / (target - 1f)
                frames[((t * t * 0.35f + t * 0.65f) * (frames.size - 1)).toInt()]
            }
        }
        val first = BitmapFactory.decodeFile(pick.first().absolutePath)
        val enc = Mp4Encoder(f, first.width, first.height, fps)
        pick.forEachIndexed { i, file ->
            val b = if (i == 0) first else BitmapFactory.decodeFile(file.absolutePath)
            if (b != null) { enc.addFrame(b); b.recycle() }
            progress((i + 1f) / pick.size)
        }
        // hold the final artwork for 2 seconds
        val final = Compositor.flatten(doc, min(1f, 1920f / max(doc.width, doc.height)))
        repeat(fps * 2) { enc.addFrame(final) }
        final.recycle()
        enc.finish()
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves an image or video to the device gallery (Pictures/ProSketch or Movies/ProSketch). */
    fun saveToGallery(ctx: Context, file: File, mime: String): Uri? {
        val video = mime.startsWith("video")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Pictures") + "/ProSketch")
        }
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(coll, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        return uri
    }
}

/**
 * Records a downscaled frame after each committed action. Frames are rendered and encoded
 * on a single background thread; only one frame is in flight at a time.
 */
class TimelapseRecorder(private val dir: File) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "timelapse").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var index = (dir.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0)
    var pendingAgain = false

    val frameCount get() = index

    /** [render] runs on the background thread; it must only read shared state. */
    fun capture(render: () -> Bitmap) {
        if (!busy.compareAndSet(false, true)) { pendingAgain = true; return }
        pendingAgain = false
        exec.execute {
            var bmp: Bitmap? = null
            try {
                bmp = render()
                dir.mkdirs()
                File(dir, String.format("f_%07d.jpg", index++)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            } catch (_: Throwable) {
            } finally {
                bmp?.recycle()
                busy.set(false)
            }
        }
    }

    fun frames(): List<File> = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".jpg") }.sortedBy { it.name }

    fun purge() { dir.deleteRecursively(); index = 0 }
}
