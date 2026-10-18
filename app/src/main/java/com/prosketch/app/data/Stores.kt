package com.prosketch.app.data

import com.prosketch.app.L

import android.content.Context
import android.content.SharedPreferences
import com.prosketch.app.engine.Brush
import com.prosketch.app.engine.DefaultBrushes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class FingerMode(val label: String) { AUTO(L.t("S펜 사용 시 손가락은 이동만")), PAINT(L.t("손가락으로 그리기")), NONE(L.t("손가락으로 그리지 않기")) }
enum class PenButtonAction(val label: String) { ERASER(L.t("누르는 동안 지우개")), EYEDROPPER(L.t("스포이드")), UNDO(L.t("실행 취소")), QUICKMENU("QuickMenu"), NONE(L.t("동작 없음")) }

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("prosketch", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    var fingerMode: FingerMode
        get() = runCatching { FingerMode.valueOf(sp.getString("fingerMode", FingerMode.AUTO.name)!!) }.getOrDefault(FingerMode.AUTO)
        set(v) = put { putString("fingerMode", v.name) }
    var penButton: PenButtonAction
        get() = runCatching { PenButtonAction.valueOf(sp.getString("penButton", PenButtonAction.ERASER.name)!!) }.getOrDefault(PenButtonAction.ERASER)
        set(v) = put { putString("penButton", v.name) }
    var brushCursor: Boolean get() = b("brushCursor", true); set(v) = put { putBoolean("brushCursor", v) }
    var screenRelativeBrush: Boolean get() = b("screenRelative", false); set(v) = put { putBoolean("screenRelative", v) }
    var rightHanded: Boolean get() = b("rightHanded", false); set(v) = put { putBoolean("rightHanded", v) }
    var twoFingerUndo: Boolean get() = b("twoFingerUndo", true); set(v) = put { putBoolean("twoFingerUndo", v) }
    var threeFingerRedo: Boolean get() = b("threeFingerRedo", true); set(v) = put { putBoolean("threeFingerRedo", v) }
    var holdEyedropper: Boolean get() = b("holdEyedropper", true); set(v) = put { putBoolean("holdEyedropper", v) }
    var holdEyedropperDelay: Float get() = f("holdEyedropperDelay", 0.4f); set(v) = put { putFloat("holdEyedropperDelay", v) }
    var scrubClear: Boolean get() = b("scrubClear", true); set(v) = put { putBoolean("scrubClear", v) }
    var threeSwipeMenu: Boolean get() = b("threeSwipeMenu", true); set(v) = put { putBoolean("threeSwipeMenu", v) }
    var fourTapFullscreen: Boolean get() = b("fourTapFullscreen", true); set(v) = put { putBoolean("fourTapFullscreen", v) }
    var quickShape: Boolean get() = b("quickShape", true); set(v) = put { putBoolean("quickShape", v) }
    var shapeMagnet: Boolean get() = b("shapeMagnet", true); set(v) = put { putBoolean("shapeMagnet", v) }
    var quickShapeDelay: Float get() = f("quickShapeDelay", 0.55f); set(v) = put { putFloat("quickShapeDelay", v) }
    var rapidUndoDelay: Float get() = f("rapidUndoDelay", 0.65f); set(v) = put { putFloat("rapidUndoDelay", v) }
    var rotateWithPinch: Boolean get() = b("rotateWithPinch", true); set(v) = put { putBoolean("rotateWithPinch", v) }
    var selectionMaskOpacity: Float get() = f("selMaskOpacity", 1f); set(v) = put { putFloat("selMaskOpacity", v) }
    var pressureSmoothing: Float get() = f("pressureSmoothing", 0.15f); set(v) = put { putFloat("pressureSmoothing", v) }
    var sidebarOffset: Float get() = f("sidebarOffset", 0.42f); set(v) = put { putFloat("sidebarOffset", v) }
    /** 0 = system, 1 = 한국어, 2 = English (read by [com.prosketch.app.L] before anything else). */
    var language: Int get() = sp.getInt("language", 0); set(v) = put { putInt("language", v) }
    var onboarded: Boolean get() = b("onboarded", false); set(v) = put { putBoolean("onboarded", v) }
    var colorDropThreshold: Float get() = f("colorDropThreshold", 0.3f); set(v) = put { putFloat("colorDropThreshold", v) }
    /** ColorDrop gap closing in canvas pixels (0 = off): fills line art with small gaps without leaking. */
    var colorDropGap: Int get() = sp.getInt("colorDropGap", 0); set(v) = put { putInt("colorDropGap", v) }
    /** QuickMenu trigger: 0 off, 1 finger touch-and-hold (instead of the eyedropper), 2 S Pen button. */
    var quickMenuGesture: Int get() = sp.getInt("quickMenuGesture", 0); set(v) = put { putInt("quickMenuGesture", v) }
    var quickMenuActions: String get() = sp.getString("quickMenuActions", "")!!; set(v) = put { putString("quickMenuActions", v) }
    var autoSelectThreshold: Float get() = f("autoSelectThreshold", 0.25f); set(v) = put { putFloat("autoSelectThreshold", v) }
    var lastColorTab: Int get() = sp.getInt("lastColorTab", 0); set(v) = put { putInt("lastColorTab", v) }
    var harmonyMode: Int get() = sp.getInt("harmonyMode", 0); set(v) = put { putInt("harmonyMode", v) }

    /** Pressure curve as a cubic Bézier from (0,0) to (1,1) with two control points. */
    var pressureCurve: FloatArray
        get() = floatArrayOf(f("pc_x1", 0.33f), f("pc_y1", 0.33f), f("pc_x2", 0.67f), f("pc_y2", 0.67f))
        set(v) = put { putFloat("pc_x1", v[0]); putFloat("pc_y1", v[1]); putFloat("pc_x2", v[2]); putFloat("pc_y2", v[3]) }

    var recentColors: List<Int>
        get() = sp.getString("recentColors", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        set(v) = put { putString("recentColors", v.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }) }

    var toolState: JSONObject
        get() = runCatching { JSONObject(sp.getString("toolState", "{}")!!) }.getOrDefault(JSONObject())
        set(v) = put { putString("toolState", v.toString()) }
}

