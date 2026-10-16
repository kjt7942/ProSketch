package com.prosketch.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.engine.BitmapDabTarget
import com.prosketch.app.engine.Brush
import com.prosketch.app.engine.BrushStroke
import com.prosketch.app.engine.BrushTextures
import com.prosketch.app.engine.GrainType
import com.prosketch.app.engine.InputPoint
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.engine.StrokeKind
import com.prosketch.app.engine.TipShape
import com.prosketch.app.ui.common.IconBtn
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.SwitchRow
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Renders brush stroke previews (S-curve with a pressure swell). */
object BrushPreview {
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 80
    }

    fun key(b: Brush, w: Int, h: Int) = "${b.toJson()}|$w|$h"

    @Synchronized fun cached(b: Brush, w: Int, h: Int): Bitmap? = cache[key(b, w, h)]

    fun render(b: Brush, w: Int, h: Int, color: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val k = key(b, w, h)
        synchronized(this) { cache[k]?.let { return it } }
        val buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val size = b.sizeFor(b.defaultSize.coerceAtLeast(0.35f)).coerceIn(h * 0.1f, h * 0.36f)
        val stroke = BrushStroke(b, StrokeKind.PAINT, color, size, b.opacityFor(1f), BitmapDabTarget(buffer), listOf(android.graphics.Matrix()), true, 1f, null, 7)
        val n = 90
        var t0 = 0L
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * (0.08f + 0.84f * t)
            val y = h / 2f + sin(t * 2 * PI.toFloat()) * h * 0.22f
            val p = (sin(t * PI.toFloat()) * 0.85f + 0.15f)
            val ip = InputPoint(x, y, p, 0.4f, 0f, t0)
            t0 += 8
            if (i == 0) stroke.begin(ip) else stroke.add(ip)
        }
        stroke.end()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(buffer, 0f, 0f, Paint().apply { alpha = (stroke.compositeOpacity * 255).toInt(); blendMode = BlendMode.SRC_OVER })
        buffer.recycle()
        synchronized(this) { cache[k] = out }
        return out
    }
}

