package com.prosketch.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import com.prosketch.app.engine.AnimLoop
import com.prosketch.app.engine.Document
import com.prosketch.app.engine.GuideType
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.engine.SymmetryType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ArtworkInfo(
    val id: String, val name: String, val width: Int, val height: Int,
    val modified: Long, val layerCount: Int, val thumb: File,
)

/** A frozen copy of what must be written, so saving can run off the main thread. */
class SaveSnapshot(
    val id: String,
    val manifest: JSONObject,
    val dirtyLayers: List<Pair<Long, Bitmap>>,   // layer id → private copy
    val keepLayerIds: Set<Long>,
    val thumbnail: Bitmap,
)

class ProjectStore(private val ctx: Context) {
    val root = File(ctx.filesDir, "artworks").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun timelapseDir(id: String) = File(dir(id), "timelapse")

    fun newId(): String = UUID.randomUUID().toString().substring(0, 13).replace("-", "")

    fun list(): List<ArtworkInfo> = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
        val mf = File(d, "project.json")
        if (!mf.exists()) return@mapNotNull null
        runCatching {
            val o = JSONObject(mf.readText())
            val layers = o.optJSONArray("layers")
            val count = if (layers == null) 0 else (0 until layers.length()).count { !layers.getJSONObject(it).optBoolean("group") }
            ArtworkInfo(d.name, o.optString("name", "무제"), o.getInt("width"), o.getInt("height"),
                o.optLong("modified", mf.lastModified()), count, File(d, "thumb.jpg"))
        }.getOrNull()
    }.sortedByDescending { it.modified }

    fun manifestOf(doc: Document): JSONObject = JSONObject().apply {
        put("version", 2)
        put("name", doc.name); put("width", doc.width); put("height", doc.height)
        put("background", doc.backgroundColor.toLong() and 0xFFFFFFFFL); put("backgroundVisible", doc.backgroundVisible)
        put("active", doc.activeIndex); put("strokes", doc.strokeCount); put("timeSpent", doc.timeSpentMs)
        put("created", doc.createdAt); put("modified", System.currentTimeMillis())
        put("timelapse", doc.timelapseEnabled)
        put("layers", JSONArray().apply {
            for (l in doc.layers) put(JSONObject().apply {
                put("id", l.id); put("name", l.name); put("visible", l.visible); put("opacity", l.opacity.toDouble())
                put("blend", l.blend.name); put("alphaLock", l.alphaLock); put("clipping", l.clipping)
                put("reference", l.isReference); put("locked", l.locked)
                put("private", l.isPrivate); put("parent", l.parentId)
                if (l.isGroup) { put("group", true); put("expanded", l.expanded); put("passThrough", l.passThrough) }
                l.mask?.let { put("mask", it.id) }
                l.text?.let { put("text", it.toJson()) }
                if (l.holdFrames > 0) put("hold", l.holdFrames)
            })
        })
        val g = doc.guides
        put("guides", JSONObject().apply {
            put("enabled", g.enabled); put("type", g.type.name); put("grid", g.gridSize.toDouble()); put("color", g.color)
            put("opacity", g.opacity.toDouble()); put("thickness", g.thickness.toDouble()); put("assist", g.assist)
            put("symmetry", g.symmetry.name); put("rotational", g.rotational); put("segments", g.radialSegments)
            put("cx", if (g.cx.isNaN()) -1.0 else g.cx.toDouble()); put("cy", if (g.cy.isNaN()) -1.0 else g.cy.toDouble())
            put("rotation", g.rotationDeg.toDouble())
            put("vps", JSONArray().apply { g.vanishingPoints.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })
        })
        val a = doc.animation
        put("animation", JSONObject().apply {
            put("enabled", a.enabled); put("fps", a.fps); put("onion", a.onionFrames); put("onionOpacity", a.onionOpacity.toDouble())
            put("loop", a.loop.name); put("colorOnion", a.colorOnion); put("firstBg", a.firstFrameBackground)
            put("lastFg", a.lastFrameForeground); put("blendPrimary", a.blendPrimary)
            put("onionBefore", a.onionBefore.toLong() and 0xFFFFFFFFL); put("onionAfter", a.onionAfter.toLong() and 0xFFFFFFFFL)
        })
    }

    /** Main thread: copies only what changed. */
    fun snapshot(doc: Document, thumbnail: Bitmap): SaveSnapshot {
        // pixel layers and masks each have their own PNG; groups have none
        val bitmaps = doc.layers.filter { !it.isGroup } + doc.layers.mapNotNull { it.mask }
        val dirty = bitmaps.filter { it.savedVersion != it.version || !File(dir(doc.id), "L_${it.id}.png").exists() }
            .map { it.id to it.bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        bitmaps.forEach { it.savedVersion = it.version }
        return SaveSnapshot(doc.id, manifestOf(doc), dirty, bitmaps.map { it.id }.toSet(), thumbnail)
    }

    /** Background thread. */
    fun write(s: SaveSnapshot) {
        val d = dir(s.id).apply { mkdirs() }
        for ((id, bmp) in s.dirtyLayers) {
            val tmp = File(d, "L_$id.png.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(File(d, "L_$id.png").also { it.delete() })
            bmp.recycle()
        }
        File(d, "thumb.jpg").outputStream().use { s.thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(s.manifest.toString())
        File(d, "project.json").delete()
        tmp.renameTo(File(d, "project.json"))
        // remove files of deleted layers
        d.listFiles()?.forEach { f ->
            val n = f.name
            if (n.startsWith("L_") && n.endsWith(".png")) {
                val id = n.removePrefix("L_").removeSuffix(".png").toLongOrNull()
                if (id != null && id !in s.keepLayerIds) f.delete()
            }
        }
    }

    fun load(id: String): Document? {
        val d = dir(id)
        val mf = File(d, "project.json")
        if (!mf.exists()) return null
        return runCatching {
            val o = JSONObject(mf.readText())
            val doc = Document(id, o.getInt("width"), o.getInt("height"))
            doc.name = o.optString("name", "무제 작품")
            doc.backgroundColor = o.optLong("background", 0xFFFFFFFFL).toInt()
            doc.backgroundVisible = o.optBoolean("backgroundVisible", true)
            doc.strokeCount = o.optInt("strokes"); doc.timeSpentMs = o.optLong("timeSpent")
            doc.createdAt = o.optLong("created", System.currentTimeMillis())
            doc.timelapseEnabled = o.optBoolean("timelapse", true)
            val arr = o.getJSONArray("layers")
            val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = true }
            fun pixels(lid: Long, fill: Int = 0): Bitmap {
                val f = File(d, "L_$lid.png")
                var bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, opts) else null
                if (bmp == null || bmp.width != doc.width || bmp.height != doc.height || !bmp.isMutable) {
                    val nb = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                    if (bmp != null) android.graphics.Canvas(nb).drawBitmap(bmp, 0f, 0f, null) else if (fill != 0) nb.eraseColor(fill)
                    bmp = nb
                }
                return bmp
            }
            for (i in 0 until arr.length()) {
                val lo = arr.getJSONObject(i)
                val lid = lo.getLong("id")
                val isGroup = lo.optBoolean("group")
                val bmp = if (isGroup) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else pixels(lid)
                val l = Layer(bmp, lo.optString("name", "레이어"), lid, isGroup)
                l.visible = lo.optBoolean("visible", true); l.opacity = lo.optDouble("opacity", 1.0).toFloat()
                l.blend = LayerBlend.fromName(lo.optString("blend")); l.alphaLock = lo.optBoolean("alphaLock")
                l.clipping = lo.optBoolean("clipping"); l.isReference = lo.optBoolean("reference"); l.locked = lo.optBoolean("locked")
                l.isPrivate = lo.optBoolean("private"); l.parentId = lo.optLong("parent", 0L)
                l.expanded = lo.optBoolean("expanded", true); l.passThrough = lo.optBoolean("passThrough", true)
                l.holdFrames = lo.optInt("hold", 0)
                lo.optJSONObject("text")?.let { l.text = com.prosketch.app.engine.TextSpec.fromJson(it) }
                if (lo.has("mask")) {
                    val mid = lo.getLong("mask")
                    l.mask = Layer(pixels(mid, android.graphics.Color.WHITE), "마스크", mid).also { it.savedVersion = it.version }
                }
                l.savedVersion = l.version
                doc.layers.add(l)
            }
            if (doc.layers.none { !it.isGroup }) doc.layers.add(Layer.blank(doc.width, doc.height, "레이어 1"))
            doc.normalize()
            doc.activeIndex = o.optInt("active", doc.layers.size - 1).coerceIn(0, doc.layers.size - 1)
            o.optJSONObject("guides")?.let { g ->
                val c = doc.guides
                c.enabled = g.optBoolean("enabled"); c.type = runCatching { GuideType.valueOf(g.optString("type")) }.getOrDefault(GuideType.GRID)
                c.gridSize = g.optDouble("grid", 120.0).toFloat(); c.color = g.optInt("color", c.color)
                c.opacity = g.optDouble("opacity", 0.65).toFloat(); c.thickness = g.optDouble("thickness", 1.5).toFloat()
                c.assist = g.optBoolean("assist"); c.symmetry = runCatching { SymmetryType.valueOf(g.optString("symmetry")) }.getOrDefault(SymmetryType.VERTICAL)
                c.rotational = g.optBoolean("rotational"); c.radialSegments = g.optInt("segments", 8)
                val cx = g.optDouble("cx", -1.0); val cy = g.optDouble("cy", -1.0)
                c.cx = if (cx < 0) Float.NaN else cx.toFloat(); c.cy = if (cy < 0) Float.NaN else cy.toFloat()
                c.rotationDeg = g.optDouble("rotation", 0.0).toFloat()
                val vps = g.optJSONArray("vps")
                if (vps != null) for (i in 0 until vps.length()) {
                    val p = vps.getJSONArray(i); c.vanishingPoints.add(PointF(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
                }
            }
            o.optJSONObject("animation")?.let { a ->
                val c = doc.animation
                c.enabled = a.optBoolean("enabled"); c.fps = a.optInt("fps", 12); c.onionFrames = a.optInt("onion", 2)
                c.onionOpacity = a.optDouble("onionOpacity", 0.45).toFloat()
                c.loop = runCatching { AnimLoop.valueOf(a.optString("loop")) }.getOrDefault(AnimLoop.LOOP)
                c.colorOnion = a.optBoolean("colorOnion", true); c.firstFrameBackground = a.optBoolean("firstBg")
                c.lastFrameForeground = a.optBoolean("lastFg"); c.blendPrimary = a.optBoolean("blendPrimary")
                c.onionBefore = a.optLong("onionBefore", 0xFFE5484DL).toInt(); c.onionAfter = a.optLong("onionAfter", 0xFF30A46CL).toInt()
            }
            doc
        }.getOrNull()
    }

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun rename(id: String, name: String) {
        val mf = File(dir(id), "project.json")
        if (!mf.exists()) return
        val o = JSONObject(mf.readText()); o.put("name", name)
        mf.writeText(o.toString())
    }

    fun duplicate(id: String): String {
        val nid = newId()
        dir(id).copyRecursively(dir(nid), overwrite = true)
        val mf = File(dir(nid), "project.json")
        val o = JSONObject(mf.readText())
        o.put("name", o.optString("name") + " 복사본"); o.put("modified", System.currentTimeMillis())
        mf.writeText(o.toString())
        return nid
    }

    /** .prosketch = zip of the project directory. */
    fun exportZip(id: String, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            dir(id).walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                val rel = f.relativeTo(dir(id)).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun importZip(input: InputStream): String? {
        val nid = newId()
        val d = dir(nid).apply { mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(d, e.name).canonicalFile
                if (!target.path.startsWith(d.canonicalPath)) continue   // zip-slip guard
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        return if (File(d, "project.json").exists()) {
            val mf = File(d, "project.json"); val o = JSONObject(mf.readText())
            o.put("modified", System.currentTimeMillis()); mf.writeText(o.toString())
            nid
        } else { d.deleteRecursively(); null }
    }
}
