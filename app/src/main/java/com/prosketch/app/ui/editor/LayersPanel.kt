package com.prosketch.app.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.ui.common.IconBtn
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.checkerboard
import kotlin.math.roundToInt

@Composable
fun LayersPanel(ed: Editor, modifier: Modifier = Modifier) {
    val v = ed.version   // recompose on any document change
    val doc = ed.doc
    var blendFor by remember { mutableStateOf<Layer?>(null) }
    var menuFor by remember { mutableStateOf<Layer?>(null) }
    var renameFor by remember { mutableStateOf<Layer?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowH = 64.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }

    PanelBox(modifier, title = "레이어", trailing = {
        Text("${doc.layers.size}/${ed.maxLayers}", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
        IconBtn(Icons.Filled.Add, "레이어 추가") { ed.addLayer() }
    }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            v.let { }
            val count = doc.layers.size
            for (vi in 0 until count) {
                val i = count - 1 - vi
                val layer = doc.layers[i]
                val isDragged = dragIndex == i
                Box(
                    Modifier.zIndex(if (isDragged) 1f else 0f)
                        .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                        .pointerInput(layer.id, count) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = doc.layers.indexOf(layer); dragOffset = 0f },
                                onDragEnd = {
                                    val from = dragIndex
                                    val to = (from - (dragOffset / rowPx).roundToInt()).coerceIn(0, doc.layers.size - 1)
                                    dragIndex = -1; dragOffset = 0f
                                    if (from >= 0) ed.moveLayer(from, to)
                                },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            ) { ch, d -> ch.consume(); dragOffset += d.y }
                        },
                ) {
                    LayerRow(ed, layer, i == doc.activeIndex, rowH,
                        onTap = { if (i == doc.activeIndex) menuFor = layer else ed.selectLayer(i) },
                        onBlend = { blendFor = if (blendFor === layer) null else layer; ed.selectLayer(i) })
                    DropdownMenu(menuFor === layer, { menuFor = null }) { LayerOptions(ed, layer, { menuFor = null }, { renameFor = layer }) }
                }
                if (blendFor === layer) BlendEditor(ed, layer)
                Spacer(Modifier.height(4.dp))
            }
            BackgroundRow(ed)
        }
    }

    renameFor?.let { l ->
        var name by remember(l) { mutableStateOf(l.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("레이어 이름 변경") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ ed.rename(l, name); renameFor = null }) { Text("확인") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(ed: Editor, layer: Layer, active: Boolean, rowH: androidx.compose.ui.unit.Dp, onTap: () -> Unit, onBlend: () -> Unit) {
    val bg = if (active) UI.accent else UI.panel2
    val tapRef by rememberUpdatedState(onTap)
    Row(
        Modifier.fillMaxWidth().height(rowH).clip(RoundedCornerShape(10.dp)).background(bg)
            .combinedClickable(onClick = { tapRef() })
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer.clipping) Icon(Icons.Filled.SubdirectoryArrowRight, "클리핑", tint = Color.White, modifier = Modifier.size(18.dp))
        val thumb = layer.thumbnail()
        val ratio = thumb.width.toFloat() / thumb.height
        Box(Modifier.size(width = (46f * ratio.coerceIn(0.6f, 1.6f)).dp, height = 46.dp).clip(RoundedCornerShape(4.dp))
            .checkerboard(if (layer.alphaLock) 4.dp else 6.dp).border(1.dp, Color(0x33000000), RoundedCornerShape(4.dp))) {
            Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(layer.name, color = Color.White, fontSize = 14.sp, maxLines = 1, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            val tags = buildList {
                if (layer.isReference) add("참조")
                if (layer.alphaLock) add("알파 잠금")
                if (layer.opacity < 0.995f) add("${(layer.opacity * 100).roundToInt()}%")
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
        if (layer.locked) Icon(Icons.Filled.Lock, "잠김", tint = Color.White, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = onBlend), contentAlignment = Alignment.Center) {
            Text(layer.blend.short, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        // visibility checkbox; long-press = solo
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .background(if (layer.visible) Color.White.copy(alpha = 0.95f) else Color.Transparent)
                .combinedClickable(onClick = { ed.toggleVisible(layer) }, onLongClick = { ed.soloToggle(layer) }),
            contentAlignment = Alignment.Center,
        ) { if (layer.visible) Icon(Icons.Filled.Check, "표시", tint = if (active) UI.accent else Color.Black, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun BlendEditor(ed: Editor, layer: Layer) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E21)).padding(10.dp)) {
        LabeledSlider("불투명도", layer.opacity, 0f..1f, "${(layer.opacity * 100).roundToInt()}%", { ed.setOpacityLive(layer, it) }, { ed.commitOpacity(layer) })
        var group = -1
        Column {
            LayerBlend.entries.forEach { m ->
                if (m.group != group) { if (group >= 0) HorizontalDivider(color = UI.divider, modifier = Modifier.padding(vertical = 3.dp)); group = m.group }
                val sel = layer.blend == m
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel) UI.accentDim else Color.Transparent)
                    .combinedClickableCompat { ed.setBlend(layer, m) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(m.label, color = UI.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(m.short, color = UI.text2, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun LayerOptions(ed: Editor, layer: Layer, close: () -> Unit, rename: () -> Unit) {
    val i = ed.doc.indexOf(layer)
    @Composable
    fun item(t: String, checked: Boolean? = null, enabled: Boolean = true, f: () -> Unit) =
        DropdownMenuItem(text = { Text(t + if (checked == true) "  ✓" else "") }, enabled = enabled, onClick = { close(); f() })
    item("이름 변경") { rename() }
    item("선택") { ed.selectLayerAlpha(i) }
    item("복사") { ed.copy() }
    item("레이어 채우기") { ed.fillLayer(i) }
    item("지우기") { ed.clearLayer(i) }
    HorizontalDivider()
    item("알파 채널 잠금", layer.alphaLock) { ed.toggleAlphaLock(layer) }
    item("클리핑 마스크", layer.clipping, enabled = i > 0) { ed.toggleClipping(layer) }
    item("반전") { ed.invertLayer(i) }
    item("참조", layer.isReference) { ed.toggleReference(layer) }
    item("잠금", layer.locked) { ed.toggleLock(layer) }
    HorizontalDivider()
    item("아래 레이어와 병합", enabled = i > 0) { ed.mergeDown(i) }
    item("복제") { ed.duplicateLayer(i) }
    item("가로로 뒤집기") { ed.flipLayer(true) }
    item("세로로 뒤집기") { ed.flipLayer(false) }
    item("삭제") { ed.deleteLayer(i) }
}

@Composable
private fun BackgroundRow(ed: Editor) {
    val doc = ed.doc
    var pick by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(UI.panel2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(doc.backgroundColor))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp)).combinedClickableCompat { pick = true })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("배경 색상", color = UI.text, fontSize = 14.sp)
            Text("탭: 현재 색상으로 설정", color = UI.text2, fontSize = 11.sp)
        }
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .background(if (doc.backgroundVisible) Color.White else Color.Transparent)
                .combinedClickableCompat { ed.setBackground(visible = !doc.backgroundVisible) },
            contentAlignment = Alignment.Center,
        ) { if (doc.backgroundVisible) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
    }
    if (pick) AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text("배경 색상") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ed.color, 0xFFFFFFFF.toInt(), 0xFFF4EFE6.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt()).forEach { c ->
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(c)).border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .combinedClickableCompat { ed.setBackground(color = c, visible = true); pick = false })
                }
            }
        },
        confirmButton = { TextButton({ pick = false }) { Text("닫기") } },
    )
}