/** Maps raw pen pressure through the user's Bézier pressure curve (with a LUT). */
class PressureCurve(ctrl: FloatArray) {
    private val lut = FloatArray(257)

    init {
        val (x1, y1, x2, y2) = ctrl.toList()
        // sample the bezier densely then invert x→y
        val n = 1024
        val xs = FloatArray(n + 1); val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat(); val it = 1 - t
            xs[i] = 3 * it * it * t * x1 + 3 * it * t * t * x2 + t * t * t
            ys[i] = 3 * it * it * t * y1 + 3 * it * t * t * y2 + t * t * t
        }
        var j = 0
        for (k in 0..256) {
            val x = k / 256f
            while (j < n && xs[j + 1] < x) j++
            val x0 = xs[j]; val x1b = xs[minOf(j + 1, n)]
            val f = if (x1b > x0) ((x - x0) / (x1b - x0)).coerceIn(0f, 1f) else 0f
            lut[k] = (ys[j] + (ys[minOf(j + 1, n)] - ys[j]) * f).coerceIn(0f, 1f)
        }
    }

    fun map(p: Float): Float {
        val x = p.coerceIn(0f, 1f) * 256f
        val i = x.toInt().coerceAtMost(255)
        val f = x - i
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }
}

data class Palette(val id: String, var name: String, val colors: MutableList<Int>)

class PaletteStore(ctx: Context) {
    private val file = File(ctx.filesDir, "palettes.json")
    val palettes = mutableListOf<Palette>()
    var activeId: String = "default"

    init { load() }

    private fun defaults(): List<Palette> = listOf(
        Palette("default", L.t("기본"), mutableListOf(
            0xFF000000, 0xFF3A3A3A, 0xFF7A7A7A, 0xFFBDBDBD, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF7CB342, 0xFF00897B,
            0xFF039BE5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41, 0xFFFFCDD2, 0xFFFFE0B2, 0xFFFFF9C4, 0xFFC8E6C9, 0xFFB3E5FC,
        ).map { it.toInt() }.toMutableList()),
        Palette("skin", L.t("피부 톤"), mutableListOf(
            0xFFFFE6D5, 0xFFF9D2B6, 0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFFA0662F, 0xFF8D5524, 0xFF6B3E1F, 0xFF4A2A14, 0xFF2E1A0E,
            0xFFF4B6A8, 0xFFE8968A, 0xFFD87A6F, 0xFFB85C55, 0xFF8E4440,
        ).map { it.toInt() }.toMutableList()),
        Palette("pastel", L.t("파스텔"), mutableListOf(
            0xFFFFADAD, 0xFFFFD6A5, 0xFFFDFFB6, 0xFFCAFFBF, 0xFF9BF6FF, 0xFFA0C4FF, 0xFFBDB2FF, 0xFFFFC6FF, 0xFFFFFFFC, 0xFFE4C1F9,
        ).map { it.toInt() }.toMutableList()),
        Palette("sunset", L.t("노을"), mutableListOf(
            0xFF03071E, 0xFF370617, 0xFF6A040F, 0xFF9D0208, 0xFFD00000, 0xFFDC2F02, 0xFFE85D04, 0xFFF48C06, 0xFFFAA307, 0xFFFFBA08,
        ).map { it.toInt() }.toMutableList()),
        Palette("forest", L.t("숲"), mutableListOf(
            0xFF081C15, 0xFF1B4332, 0xFF2D6A4F, 0xFF40916C, 0xFF52B788, 0xFF74C69D, 0xFF95D5B2, 0xFFB7E4C7, 0xFFD8F3DC, 0xFF6F4E37,
        ).map { it.toInt() }.toMutableList()),
        Palette("gray", L.t("회색조"), (0..10).map { android.graphics.Color.rgb(it * 25, it * 25, it * 25) }.toMutableList()),
    )

