package com.prosketch.app.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.LayerBlend
import com.prosketch.app.ui.common.IconBtn
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.checkerboard
import kotlin.math.roundToInt

private val secondaryBg = Color(0xFF1D4F91)

@Composable
fun LayersPanel(ed: Editor, modifier: Modifier = Modifier) {
    val v = ed.version   // recompose on any document change
    val doc = ed.doc
    var blendFor by remember { mutableStateOf<Layer?>(null) }
    var menuFor by remember { mutableStateOf<Layer?>(null) }
    var menuMask by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<Layer?>(null) }
    var swiped by remember { mutableStateOf<Layer?>(null) }
    var dragged by remember { mutableStateOf<Layer?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    val rowH = 64.dp
    val gap = 4.dp
    val density = LocalDensity.current
    val stepPx = with(density) { (rowH + gap).toPx() }
    val indentPx = with(density) { 40.dp.toPx() }

    val multi = ed.secondary.isNotEmpty()
    PanelBox(modifier, title = "레이어", trailing = {
        if (multi) {
            TextBtn("그룹") { ed.createGroup() }
            TextBtn("병합") { ed.mergeSelected() }
            TextBtn("삭제") { ed.deleteSelected() }
            Spacer(Modifier.width(4.dp))
        }
        Text("${doc.pixelBudgetUsed()}/${ed.maxLayers}", color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
        IconBtn(Icons.Filled.Add, "레이어 추가") { ed.addLayer() }
    }) {
        v.let { }
        val rows = ed.displayRows()
        val d = dragged
        // rows that move with the dragged one (a group's visible descendants)
        val block = if (d == null) emptyList() else rows.filter { it === d || doc.isDescendant(it, d) }
        val others = rows.filter { it !in block }
        val k0 = if (d == null) 0 else rows.indexOf(d)
        val target = if (d == null) 0 else ((k0 * stepPx + dragDy) / stepPx).roundToInt().coerceIn(0, others.size)
        val outdent = if (d == null) 0 else (-dragDx / indentPx).toInt().coerceAtLeast(0)
        val rowAbove = others.getOrNull(target - 1)
        val dropDepth = if (d == null) 0 else (ed.gapDepth(rowAbove) - outdent).coerceAtLeast(0)

        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(with(density) { (rows.size * stepPx).toDp() })) {
                fun slotOf(l: Layer): Float = when {
                    d == null -> rows.indexOf(l) * stepPx
                    l in block -> (k0 + block.indexOf(l)) * stepPx + dragDy
                    else -> { val j = others.indexOf(l); (if (j >= target) j + block.size else j) * stepPx }
                }
                if (d != null) {
                    // drop indicator at the target gap, indented to the depth the layer will get
                    val y = (target * stepPx - with(density) { gap.toPx() } / 2f)
                    Box(Modifier.offset { IntOffset((dropDepth * with(density) { 14.dp.toPx() }).roundToInt(), y.roundToInt()) }
                        .fillMaxWidth().height(3.dp).background(UI.accent).zIndex(2f))
                }
                for (layer in rows) {
                    val isDragged = layer in block
                    Box(
                        Modifier.zIndex(if (isDragged) 1f else 0f)
                            .offset { IntOffset(0, slotOf(layer).roundToInt()) }
                            .pointerInput(layer.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragged = layer; dragDy = 0f; dragDx = 0f; swiped = null },
                                    onDragEnd = {
                                        val dl = dragged
                                        if (dl != null) {
                                            val rws = ed.displayRows()
                                            val blk = rws.filter { it === dl || doc.isDescendant(it, dl) }
                                            val oth = rws.filter { it !in blk }
                                            val k = rws.indexOf(dl)
                                            val t = ((k * stepPx + dragDy) / stepPx).roundToInt().coerceIn(0, oth.size)
                                            val od = (-dragDx / indentPx).toInt().coerceAtLeast(0)
                                            val above = oth.getOrNull(t - 1)
                                            val moved = t != k || od > 0
                                            if (moved) ed.moveToGap(dl, above, od)
                                        }
                                        dragged = null; dragDy = 0f; dragDx = 0f
                                    },
                                    onDragCancel = { dragged = null; dragDy = 0f; dragDx = 0f },
                                ) { ch, delta -> ch.consume(); dragDy += delta.y; dragDx += delta.x }
                            },
                    ) {
                        Column {
                            LayerRow(ed, layer, rowH,
                                swipedOpen = swiped === layer,
                                onTap = {
                                    if (swiped != null) { swiped = null; return@LayerRow }
                                    val i = doc.indexOf(layer)
                                    if (i == doc.activeIndex && !doc.editMask) { menuFor = layer; menuMask = false } else ed.selectLayer(i)
                                },
                                onMaskTap = {
                                    val i = doc.indexOf(layer)
                                    if (i == doc.activeIndex && doc.editMask) { menuFor = layer; menuMask = true } else ed.selectLayer(i, mask = true)
                                },
                                onBlend = { blendFor = if (blendFor === layer) null else layer; ed.selectLayer(doc.indexOf(layer)) },
                                onSwipeRight = { ed.toggleSecondary(layer) },
                                onSwipeLeft = { swiped = if (swiped === layer) null else layer },
                                onSwipeAction = { a ->
                                    swiped = null
                                    when (a) {
                                        0 -> ed.toggleLock(layer)
                                        1 -> ed.duplicateLayer(doc.indexOf(layer))
                                        2 -> ed.deleteLayer(doc.indexOf(layer))
                                    }
                                },
                            )
                            DropdownMenu(menuFor === layer, { menuFor = null }) {
                                when {
                                    menuMask -> MaskOptions(ed, layer) { menuFor = null }
                                    layer.isGroup -> GroupOptions(ed, layer, { menuFor = null }, { renameFor = layer })
                                    else -> LayerOptions(ed, layer, { menuFor = null }, { renameFor = layer })
                                }
                            }
                        }
                    }
                }
            }
            blendFor?.let { if (it in doc.layers) BlendEditor(ed, it) }
            Spacer(Modifier.height(4.dp))
            BackgroundRow(ed)
        }
    }

    renameFor?.let { l ->
        var name by remember(l) { mutableStateOf(l.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(if (l.isGroup) "그룹 이름 변경" else "레이어 이름 변경") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ ed.rename(l, name); renameFor = null }) { Text("확인") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(
    ed: Editor, layer: Layer, rowH: Dp, swipedOpen: Boolean,
    onTap: () -> Unit, onMaskTap: () -> Unit, onBlend: () -> Unit,
    onSwipeRight: () -> Unit, onSwipeLeft: () -> Unit, onSwipeAction: (Int) -> Unit,
) {
    val doc = ed.doc
    val primary = doc.activeLayer === layer
    val secondary = ed.secondary.contains(layer.id)
    val editingMask = primary && doc.editMask
    val bg = when { primary && !editingMask -> UI.accent; primary -> UI.accentDim; secondary -> secondaryBg; else -> UI.panel2 }
    val tapRef by rememberUpdatedState(onTap)
    val depth = doc.depth(layer)
    val swipePx = with(LocalDensity.current) { 56.dp.toPx() }
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().height(rowH).padding(start = (depth * 14).dp)) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(bg)
                .pointerInput(layer.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipeAcc = 0f },
                        onDragEnd = {
                            if (swipeAcc > swipePx) onSwipeRight() else if (swipeAcc < -swipePx) onSwipeLeft()
                            swipeAcc = 0f
                        },
                    ) { ch, dx -> ch.consume(); swipeAcc += dx }
                }
                .combinedClickable(onClick = { tapRef() })
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = if (doc.effectiveVisible(layer)) 1f else 0.55f
            if (layer.isGroup) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { ed.toggleExpanded(layer) }), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ExpandMore, if (layer.expanded) "접기" else "펼치기", tint = Color.White, modifier = Modifier.size(22.dp).rotate(if (layer.expanded) 0f else -90f))
                }
                Icon(Icons.Filled.Folder, null, tint = Color.White.copy(alpha = 0.9f * dim), modifier = Modifier.size(30.dp))
            } else {
                if (layer.clipping) Icon(Icons.Filled.SubdirectoryArrowRight, "클리핑", tint = Color.White, modifier = Modifier.size(18.dp))
                Thumb(layer, layer.alphaLock, selected = false, dim = dim)
                if (layer.isText) Text("A", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x66000000)).padding(horizontal = 3.dp))
            }
            layer.mask?.let { m ->
                Spacer(Modifier.width(6.dp))
                Box(Modifier.combinedClickable(onClick = onMaskTap)) { Thumb(m, false, selected = editingMask, dim = dim, black = true) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(layer.name, color = Color.White.copy(alpha = dim), fontSize = 14.sp, maxLines = 1,
                    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
                val tags = buildList {
                    if (editingMask) add("마스크 편집")
                    if (layer.isReference) add("참조")
                    if (layer.alphaLock) add("알파 잠금")
                    if (layer.isPrivate) add("비공개")
                    if (layer.isText) add("텍스트")
                    if (layer.opacity < 0.995f) add("${(layer.opacity * 100).roundToInt()}%")
                    if (layer.holdFrames > 0 && doc.animation.enabled) add("유지 ${layer.holdFrames}")
                }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
            }
            if (doc.effectiveLocked(layer) && !layer.locked) Icon(Icons.Filled.Lock, "그룹 잠김", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            if (layer.locked) Icon(Icons.Filled.Lock, "잠김", tint = Color.White, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = onBlend), contentAlignment = Alignment.Center) {
                Text(if (layer.isGroup && layer.passThrough) "P" else layer.blend.short, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            // visibility checkbox; long-press = solo
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                    .background(if (layer.visible) Color.White.copy(alpha = 0.95f) else Color.Transparent)
                    .combinedClickable(onClick = { ed.toggleVisible(layer) }, onLongClick = { ed.soloToggle(layer) }),
                contentAlignment = Alignment.Center,
            ) {
                if (layer.visible) Icon(Icons.Filled.Check, "표시", tint = if (primary) UI.accent else Color.Black, modifier = Modifier.size(20.dp))
                else if (ed.soloLayer === layer) Icon(Icons.Filled.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (swipedOpen) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)), horizontalArrangement = Arrangement.End) {
                SwipeAction(if (layer.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, if (layer.locked) "잠금 해제" else "잠금", Color(0xFF5A5A62)) { onSwipeAction(0) }
                SwipeAction(Icons.Filled.ContentCopy, "복제", Color(0xFF3A6FD8)) { onSwipeAction(1) }
                SwipeAction(Icons.Filled.Delete, "삭제", Color(0xFFD64545)) { onSwipeAction(2) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).fillMaxHeight().background(color).combinedClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun Thumb(layer: Layer, fineChecker: Boolean, selected: Boolean, dim: Float, black: Boolean = false) {
    val thumb = layer.thumbnail()
    val ratio = thumb.width.toFloat() / thumb.height
    Box(Modifier.size(width = (46f * ratio.coerceIn(0.6f, 1.6f)).dp, height = 46.dp).clip(RoundedCornerShape(4.dp))
        .then(if (black) Modifier.background(Color.Black) else Modifier.checkerboard(if (fineChecker) 4.dp else 6.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0x33000000), RoundedCornerShape(4.dp))) {
        Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit, alpha = dim, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun BlendEditor(ed: Editor, layer: Layer) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E21)).padding(10.dp)) {
        Text(layer.name, color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        LabeledSlider("불투명도", layer.opacity, 0f..1f, "${(layer.opacity * 100).roundToInt()}%", { ed.setOpacityLive(layer, it) }, { ed.commitOpacity(layer) })
        Column {
            if (layer.isGroup) {
                val sel = layer.passThrough
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel) UI.accentDim else Color.Transparent)
                    .combinedClickableCompat { ed.setPassThrough(layer, true) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text("통과 (그룹 아래와 혼합)", color = UI.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("P", color = UI.text2, fontSize = 12.sp)
                }
                HorizontalDivider(color = UI.divider, modifier = Modifier.padding(vertical = 3.dp))
            }
            var group = -1
            LayerBlend.entries.forEach { m ->
                if (m.group != group) { if (group >= 0) HorizontalDivider(color = UI.divider, modifier = Modifier.padding(vertical = 3.dp)); group = m.group }
                val sel = layer.blend == m && !(layer.isGroup && layer.passThrough)
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
private fun item(t: String, checked: Boolean? = null, enabled: Boolean = true, close: () -> Unit, f: () -> Unit) =
    DropdownMenuItem(text = { Text(t + if (checked == true) "  ✓" else "") }, enabled = enabled, onClick = { close(); f() })

@Composable
private fun LayerOptions(ed: Editor, layer: Layer, close: () -> Unit, rename: () -> Unit) {
    val doc = ed.doc
    val i = doc.indexOf(layer)
    val sib = doc.children(layer.parentId)
    val below = sib.getOrNull(sib.indexOf(layer) - 1)
    item("이름 변경", close = close) { rename() }
    if (layer.isText) {
        item("텍스트 편집", close = close) { ed.beginTextEdit(layer) }
        item("래스터화", close = close) { ed.rasterizeText(layer) }
    }
    item("선택", close = close) { ed.selectLayerAlpha(layer) }
    item("복사", close = close) { ed.copy() }
    item("레이어 채우기", close = close) { ed.fillLayer(layer) }
    item("지우기", close = close) { ed.clearLayer(layer) }
    HorizontalDivider()
    item("알파 채널 잠금", layer.alphaLock, close = close) { ed.toggleAlphaLock(layer) }
    item(if (layer.mask != null) "마스크 편집" else "마스크", close = close) { ed.addMask(layer) }
    item("클리핑 마스크", layer.clipping, enabled = below != null, close = close) { ed.toggleClipping(layer) }
    item("반전", close = close) { ed.invertLayer(layer) }
    item("참조", layer.isReference, close = close) { ed.toggleReference(layer) }
    item("비공개", layer.isPrivate, close = close) { ed.togglePrivate(layer) }
    item("잠금", layer.locked, close = close) { ed.toggleLock(layer) }
    HorizontalDivider()
    item("아래 레이어와 병합", enabled = below != null && !below.isGroup, close = close) { ed.mergeDown(i) }
    item("아래 레이어와 결합 (그룹)", enabled = below != null, close = close) { below?.let { ed.createGroup(listOf(it, layer)) } }
    item("복제", close = close) { ed.duplicateLayer(i) }
    item("가로로 뒤집기", close = close) { ed.flipLayer(true) }
    item("세로로 뒤집기", close = close) { ed.flipLayer(false) }
    item("삭제", close = close) { ed.deleteLayer(i) }
}

@Composable
private fun GroupOptions(ed: Editor, g: Layer, close: () -> Unit, rename: () -> Unit) {
    val i = ed.doc.indexOf(g)
    item("이름 변경", close = close) { rename() }
    item("선택", close = close) { ed.selectLayerAlpha(g) }
    item("복사", close = close) { ed.copy() }
    HorizontalDivider()
    item(if (g.mask != null) "마스크 편집" else "마스크", close = close) { ed.addMask(g) }
    item("비공개", g.isPrivate, close = close) { ed.togglePrivate(g) }
    item("잠금", g.locked, close = close) { ed.toggleLock(g) }
    HorizontalDivider()
    item("그룹 병합", close = close) { ed.flattenGroup(g) }
    item("그룹 해제", close = close) { ed.ungroup(g) }
    item("복제", close = close) { ed.duplicateLayer(i) }
    item("가로로 뒤집기", close = close) { ed.flipLayer(true) }
    item("세로로 뒤집기", close = close) { ed.flipLayer(false) }
    item("삭제", close = close) { ed.deleteLayer(i) }
}

@Composable
private fun MaskOptions(ed: Editor, owner: Layer, close: () -> Unit) {
    val m = owner.mask ?: return
    item("마스크 반전", close = close) { ed.invertLayer(m) }
    item("마스크 초기화", close = close) { ed.clearLayer(m) }
    item("마스크에서 선택", close = close) { ed.selectLayerAlpha(m) }
    item("마스크 적용", enabled = !owner.isGroup, close = close) { ed.applyMask(owner) }
    item("마스크 삭제", close = close) { ed.deleteMask(owner) }
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
            Text("탭: 색상 선택 · 체크 해제: 투명 배경", color = UI.text2, fontSize = 11.sp)
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

