package com.prosketch.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.prosketch.app.engine.AdjustmentType
import com.prosketch.app.engine.AnimLoop
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.GradientPresets
import com.prosketch.app.engine.GuideType
import com.prosketch.app.engine.Interpolation
import com.prosketch.app.engine.LiquifyMode
import com.prosketch.app.engine.SelectionMode
import com.prosketch.app.engine.SelectionOp
import com.prosketch.app.engine.SymmetryType
import com.prosketch.app.engine.TransformMode
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.MenuRow
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.Segmented
import com.prosketch.app.ui.common.SwitchRow
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.checkerboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bottom floating toolbar container. */
@Composable
fun BottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(UI.panel).pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } }.padding(10.dp)) { content() }
}

// ---------------------------------------------------------------- Selection
@Composable
fun SelectionBar(ed: Editor, modifier: Modifier = Modifier) {
    val st = ed.selectionTool
    var feather by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    BottomBar(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (feather) {
                Row(Modifier.width(420.dp), verticalAlignment = Alignment.CenterVertically) {
                    var f by remember { mutableFloatStateOf(0f) }
                    Box(Modifier.weight(1f)) { LabeledSlider("페더", f, 0f..100f, "${f.roundToInt()}%", { f = it }) }
                    TextBtn("적용") { st.applyFeather(f / 100f * max(ed.doc.width, ed.doc.height) * 0.05f); feather = false }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Segmented(SelectionMode.entries.map { it.label }, st.mode.ordinal, Modifier.width(300.dp)) { st.finish(); st.mode = SelectionMode.entries[it] }
                Spacer(Modifier.width(6.dp))
                TextBtn("추가", st.op == SelectionOp.ADD) { st.op = SelectionOp.ADD }
                TextBtn("제거", st.op == SelectionOp.SUBTRACT) { st.op = SelectionOp.SUBTRACT }
                TextBtn("반전") { st.invert() }
                TextBtn("복사 및 붙여넣기") { ed.duplicateSelection() }
                TextBtn("페더", feather) { feather = !feather }
                Box {
                    TextBtn("저장 및 불러오기") { saveMenu = true }
                    DropdownMenu(saveMenu, { saveMenu = false }) {
                        DropdownMenuItem(text = { Text("+ 현재 선택 저장") }, onClick = { st.saveCurrent(); saveMenu = false })
                        st.saved.forEachIndexed { i, _ -> DropdownMenuItem(text = { Text("선택 ${i + 1} 불러오기") }, onClick = { st.load(i); saveMenu = false }) }
                    }
                }
                TextBtn("색상 채우기", st.colorFill) { st.colorFill = !st.colorFill }
                TextBtn("지우기") { st.clear() }
                TextBtn("완료", true) { ed.exitModes() }
            }
            if (st.mode == SelectionMode.FREEHAND) Text("드래그하여 올가미 · 탭하여 다각형 점 추가 · 시작점을 탭하여 닫기", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (st.mode == SelectionMode.AUTOMATIC) Text("탭하여 선택 · 누른 채 좌우로 밀어 임계값 ${(st.autoThreshold * 100).roundToInt()}%", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------- Transform
@Composable
fun TransformBar(ed: Editor, modifier: Modifier = Modifier) {
    val tt = ed.transformTool
    tt.version.let { }
    val s = tt.session ?: return
    BottomBar(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextBtn("자석", s.magnetics) { s.magnetics = !s.magnetics; tt.changed() }
            Segmented(TransformMode.entries.map { it.label }, s.mode.ordinal, Modifier.width(320.dp)) { tt.setMode(TransformMode.entries[it]) }
            TextBtn("수평 뒤집기") { tt.flipH() }
            TextBtn("수직 뒤집기") { tt.flipV() }
            TextBtn("45° 회전") { tt.rotate45() }
            TextBtn("화면에 맞추기") { tt.fitToScreen() }
            TextBtn("보간: ${s.interpolation.label}") { s.interpolation = Interpolation.entries[(s.interpolation.ordinal + 1) % Interpolation.entries.size]; tt.changed() }
            TextBtn("초기화") { tt.reset() }
            TextBtn("취소") { tt.cancel() }
            TextBtn("완료", true) { tt.commit() }
        }
    }
}

// ---------------------------------------------------------------- Adjustments
@Composable
fun AdjustmentsMenu(ed: Editor, modifier: Modifier = Modifier) {
    PanelBox(modifier, title = "조정") {
        val sections = listOf("색상", "흐림 효과", "효과", "변형")
        Column(Modifier.androidxScroll()) {
            AdjustmentType.entries.groupBy { it.section }.toSortedMap().forEach { (sec, items) ->
                com.prosketch.app.ui.common.SectionLabel(sections[sec])
                items.forEach { t -> MenuRow(t.label) { ed.adjustTool.begin(t) } }
            }
        }
    }
}

@Composable
private fun Modifier.androidxScroll(): Modifier = this.then(Modifier.height(560.dp)).then(Modifier.verticalScrollCompat())

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustBar(ed: Editor, modifier: Modifier = Modifier) {
    val at = ed.adjustTool
    val t = at.type ?: return
    BottomBar(modifier.widthIn(max = 720.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, color = UI.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (at.computing) Text("계산 중…", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                TextBtn("초기화") { at.reset() }
                Spacer(Modifier.width(6.dp))
                TextBtn("취소") { at.cancel() }
                Spacer(Modifier.width(6.dp))
                TextBtn("적용", true) { at.apply() }
            }
            when (t) {
                AdjustmentType.CURVES -> CurvesEditor(ed)
                AdjustmentType.COLOR_BALANCE -> {
                    at.version.let { }
                    Segmented(listOf("그림자", "중간톤", "하이라이트"), at.balanceTone, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { at.balanceTone = it }
                    val v = at.balance.v[at.balanceTone]
                    t.params.forEachIndexed { i, p ->
                        LabeledSlider(p.label, v[i], p.min..p.max, "${v[i].roundToInt()}", { nv -> v[i] = nv; at.balanceChanged() })
                    }
                    SwitchRow("광도 유지", at.balance.preserveLuminosity) { at.balance.preserveLuminosity = it; at.balanceChanged() }
                }
                AdjustmentType.GRADIENT_MAP -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        GradientPresets.all.forEachIndexed { i, g ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickableC { at.gradientIndex = i; at.setParam("amount", at.params["amount"] ?: 100f) }) {
                                Canvas(Modifier.size(70.dp, 22.dp).clip(RoundedCornerShape(5.dp)).border(if (at.gradientIndex == i) 2.dp else 0.dp, Color.White, RoundedCornerShape(5.dp))) {
                                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(*g.stops.indices.map { k -> g.stops[k] to Color(g.colors[k]) }.toTypedArray()))
                                }
                                Text(g.name, color = UI.text2, fontSize = 10.sp)
                            }
                        }
                    }
                }
                else -> {}
            }
            t.params.takeIf { t != AdjustmentType.COLOR_BALANCE }?.forEach { p ->
                val v = at.params[p.key] ?: p.default
                if (p.unit.isEmpty() && p.max <= 1f) SwitchRow(p.label, v >= 0.5f) { at.setParam(p.key, if (it) 1f else 0f) }
                else LabeledSlider(p.label, v, p.min..p.max, at.formatParam(p, v), { at.setParam(p.key, it) })
            }
            if (t.params.isNotEmpty() && t != AdjustmentType.COLOR_BALANCE) Text("캔버스를 손가락으로 좌우로 밀어 '${t.params[0].label}' 조절", color = UI.text2, fontSize = 11.sp)
            if (ed.selection != null) Text("선택 영역에만 적용됩니다", color = UI.accent, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableC(f: () -> Unit) = this.combinedClickable(onClick = f)

@Composable
private fun CurvesEditor(ed: Editor) {
    val at = ed.adjustTool
    at.version.let { }
    Segmented(listOf("마스터", "빨강", "녹색", "파랑"), at.curveChannel, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { at.curveChannel = it }
    val chColor = listOf(Color.White, Color(0xFFFF5A5F), Color(0xFF30C46C), Color(0xFF3F8CFF))[at.curveChannel]
    val atRef by rememberUpdatedState(at)
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF151517))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val a = atRef
                    val pts = a.curves.channel(a.curveChannel)
                    fun toN(o: Offset) = floatArrayOf((o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f))
                    val n = toN(down.position)
                    var idx = pts.indexOfFirst { hypot((it[0] - n[0]) * size.width, (it[1] - n[1]) * size.height) < 28.dp.toPx() }
                    if (idx < 0) { pts.add(n); pts.sortBy { it[0] }; idx = pts.indexOf(n) }
                    val point = pts[idx]
                    a.curvesChanged()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val endpoint = point === pts.first() || point === pts.last()
                        val outside = c.position.y < -40.dp.toPx() || c.position.y > size.height + 40.dp.toPx()
                        if (outside && !endpoint && pts.size > 2) { pts.remove(point); a.curvesChanged(); break }
                        val nn = toN(c.position)
                        point[0] = if (endpoint) point[0] else nn[0]; point[1] = nn[1]
                        pts.sortBy { it[0] }
                        a.curvesChanged()
                    }
                }
            },
    ) {
        for (i in 1..3) {
            drawLine(Color(0x22FFFFFF), Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height))
            drawLine(Color(0x22FFFFFF), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
        }
        val pts = at.curves.channel(at.curveChannel)
        val lut = at.curves.lut(pts)
        val path = Path()
        for (x in 0..255) {
            val px = x / 255f * size.width; val py = (1f - lut[x] / 255f) * size.height
            if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, chColor, style = Stroke(2.5.dp.toPx()))
        pts.forEach { p -> drawCircle(chColor, 7.dp.toPx(), Offset(p[0] * size.width, (1f - p[1]) * size.height)) }
    }
    Text("탭하여 점 추가 · 점을 위/아래 밖으로 끌어 삭제", color = UI.text2, fontSize = 11.sp)
}

