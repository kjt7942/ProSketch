package com.prosketch.app.ui.editor

import com.prosketch.app.L

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.ui.common.UI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Actions a QuickMenu button can run (a subset of Procreate's list that ProSketch supports). */
enum class QuickAction(val label: String, val run: (Editor) -> Unit) {
    NEW_LAYER(L.t("새 레이어"), { it.addLayer() }),
    FLIP_H(L.t("가로로 뒤집기"), { it.flipLayer(true) }),
    FLIP_V(L.t("세로로 뒤집기"), { it.flipLayer(false) }),
    COPY(L.t("복사"), { it.copy() }),
    MERGE_DOWN(L.t("아래 레이어와 병합"), { it.mergeDown(it.doc.activeIndex) }),
    CLEAR_LAYER(L.t("레이어 지우기"), { it.clearLayer() }),
    PASTE(L.t("붙여넣기"), { it.paste() }),
    CUT(L.t("잘라내기"), { it.cut() }),
    COPY_ALL(L.t("모두 복사"), { it.copy(all = true) }),
    DUPLICATE_LAYER(L.t("레이어 복제"), { it.duplicateLayer(it.doc.activeIndex) }),
    DELETE_LAYER(L.t("레이어 삭제"), { it.deleteLayer(it.doc.activeIndex) }),
    FILL_LAYER(L.t("레이어 채우기"), { it.fillLayer() }),
    ALPHA_LOCK(L.t("알파 채널 잠금"), { e -> e.doc.activeLayer?.let { e.toggleAlphaLock(it) } }),
    CLIPPING(L.t("클리핑 마스크"), { e -> e.doc.activeLayer?.let { e.toggleClipping(it) } }),
    LAYER_VISIBILITY(L.t("레이어 가시성"), { e -> e.doc.activeLayer?.let { e.toggleVisible(it) } }),
    SOLO(L.t("단독 가시성"), { e -> e.doc.activeLayer?.let { e.soloToggle(it) } }),
    REFERENCE(L.t("참조"), { e -> e.doc.activeLayer?.let { e.toggleReference(it) } }),
    SELECT_CONTENT(L.t("레이어 내용 선택"), { it.selectLayerAlpha() }),
    UNDO(L.t("실행 취소"), { it.undo() }),
    REDO(L.t("다시 실행"), { it.redo() }),
    PREVIOUS_COLOR(L.t("이전 색상"), { it.swapWithPrevious() }),
    EYEDROPPER(L.t("스포이드"), { it.eyedropperArmed = true; it.hud(L.t("캔버스를 탭하여 색상 선택")) }),
    PAINT(L.t("페인트"), { it.exitModes(); it.tool = Tool.BRUSH }),
    SMUDGE(L.t("문지르기"), { it.exitModes(); it.tool = Tool.SMUDGE }),
    ERASE(L.t("지우기"), { it.exitModes(); it.tool = Tool.ERASER }),
    SELECTION(L.t("선택 도구"), { it.selectionTool.begin() }),
    TRANSFORM(L.t("변형 도구"), { it.exitModes(); it.transformTool.begin() }),
    ADD_TEXT(L.t("텍스트 추가"), { it.startNewText() }),
    FULLSCREEN(L.t("전체 화면"), { it.fullscreen = !it.fullscreen }),
    FIT_SCREEN(L.t("화면에 맞추기"), { it.fitRequest?.invoke() }),
    MIRROR_VIEW(L.t("좌우 반전 보기"), { it.toggleMirrorView() }),
    GRAYSCALE_VIEW(L.t("흑백 보기"), { it.toggleGrayscaleView() }),
    GUIDE(L.t("그리기 가이드 표시"), { it.doc.guides.enabled = !it.doc.guides.enabled; it.bump() }),
    ASSIST(L.t("그리기 도우미"), { it.doc.guides.assist = !it.doc.guides.assist; it.hud(if (it.doc.guides.assist) L.t("그리기 도우미 켬") else L.t("그리기 도우미 끔")); it.bump() }),
    LAYERS_PANEL(L.t("레이어 패널"), { it.panel = Panel.LAYERS }),
    COLOR_PANEL(L.t("색상 패널"), { it.panel = Panel.COLOR }),
    ADJUSTMENTS(L.t("조정"), { it.panel = Panel.ADJUSTMENTS }),
    HISTORY(L.t("작업 내역"), { it.panel = Panel.HISTORY }),
    NONE(L.t("동작 없음"), { });

    companion object {
        val defaults = listOf(NEW_LAYER, FLIP_H, COPY, MERGE_DOWN, CLEAR_LAYER, FLIP_V)
        fun parse(s: String): List<QuickAction> {
            val l = s.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n } }
            return if (l.size == 6) l else defaults
        }
    }
}

/**
 * Radial QuickMenu around [Editor.quickMenuAt] (screen px). Tap a button, or keep the finger down
 * and release over one ([Editor.quickMenuHover] is driven by the canvas). Hold a button to change it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickMenuOverlay(ed: Editor) {
    val at = ed.quickMenuAt ?: return
    val actions = QuickAction.parse(ed.prefs.quickMenuActions)
    val density = LocalDensity.current
    val radius = with(density) { 128.dp.toPx() }
    val btn = 94.dp
    val btnPx = with(density) { btn.toPx() }
    var editing by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxSize().combinedClickable(indication = null, interactionSource = null, onClick = { ed.quickMenuAt = null }))
    Box(Modifier.offset { IntOffset((at.x - btnPx * 0.55f).roundToInt(), (at.y - btnPx * 0.55f).roundToInt()) }
        .size(btn * 1.1f).background(Color(0xE61C1C1E), CircleShape).border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center) {
        Text("QuickMenu", color = UI.text2, fontSize = 10.sp)
    }
    actions.forEachIndexed { i, a ->
        val ang = -Math.PI / 2 + i * Math.PI * 2 / 6
        val cx = at.x + cos(ang).toFloat() * radius; val cy = at.y + sin(ang).toFloat() * radius
        val hot = ed.quickMenuHover == i
        Box(
            Modifier.offset { IntOffset((cx - btnPx / 2).roundToInt(), (cy - btnPx / 2).roundToInt()) }
                .size(btn).background(if (hot) UI.accent else Color(0xF02A2A2E), CircleShape)
                .border(1.dp, Color(0x44FFFFFF), CircleShape)
                .combinedClickable(onClick = { ed.quickMenuAt = null; a.run(ed) }, onLongClick = { editing = i }),
            contentAlignment = Alignment.Center,
        ) {
            Text(a.label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
            DropdownMenu(editing == i, { editing = -1 }) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    QuickAction.entries.forEach { q ->
                        DropdownMenuItem(text = { Text(q.label + if (q == a) "  ✓" else "") }, onClick = {
                            val l = actions.toMutableList(); l[i] = q
                            ed.prefs.quickMenuActions = l.joinToString(",") { it.name }
                            editing = -1; ed.version++
                        })
                    }
                }
            }
        }
    }
}

/** Index of the button under a screen point, or -1 (used for touch-and-release selection). */
fun quickMenuIndexAt(center: android.graphics.PointF, x: Float, y: Float, minDist: Float): Int {
    val dx = x - center.x; val dy = y - center.y
    if (hypot(dx, dy) < minDist) return -1
    var a = Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0
    a = (a % 360 + 360) % 360
    return ((a + 30) / 60).toInt() % 6
}