    private fun load() {
        palettes.clear()
        if (file.exists()) runCatching {
            val o = JSONObject(file.readText())
            activeId = o.optString("active", "default")
            val arr = o.getJSONArray("palettes")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val cs = p.getJSONArray("colors")
                palettes.add(Palette(p.getString("id"), p.getString("name"), MutableList(cs.length()) { cs.getLong(it).toInt() }))
            }
        }
        if (palettes.isEmpty()) palettes.addAll(defaults())
    }

    fun save() {
        val arr = JSONArray()
        for (p in palettes) arr.put(JSONObject().apply {
            put("id", p.id); put("name", p.name)
            put("colors", JSONArray().apply { p.colors.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
        })
        file.writeText(JSONObject().apply { put("active", activeId); put("palettes", arr) }.toString())
    }

    val active: Palette get() = palettes.firstOrNull { it.id == activeId } ?: palettes.first()
}

/** Built-in brushes + user overrides/customs, persisted as JSON. */
class BrushStore(ctx: Context) {
    private val file = File(ctx.filesDir, "brushes.json")
    val customDir = File(ctx.filesDir, "brush_assets").apply { mkdirs() }
    private val builtIns = DefaultBrushes.all().associateBy { it.id }
    private val overrides = LinkedHashMap<String, Brush>()   // edited built-ins and custom brushes
    val customOrder = mutableListOf<String>()
    val recent = mutableListOf<String>()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("brushes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val b = Brush.fromJson(arr.getJSONObject(i))
                b.builtIn = builtIns.containsKey(b.id)
                overrides[b.id] = b
            }
            val ord = o.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until ord.length()) customOrder.add(ord.getString(i))
            val rec = o.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until rec.length()) recent.add(rec.getString(i))
        }
    }

    fun save() {
        val arr = JSONArray()
        overrides.values.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().apply {
            put("brushes", arr)
            put("custom", JSONArray(customOrder))
            put("recent", JSONArray(recent))
        }.toString())
    }

    fun get(id: String): Brush? = overrides[id]?.copy() ?: builtIns[id]?.copy()

    fun categories(): List<String> = buildList {
        if (recent.isNotEmpty()) add(RECENT)
        if (customOrder.isNotEmpty()) add(DefaultBrushes.CUSTOM)
        addAll(DefaultBrushes.categories)
    }

    fun inCategory(cat: String): List<Brush> = when (cat) {
        RECENT -> recent.mapNotNull { get(it) }
        DefaultBrushes.CUSTOM -> customOrder.mapNotNull { get(it) }
        else -> DefaultBrushes.all().filter { it.category == cat }.map { get(it.id) ?: it }
    }

    fun all(): List<Brush> = DefaultBrushes.all().map { get(it.id) ?: it } + customOrder.mapNotNull { get(it) }

    fun update(b: Brush) {
        overrides[b.id] = b.copy()
        if (!builtIns.containsKey(b.id) && b.id !in customOrder) customOrder.add(0, b.id)
        save()
    }

    fun isModified(id: String) = builtIns.containsKey(id) && overrides.containsKey(id)

    fun reset(id: String) { if (builtIns.containsKey(id)) { overrides.remove(id); save() } }

    fun delete(id: String) {
        if (builtIns.containsKey(id)) return
        overrides.remove(id); customOrder.remove(id); recent.remove(id); save()
    }

    fun duplicate(b: Brush): Brush {
        val nb = b.duplicate("u_" + System.currentTimeMillis(), b.name + L.t(" 복사본")).apply { category = DefaultBrushes.CUSTOM }
        update(nb)
        return nb
    }

    fun markRecent(id: String) {
        recent.remove(id); recent.add(0, id)
        while (recent.size > 8) recent.removeAt(recent.size - 1)
        save()
    }

    companion object { const val RECENT = "최근 사용" }
}