// ---------------------------------------------------------------- Liquify
@Composable
fun LiquifyBar(ed: Editor, modifier: Modifier = Modifier) {
    val at = ed.adjustTool
    var menu by remember { mutableStateOf(false) }
    BottomBar(modifier.widthIn(max = 760.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    TextBtn("모드: ${at.lqMode.label} ▾", true) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        LiquifyMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { at.lqMode = m; at.syncLiquify(); menu = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextBtn("초기화") { at.reset() }
                TextBtn("취소") { at.cancel() }
                TextBtn("적용", true) { at.apply() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { LabeledSlider("크기", at.lqSize, 0f..1f, "${(at.lqSize * 100).roundToInt()}%", { at.lqSize = it; at.syncLiquify() }) }
                Box(Modifier.weight(1f)) { LabeledSlider("압력", at.lqPressure, 0f..1f, "${(at.lqPressure * 100).roundToInt()}%", { at.lqPressure = it; at.syncLiquify() }) }
                Box(Modifier.weight(1f)) { LabeledSlider("왜곡", at.lqDistortion, 0f..1f, "${(at.lqDistortion * 100).roundToInt()}%", { at.lqDistortion = it; at.syncLiquify() }) }
                Box(Modifier.weight(1f)) { LabeledSlider("모멘텀", at.lqMomentum, 0f..1f, "${(at.lqMomentum * 100).roundToInt()}%", { at.lqMomentum = it; at.syncLiquify() }) }
            }
        }
    }
}

// ---------------------------------------------------------------- Drawing guide editor
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideBar(ed: Editor, modifier: Modifier = Modifier) {
    val g = ed.doc.guides
    var rev by remember { mutableIntStateOf(0) }
    fun ch(f: () -> Unit) { f(); rev++; ed.version++; ed.invalidate() }
    rev.let { }
    BottomBar(modifier.widthIn(max = 820.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("그리기 가이드", color = UI.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextBtn("완료", true) { ed.mode = Mode.PAINT; ed.bump() }
            }
            Spacer(Modifier.height(6.dp))
            Segmented(GuideType.entries.map { it.label }, g.type.ordinal, Modifier.fillMaxWidth()) { ch { g.type = GuideType.entries[it]; g.enabled = true } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LabeledSlider("불투명도", g.opacity, 0.05f..1f, "${(g.opacity * 100).roundToInt()}%", { v -> ch { g.opacity = v } }) }
                Box(Modifier.weight(1f)) { LabeledSlider("두께", g.thickness, 0.5f..6f, "%.1f".format(g.thickness), { v -> ch { g.thickness = v } }) }
                if (g.type == GuideType.GRID || g.type == GuideType.ISOMETRIC)
                    Box(Modifier.weight(1f)) { LabeledSlider("격자 크기", g.gridSize, 10f..max(ed.doc.width, ed.doc.height) / 4f, "${g.gridSize.roundToInt()}px", { v -> ch { g.gridSize = v } }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0xFF3FA9F5, 0xFFFF5A5F, 0xFF30C46C, 0xFFFFC53D, 0xFFB57BFF, 0xFFFFFFFF, 0xFF000000).forEach { c ->
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(c.toInt()))
                        .border(if (g.color == c.toInt()) 2.dp else 0.dp, Color.White, RoundedCornerShape(12.dp))
                        .combinedClickableC { ch { g.color = c.toInt() } })
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { SwitchRow("그리기 도우미", g.assist) { v -> ch { g.assist = v } } }
            }
            when (g.type) {
                GuideType.SYMMETRY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SymmetryType.entries.forEach { s -> TextBtn(s.label, g.symmetry == s) { ch { g.symmetry = s } } }
                    TextBtn("회전 대칭", g.rotational) { ch { g.rotational = !g.rotational } }
                    if (g.symmetry == SymmetryType.RADIAL) {
                        TextBtn("−") { ch { g.radialSegments = (g.radialSegments - 2).coerceAtLeast(2) } }
                        Text("${g.radialSegments}분할", color = UI.text, modifier = Modifier.padding(top = 8.dp))
                        TextBtn("+") { ch { g.radialSegments = (g.radialSegments + 2).coerceAtMost(32) } }
                    }
                    TextBtn("중심 초기화") { ch { g.cx = Float.NaN; g.cy = Float.NaN; g.rotationDeg = 0f } }
                }
                GuideType.PERSPECTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("캔버스를 탭하여 소실점 추가 (최대 3개) · 드래그하여 이동", color = UI.text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextBtn("소실점 지우기") { ch { g.vanishingPoints.clear() } }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("파란 점: 위치 · 초록 점: 회전", color = UI.text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextBtn("초기화") { ch { g.cx = Float.NaN; g.cy = Float.NaN; g.rotationDeg = 0f } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Copy & paste (3-finger swipe down)
@Composable
fun CopyPasteMenu(ed: Editor, modifier: Modifier = Modifier) {
    BottomBar(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("복사 및 붙여넣기", color = UI.text, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            TextBtn("잘라내기") { ed.cut(); ed.copyPasteMenu = false }
            TextBtn("복사") { ed.copy(); ed.copyPasteMenu = false }
            TextBtn("모두 복사") { ed.copy(all = true); ed.copyPasteMenu = false }
            TextBtn("복제") { ed.duplicateSelection(); ed.copyPasteMenu = false }
            TextBtn("잘라내기 및 붙여넣기") { ed.cutAndPaste(); ed.copyPasteMenu = false }
            TextBtn("붙여넣기", enabled = ed.clipboard != null) { ed.paste(); ed.copyPasteMenu = false }
            Icon(Icons.Filled.Close, "닫기", tint = UI.text2, modifier = Modifier.size(22.dp).combinedClickableC { ed.copyPasteMenu = false })
        }
    }
}

// ---------------------------------------------------------------- Animation timeline
@Composable
fun AnimationTimeline(ed: Editor, modifier: Modifier = Modifier) {
    val doc = ed.doc
    val a = doc.animation
    ed.version.let { }
    var settings by remember { mutableStateOf(false) }
    var frameMenu by remember { mutableStateOf<com.prosketch.app.engine.Layer?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    LaunchedEffect(ed.playing) {
        if (!ed.playing) return@LaunchedEffect
        var seq = com.prosketch.app.engine.Anim.sequence(doc)
        if (seq.isEmpty()) { ed.playing = false; return@LaunchedEffect }
        var k = seq.indexOf(com.prosketch.app.engine.Anim.currentFrame(doc)).coerceAtLeast(0)
        while (ed.playing) {
            ed.playLayer = seq[k]
            ed.invalidate()
            delay(1000L / a.fps.coerceIn(1, 60))
            seq = com.prosketch.app.engine.Anim.sequence(doc)
            if (seq.isEmpty()) break
            if (k + 1 >= seq.size) { if (a.loop == AnimLoop.ONESHOT) ed.playing = false else k = 0 } else k++
        }
        ed.playLayer = null
        ed.invalidate()
    }
    BottomBar(modifier.fillMaxWidth()) {
        Column {
            if (settings) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(AnimLoop.entries.map { it.label }, a.loop.ordinal, Modifier.width(220.dp)) { a.loop = AnimLoop.entries[it]; rev++; ed.bump() }
                    Box(Modifier.weight(1f)) { LabeledSlider("초당 프레임", a.fps.toFloat(), 1f..60f, "${a.fps}", { a.fps = it.roundToInt(); rev++ }, { ed.bump() }) }
                    Box(Modifier.weight(1f)) { LabeledSlider("어니언 스킨 프레임", a.onionFrames.toFloat(), 0f..12f, if (a.onionFrames == 0) "없음" else "${a.onionFrames}", { a.onionFrames = it.roundToInt(); rev++; ed.invalidate() }, { ed.bump() }) }
                    Box(Modifier.weight(1f)) { LabeledSlider("어니언 스킨 불투명도", a.onionOpacity, 0f..1f, "${(a.onionOpacity * 100).roundToInt()}%", { a.onionOpacity = it; rev++; ed.invalidate() }, { ed.bump() }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SwitchRow("기본 프레임 혼합", a.blendPrimary, sub = "끄면 현재 프레임이 어니언 스킨 위에 그대로 표시") { a.blendPrimary = it; rev++; ed.bump() } }
                    Box(Modifier.weight(1f)) { SwitchRow("어니언 스킨 색상", a.colorOnion) { a.colorOnion = it; rev++; ed.bump() } }
                    Text("이전", color = UI.text2, fontSize = 12.sp)
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(a.onionBefore)).border(1.dp, Color.White, RoundedCornerShape(6.dp))
                        .combinedClickableC { a.onionBefore = ed.color; rev++; ed.bump(); ed.hud("이전 프레임 색상: 현재 색상") })
                    Text("다음", color = UI.text2, fontSize = 12.sp)
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(a.onionAfter)).border(1.dp, Color.White, RoundedCornerShape(6.dp))
                        .combinedClickableC { a.onionAfter = ed.color; rev++; ed.bump(); ed.hud("다음 프레임 색상: 현재 색상") })
                }
            }
            frameMenu?.takeIf { it in doc.layers }?.let { f ->
                val frames = doc.topLevel()
                val first = frames.firstOrNull() === f; val last = frames.lastOrNull() === f
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(UI.panel2).padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, color = UI.text, fontSize = 13.sp, modifier = Modifier.width(110.dp), maxLines = 1)
                    Box(Modifier.weight(1f)) {
                        LabeledSlider("유지 시간", f.holdFrames.toFloat(), 0f..120f, if (f.holdFrames == 0) "없음" else "${f.holdFrames}프레임",
                            { f.holdFrames = it.roundToInt(); rev++; ed.invalidate() }, { ed.setHold(f, f.holdFrames) })
                    }
                    TextBtn("복제") { ed.duplicateLayer(doc.indexOf(f)); frameMenu = null }
                    TextBtn("삭제") { ed.deleteLayer(doc.indexOf(f)); frameMenu = null }
                    if (first) TextBtn("배경", a.firstFrameBackground) { a.firstFrameBackground = !a.firstFrameBackground; rev++; ed.bump() }
                    if (last) TextBtn("전경", a.lastFrameForeground) { a.lastFrameForeground = !a.lastFrameForeground; rev++; ed.bump() }
                    Icon(Icons.Filled.Close, "닫기", tint = UI.text2, modifier = Modifier.size(22.dp).combinedClickableC { frameMenu = null })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.prosketch.app.ui.common.IconBtn(Icons.Filled.Settings, "설정", selected = settings) { settings = !settings }
                com.prosketch.app.ui.common.IconBtn(if (ed.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "재생") { ed.playing = !ed.playing }
                com.prosketch.app.ui.common.IconBtn(Icons.Filled.Add, "프레임 추가") { ed.playing = false; ed.addFrame() }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val frames = doc.topLevel()
                    val current = if (ed.playing) ed.playLayer else com.prosketch.app.engine.Anim.currentFrame(doc)
                    val bg = com.prosketch.app.engine.Anim.background(doc); val fg = com.prosketch.app.engine.Anim.foreground(doc)
                    frames.forEach { f ->
                        val cur = current === f
                        val thumb = ed.frameThumb(f)
                        Box(Modifier.size(width = 72.dp, height = 50.dp).clip(RoundedCornerShape(6.dp)).checkerboard(4.dp)
                            .border(if (cur) 2.5.dp else 1.dp, if (cur) UI.accent else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                            .combinedClickableC {
                                ed.playing = false
                                if (cur) frameMenu = if (frameMenu === f) null else f
                                else { ed.selectLayer(doc.indexOf(f)); frameMenu = null }
                            }) {
                            Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            val tag = when { f === bg -> "배경"; f === fg -> "전경"; f.isGroup -> "그룹"; else -> null }
                            if (tag != null) Text(tag, color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(2.dp))
                        }
                        // held beats are shown as greyed slivers after the frame
                        val shown = f.holdFrames.coerceAtMost(8)
                        repeat(shown) { Box(Modifier.size(width = 10.dp, height = 44.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) }
                        if (f.holdFrames > 8) Text("+${f.holdFrames - 8}", color = UI.text2, fontSize = 10.sp)
                    }
                }
                Text("${com.prosketch.app.engine.Anim.totalFrames(doc)}프레임 · ${a.fps}fps", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Reference window
@Composable
fun ReferenceWindow(ed: Editor) {
    var off by remember { mutableStateOf(Offset(40f, 140f)) }
    var w by remember { mutableFloatStateOf(300f) }
    val img by produceState<Bitmap?>(null, ed.referenceVersion, ed.referenceImage) {
        val ri = ed.referenceImage
        value = ri ?: run {
            val shadow = ed.doc.shallowCopy()
            withContext(Dispatchers.Default) { Compositor.flatten(shadow, min(1f, 600f / max(shadow.width, shadow.height))) }
        }
    }
    val bmp = img ?: return
    val ratio = bmp.height.toFloat() / bmp.width
    Box(
        Modifier.offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }
            .size(w.dp, (w * ratio + 30).dp)
            .clip(RoundedCornerShape(12.dp)).background(UI.panel).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); off += d } }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("참조", color = UI.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (ed.referenceImage != null) "캔버스 보기" else "", color = UI.accent, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 10.dp).combinedClickableC { ed.referenceImage = null; ed.referenceVersion++ })
                Icon(Icons.Filled.Close, "닫기", tint = UI.text2, modifier = Modifier.size(18.dp).combinedClickableC { ed.referenceVisible = false })
            }
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White))
        }
        Box(Modifier.align(Alignment.BottomEnd).size(26.dp).pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); w = (w + d.x / 2f).coerceIn(160f, 700f) } }
            .background(Color(0x55FFFFFF), RoundedCornerShape(topStart = 10.dp)))
    }
}