@Composable
fun BrushPreviewImage(b: Brush, modifier: Modifier, w: Int = 360, h: Int = 90) {
    val bmp by produceState(BrushPreview.cached(b, w, h), b.toJson().toString()) {
        value = withContext(Dispatchers.Default) { BrushPreview.render(b, w, h) }
    }
    Box(modifier) { bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrushLibraryPanel(ed: Editor, modifier: Modifier = Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    val cats = remember(refresh) { ed.brushes.categories() }
    val currentId = ed.toolBrush[ed.tool]
    var cat by remember {
        mutableStateOf(ed.brushes.get(currentId ?: "")?.category?.takeIf { it in cats } ?: cats.firstOrNull { it != com.prosketch.app.data.BrushStore.RECENT } ?: cats.first())
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    PanelBox(modifier, title = "브러시 라이브러리 · ${ed.tool.label}", trailing = {
        IconBtn(Icons.Filled.Search, "검색", selected = searching) { searching = !searching; query = "" }
        IconBtn(Icons.Filled.Add, "새 브러시") {
            val nb = ed.brushes.duplicate(ed.currentBrush)
            ed.chooseBrush(nb.id); refresh++
            ed.studioBrushId = nb.id; ed.panel = Panel.BRUSH_STUDIO
        }
    }) {
        if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("브러시 검색") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Row(Modifier.height(470.dp)) {
            if (!searching) {
                Column(Modifier.width(130.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    cats.forEach { c ->
                        val sel = c == cat
                        Text(c, color = if (sel) Color.White else UI.text, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) UI.accent else Color.Transparent)
                                .combinedClickable(onClick = { cat = c }).padding(horizontal = 10.dp, vertical = 10.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            val list = remember(cat, refresh, query, searching) {
                if (searching) ed.brushes.all().filter { query.isBlank() || it.name.contains(query, true) } else ed.brushes.inCategory(cat)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                list.forEach { b ->
                    val sel = b.id == currentId
                    Box {
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) UI.accentDim else UI.panel2)
                                .combinedClickable(
                                    onClick = { if (sel) { ed.studioBrushId = b.id; ed.panel = Panel.BRUSH_STUDIO } else { ed.chooseBrush(b.id); refresh++ } },
                                    onLongClick = { menuFor = b.id },
                                ).padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.name, color = UI.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (ed.brushes.isModified(b.id)) Text("수정됨", color = UI.text2, fontSize = 10.sp)
                                if (sel) Icon(Icons.Filled.Tune, "스튜디오", tint = UI.accent, modifier = Modifier.size(16.dp).padding(start = 4.dp))
                            }
                            BrushPreviewImage(b, Modifier.fillMaxWidth().height(58.dp))
                        }
                        DropdownMenu(menuFor == b.id, { menuFor = null }) {
                            DropdownMenuItem(text = { Text("브러시 스튜디오") }, onClick = { menuFor = null; ed.chooseBrush(b.id); ed.studioBrushId = b.id; ed.panel = Panel.BRUSH_STUDIO })
                            DropdownMenuItem(text = { Text("복제") }, onClick = { menuFor = null; ed.brushes.duplicate(b); refresh++ })
                            if (ed.brushes.isModified(b.id)) DropdownMenuItem(text = { Text("브러시 초기화") }, onClick = { menuFor = null; ed.brushes.reset(b.id); refresh++ })
                            if (!b.builtIn) DropdownMenuItem(text = { Text("삭제") }, onClick = { menuFor = null; ed.brushes.delete(b.id); refresh++ })
                        }
                    }
                }
                if (list.isEmpty()) Text("브러시가 없습니다", color = UI.text2, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private class P(val label: String, val get: (Brush) -> Float, val set: (Brush, Float) -> Unit, val range: ClosedFloatingPointRange<Float> = 0f..1f, val fmt: (Float) -> String = { "${(it * 100).roundToInt()}%" })

private fun pct(label: String, g: (Brush) -> Float, s: (Brush, Float) -> Unit) = P(label, g, s)
private fun signed(label: String, g: (Brush) -> Float, s: (Brush, Float) -> Unit) = P(label, g, s, -1f..1f) { "${(it * 100).roundToInt()}%" }

private val sections: List<Pair<String, List<P>>> = listOf(
    "획 경로" to listOf(
        P("간격", { it.spacing }, { b, v -> b.spacing = v }, 0.01f..1.5f),
        pct("간격 지터", { it.spacingJitter }, { b, v -> b.spacingJitter = v }),
        P("측면 지터", { it.jitterLateral }, { b, v -> b.jitterLateral = v }, 0f..2f),
        P("선형 지터", { it.jitterLinear }, { b, v -> b.jitterLinear = v }, 0f..2f),
        pct("감소", { it.fallOff }, { b, v -> b.fallOff = v }),
    ),
    "안정화" to listOf(
        pct("StreamLine 양", { it.streamline }, { b, v -> b.streamline = v }),
        pct("안정화 양", { it.stabilization }, { b, v -> b.stabilization = v }),
    ),
    "테이퍼" to listOf(
        pct("시작 테이퍼", { it.taperStart }, { b, v -> b.taperStart = v }),
        pct("끝 테이퍼", { it.taperEnd }, { b, v -> b.taperEnd = v }),
        pct("팁 크기", { it.taperSize }, { b, v -> b.taperSize = v }),
        pct("팁 불투명도", { it.taperOpacity }, { b, v -> b.taperOpacity = v }),
    ),
    "모양" to listOf(
        pct("경도", { it.tipHardness }, { b, v -> b.tipHardness = v }),
        P("원형률", { it.roundness }, { b, v -> b.roundness = v }, 0.03f..1f),
        P("각도", { it.tipAngle }, { b, v -> b.tipAngle = v }, 0f..360f) { "${it.roundToInt()}°" },
        signed("회전 (획 따라가기)", { it.rotation }, { b, v -> b.rotation = v }),
        pct("분산", { it.scatter }, { b, v -> b.scatter = v }),
        P("개수", { it.count.toFloat() }, { b, v -> b.count = v.roundToInt() }, 1f..16f) { "${it.roundToInt()}" },
        pct("개수 지터", { it.countJitter }, { b, v -> b.countJitter = v }),
        pct("압력 원형률", { it.pressureRoundness }, { b, v -> b.pressureRoundness = v }),
        pct("기울기 원형률", { it.tiltRoundness }, { b, v -> b.tiltRoundness = v }),
    ),
    "그레인" to listOf(
        P("크기", { it.grainScale }, { b, v -> b.grainScale = v }, 0.2f..4f) { "${(it * 100).roundToInt()}%" },
        pct("깊이", { it.grainDepth }, { b, v -> b.grainDepth = v }),
        pct("대비", { it.grainContrast }, { b, v -> b.grainContrast = v }),
    ),
    "렌더링" to listOf(
        pct("흐름", { it.flow }, { b, v -> b.flow = v }),
    ),
    "습식 혼합" to listOf(
        pct("희석", { it.dilution }, { b, v -> b.dilution = v }),
        pct("충전", { it.charge }, { b, v -> b.charge = v }),
        pct("당기기", { it.pull }, { b, v -> b.pull = v }),
        pct("문지르기 당김", { it.smudgePull }, { b, v -> b.smudgePull = v }),
    ),
    "색상 역학" to listOf(
        pct("스탬프 색조 지터", { it.stampHue }, { b, v -> b.stampHue = v }),
        pct("스탬프 채도 지터", { it.stampSat }, { b, v -> b.stampSat = v }),
        pct("스탬프 밝기 지터", { it.stampBri }, { b, v -> b.stampBri = v }),
        pct("획 색조 지터", { it.strokeHue }, { b, v -> b.strokeHue = v }),
        pct("획 채도 지터", { it.strokeSat }, { b, v -> b.strokeSat = v }),
        pct("획 밝기 지터", { it.strokeBri }, { b, v -> b.strokeBri = v }),
    ),
    "역학" to listOf(
        signed("속도 → 크기", { it.speedSize }, { b, v -> b.speedSize = v }),
        signed("속도 → 불투명도", { it.speedOpacity }, { b, v -> b.speedOpacity = v }),
        pct("크기 지터", { it.jitterSize }, { b, v -> b.jitterSize = v }),
        pct("불투명도 지터", { it.jitterOpacity }, { b, v -> b.jitterOpacity = v }),
    ),
    "S펜" to listOf(
        pct("압력 → 크기", { it.pressureSize }, { b, v -> b.pressureSize = v }),
        pct("압력 → 불투명도", { it.pressureOpacity }, { b, v -> b.pressureOpacity = v }),
        pct("압력 → 흐름", { it.pressureFlow }, { b, v -> b.pressureFlow = v }),
        pct("기울기 → 크기", { it.tiltSize }, { b, v -> b.tiltSize = v }),
        pct("기울기 → 불투명도", { it.tiltOpacity }, { b, v -> b.tiltOpacity = v }),
        P("기울기 시작 각도", { it.tiltAngleDeg }, { b, v -> b.tiltAngleDeg = v }, 0f..85f) { "${it.roundToInt()}°" },
    ),
    "속성" to listOf(
        P("최대 크기", { it.sizeMax }, { b, v -> b.sizeMax = v.coerceAtLeast(b.sizeMin + 1) }, 2f..1000f) { "${it.roundToInt()}px" },
        P("최소 크기", { it.sizeMin }, { b, v -> b.sizeMin = v.coerceAtMost(b.sizeMax - 1) }, 0.5f..200f) { "${"%.1f".format(it)}px" },
        pct("최대 불투명도", { it.opacityMax }, { b, v -> b.opacityMax = v }),
        pct("최소 불투명도", { it.opacityMin }, { b, v -> b.opacityMin = v }),
    ),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BrushStudioPanel(ed: Editor, modifier: Modifier = Modifier) {
    val id = ed.studioBrushId ?: return
    val ctx = LocalContext.current
    var b by remember(id) { mutableStateOf(ed.brushes.get(id) ?: ed.currentBrush) }
    var section by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    fun edit(f: (Brush) -> Unit) { val c = b.copy(); f(c); b = c; rev++ }
    var importTarget by remember { mutableStateOf(0) } // 1 tip, 2 grain
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val f = File(ed.brushes.customDir, "asset_${System.currentTimeMillis()}.png")
            ctx.contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            if (importTarget == 1) edit { it.tip = TipShape.CUSTOM; it.customTip = f.absolutePath }
            else edit { it.grain = GrainType.CUSTOM; it.customGrain = f.absolutePath }
        }
    }

    PanelBox(modifier, title = "브러시 스튜디오", trailing = {
        TextBtn("취소") { ed.panel = Panel.BRUSHES }
        Spacer(Modifier.width(6.dp))
        TextBtn("완료", selected = true) {
            ed.brushes.update(b); ed.chooseBrush(b.id); ed.panel = null; ed.hud("브러시 저장됨: ${b.name}")
        }
    }) {
        Row(Modifier.height(520.dp)) {
            Column(Modifier.width(120.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                (sections.map { it.first } + "브러시 정보").forEachIndexed { i, s ->
                    val sel = i == section
                    Text(s, color = if (sel) Color.White else UI.text, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) UI.accent else Color.Transparent)
                            .combinedClickable(onClick = { section = i }).padding(horizontal = 10.dp, vertical = 9.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (section < sections.size) {
                    val (name, params) = sections[section]
                    when (name) {
                        "모양" -> {
                            Text("모양 소스", color = UI.text2, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                TipShape.entries.filter { it != TipShape.CUSTOM }.forEach { t -> TextBtn(t.label, b.tip == t) { edit { it.tip = t } } }
                                TextBtn("이미지 가져오기…", b.tip == TipShape.CUSTOM) { importTarget = 1; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            }
                            TipPreview(b, rev)
                            SwitchRow("무작위", b.randomized) { v -> edit { it.randomized = v } }
                            SwitchRow("X 뒤집기", b.flipX) { v -> edit { it.flipX = v } }
                            SwitchRow("Y 뒤집기", b.flipY) { v -> edit { it.flipY = v } }
                            SwitchRow("S펜 방위각 따라 회전", b.azimuth) { v -> edit { it.azimuth = v } }
                        }
                        "그레인" -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                GrainType.entries.filter { it != GrainType.CUSTOM }.forEach { t -> TextBtn(t.label, b.grain == t) { edit { it.grain = t } } }
                                TextBtn("이미지 가져오기…", b.grain == GrainType.CUSTOM) { importTarget = 2; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            }
                        }
                        "렌더링" -> {
                            SwitchRow("글레이즈 (획 내 불투명도 유지)", b.glaze, "끄면 겹칠수록 진해지는 혼합 렌더링") { v -> edit { it.glaze = v } }
                            Text("혼합 모드", color = UI.text2, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                LayerBlend.entries.forEach { m -> TextBtn(m.label, b.strokeBlend == m) { edit { it.strokeBlend = m } } }
                            }
                        }
                        "테이퍼" -> SwitchRow("손가락 획에도 테이퍼 적용", b.taperFinger) { v -> edit { it.taperFinger = v } }
                    }
                    params.forEach { p ->
                        val v = p.get(b)
                        LabeledSlider(p.label, v, p.range, p.fmt(v), { nv -> edit { p.set(it, nv) } })
                    }
                } else {
                    var name by remember(id) { mutableStateOf(b.name) }
                    OutlinedTextField(name, { name = it; edit { br -> br.name = it } }, label = { Text("브러시 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("카테고리: ${b.category}", color = UI.text2, fontSize = 12.sp)
                    if (b.builtIn) Text("기본 브러시를 수정하면 '수정됨'으로 저장되며, 라이브러리에서 길게 눌러 초기화할 수 있습니다.", color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            DrawingPad(b, rev, Modifier.width(240.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun TipPreview(b: Brush, rev: Int) {
    val bmp = remember(b.tip, b.tipHardness, b.customTip, rev) { BrushTextures.tip(b).base }
    Box(Modifier.size(90.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp)) {
        Image(bmp.asImageBitmap(), null, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black), modifier = Modifier.fillMaxSize())
    }
}

/** Scribble pad that renders with the brush being edited. */
@Composable
private fun DrawingPad(b: Brush, rev: Int, modifier: Modifier) {
    val w = 480; val h = 1000
    val bmp = remember { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val buffer = remember { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    var tick by remember { mutableIntStateOf(0) }
    val brushRef = remember { arrayOf(b) }
    brushRef[0] = b
    LaunchedEffect(rev) {
        // auto-preview S curve whenever parameters change
        bmp.eraseColor(0)
        val pv = BrushPreview.render(b, w, 220)
        Canvas(bmp).drawBitmap(pv, 0f, 30f, null)
        tick++
    }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF111113))) {
        Text("드로잉 패드", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sx = w / size.width.toFloat(); val sy = h / size.height.toFloat()
                    buffer.eraseColor(0)
                    val br = brushRef[0]
                    val stroke = BrushStroke(br, StrokeKind.PAINT, 0xFFFFFFFF.toInt(), br.sizeFor(br.defaultSize) * 0.8f, br.opacityFor(1f),
                        BitmapDabTarget(buffer), listOf(android.graphics.Matrix()), down.type == androidx.compose.ui.input.pointer.PointerType.Stylus, 1f)
                    stroke.begin(InputPoint(down.position.x * sx, down.position.y * sy, down.pressure.coerceIn(0.05f, 1f), 0f, 0f, down.uptimeMillis))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        stroke.add(InputPoint(c.position.x * sx, c.position.y * sy, c.pressure.coerceIn(0.05f, 1f), 0f, 0f, c.uptimeMillis))
                        c.consume()
                        tick++
                    }
                    stroke.end()
                    Canvas(bmp).drawBitmap(buffer, 0f, 0f, Paint().apply { alpha = (stroke.compositeOpacity * 255).toInt() })
                    buffer.eraseColor(0)
                    tick++
                }
            },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                tick.let { }   // read in draw scope → redraw on every change
                val dst = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                val nc = drawContext.canvas.nativeCanvas
                nc.drawBitmap(bmp, null, dst, null)
                nc.drawBitmap(buffer, null, dst, null)
            }
        }
        Row(Modifier.padding(6.dp)) {
            IconBtn(Icons.Filled.Close, "패드 지우기", size = 32.dp) { bmp.eraseColor(0); tick++ }
        }
    }
}