// ---------------------------------------------------------------- Time-lapse replay
@Composable
fun TimelapseReplay(ed: Editor, onClose: () -> Unit) {
    val frames = remember { ed.timelapse.frames() }
    var idx by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    val bmp by produceState<Bitmap?>(null, idx) {
        value = withContext(Dispatchers.IO) { frames.getOrNull(idx)?.let { BitmapFactory.decodeFile(it.absolutePath) } }
    }
    LaunchedEffect(playing, frames.size) {
        while (playing && frames.isNotEmpty()) { delay(33); idx = (idx + 1) % frames.size }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (frames.isEmpty()) Text("녹화된 타임랩스가 없습니다", color = Color.White, modifier = Modifier.align(Alignment.Center))
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)
                .pointerInput(frames.size) { detectDragGestures { c, d -> c.consume(); playing = false; if (frames.isNotEmpty()) idx = ((idx + (d.x / 4).roundToInt()) % frames.size + frames.size) % frames.size } }) }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                com.prosketch.app.ui.common.IconBtn(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "재생") { playing = !playing }
                Slider(idx.toFloat(), { playing = false; idx = it.roundToInt() }, valueRange = 0f..max(1, frames.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("${idx + 1}/${frames.size}", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                TextBtn("완료", true) { onClose() }
            }
        }
    }
}

@Suppress("unused") private fun unusedAbs(x: Float) = abs(x)
