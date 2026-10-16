package com.prosketch.app.ui.editor

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.prosketch.app.ui.canvas.CanvasView
import com.prosketch.app.ui.common.IconBtn
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.VerticalSlider
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun EditorScreen(ed: Editor, onCanvasView: (CanvasView) -> Unit, onExit: () -> Unit) {
    var canvasView by remember { mutableStateOf<CanvasView?>(null) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    BackHandler {
        when {
            ed.panel != null -> ed.panel = null
            ed.mode != Mode.PAINT -> ed.exitModes()
            ed.fullscreen -> ed.fullscreen = false
            else -> ed.close(onExit)
        }
    }

    Box(Modifier.fillMaxSize().background(UI.bg)) {
        AndroidView(
            factory = { ctx -> CanvasView(ctx, ed).also { canvasView = it; onCanvasView(it) } },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { canvasOrigin = it.boundsInRoot().topLeft },
        )

        if (!ed.fullscreen) {
            TopBar(ed, Modifier.align(Alignment.TopCenter), onExit = { ed.close(onExit) }, colorDrop = { x, y, phase ->
                val v = canvasView ?: return@TopBar
                val lx = x - canvasOrigin.x; val ly = y - canvasOrigin.y
                when (phase) { 0, 1 -> v.colorDropMove(lx, ly); 2 -> v.colorDropEnd(lx, ly, false); else -> v.colorDropEnd(lx, ly, true) }
            })
            if (ed.mode == Mode.PAINT || ed.mode == Mode.SELECT) Sidebar(ed, Modifier.align(if (ed.prefs.rightHanded) Alignment.CenterEnd else Alignment.CenterStart))

            val bottom = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 12.dp, end = 12.dp)
            when (ed.mode) {
                Mode.SELECT -> SelectionBar(ed, bottom)
                Mode.TRANSFORM -> TransformBar(ed, bottom)
                Mode.ADJUST -> AdjustBar(ed, bottom)
                Mode.LIQUIFY -> LiquifyBar(ed, bottom)
                Mode.GUIDE_EDIT -> GuideBar(ed, bottom)
                Mode.PAINT -> if (ed.doc.animation.enabled) AnimationTimeline(ed, bottom)
            }
        } else {
            Box(Modifier.align(Alignment.TopStart).padding(10.dp).size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x66000000))
                .clickable { ed.fullscreen = false }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Fullscreen, "전체 화면 해제", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        if (ed.copyPasteMenu) CopyPasteMenu(ed, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
        if (ed.referenceVisible) ReferenceWindow(ed)

        // panels + scrim
        val panel = ed.panel
        if (panel != null) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { ed.panel = null } })
            val top = Modifier.padding(top = 56.dp, start = 12.dp, end = 12.dp)
            when (panel) {
                Panel.BRUSHES -> BrushLibraryPanel(ed, top.align(Alignment.TopEnd).width(520.dp))
                Panel.BRUSH_STUDIO -> BrushStudioPanel(ed, top.align(Alignment.TopCenter).widthIn(max = 940.dp).fillMaxWidth())
                Panel.LAYERS -> LayersPanel(ed, top.align(Alignment.TopEnd).width(360.dp))
                Panel.COLOR -> ColorPanel(ed, top.align(Alignment.TopEnd).width(380.dp))
                Panel.ACTIONS -> ActionsPanel(ed, top.align(Alignment.TopStart).width(470.dp))
                Panel.ADJUSTMENTS -> AdjustmentsMenu(ed, top.align(Alignment.TopStart).padding(start = 40.dp).width(300.dp))
            }
        }

        Hud(ed, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        ed.zoomText?.let { Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) }
        ed.busy?.let { BusyOverlay(it) }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE6202022)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun Hud(ed: Editor, modifier: Modifier) {
    val h = ed.hud
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(h) {
        if (h == null) return@LaunchedEffect
        visible = true
        delay(if (h.action != null) 3500 else 1500)
        visible = false
    }
    AnimatedVisibility(visible && h != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE6202022)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h?.text ?: "", color = Color.White, fontSize = 13.sp)
            h?.action?.let { (label, f) ->
                Spacer(Modifier.width(12.dp))
                Text(label, color = UI.accent, fontSize = 13.sp, modifier = Modifier.clickable { f(); visible = false })
            }
        }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(UI.panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = UI.accent)
            Spacer(Modifier.height(12.dp))
            Text(text, color = UI.text)
        }
    }
}

/** 0 = start/move, 2 = drop, 3 = cancel. */
private typealias ColorDropCallback = (Float, Float, Int) -> Unit

@Composable
private fun TopBar(ed: Editor, modifier: Modifier, onExit: () -> Unit, colorDrop: ColorDropCallback) {
    Row(
        modifier.fillMaxWidth().height(50.dp).background(UI.bar).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("갤러리", color = UI.text, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 8.dp))
        Spacer(Modifier.width(4.dp))
        IconBtn(Icons.Filled.Build, "동작", selected = ed.panel == Panel.ACTIONS) { ed.panel = if (ed.panel == Panel.ACTIONS) null else Panel.ACTIONS }
        IconBtn(Icons.Filled.AutoFixHigh, "조정", selected = ed.panel == Panel.ADJUSTMENTS || ed.mode == Mode.ADJUST || ed.mode == Mode.LIQUIFY) {
            if (ed.mode == Mode.ADJUST || ed.mode == Mode.LIQUIFY) ed.adjustTool.apply()
            else ed.panel = if (ed.panel == Panel.ADJUSTMENTS) null else Panel.ADJUSTMENTS
        }
        IconBtn(Icons.Filled.Gesture, "선택", selected = ed.mode == Mode.SELECT) {
            if (ed.mode == Mode.SELECT) ed.exitModes() else ed.selectionTool.begin()
        }
        IconBtn(Icons.Filled.NearMe, "변형", selected = ed.mode == Mode.TRANSFORM) {
            if (ed.mode == Mode.TRANSFORM) ed.transformTool.commit() else { ed.exitModes(); ed.transformTool.begin() }
        }
        Spacer(Modifier.weight(1f))
        Text(ed.doc.name, color = UI.text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.widthIn(max = 200.dp))
        Spacer(Modifier.weight(1f))
        ToolButton(ed, Tool.BRUSH) { tint, m -> Icon(Icons.Filled.Brush, "페인트", tint = tint, modifier = m) }
        ToolButton(ed, Tool.SMUDGE) { tint, m -> Icon(Icons.Filled.TouchApp, "문지르기", tint = tint, modifier = m) }
        ToolButton(ed, Tool.ERASER) { tint, m -> EraserIcon(tint, m) }
        IconBtn(Icons.Filled.Layers, "레이어", selected = ed.panel == Panel.LAYERS) { ed.panel = if (ed.panel == Panel.LAYERS) null else Panel.LAYERS }
        Spacer(Modifier.width(6.dp))
        ColorButton(ed, colorDrop)
    }
}

@Composable
private fun ToolButton(ed: Editor, t: Tool, icon: @Composable (Color, Modifier) -> Unit) {
    val sel = ed.tool == t && ed.mode == Mode.PAINT
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) UI.accent.copy(alpha = 0.22f) else Color.Transparent)
            .pointerInput(t) {
                detectTapGestures(
                    onTap = { ed.selectTool(t) },
                    onLongPress = { if (ed.tool != t) ed.selectToolWithCurrentBrush(t) },
                )
            },
        contentAlignment = Alignment.Center,
    ) { icon(if (sel) UI.accent else UI.text, Modifier.size(23.dp)) }
}

@Composable
private fun EraserIcon(tint: Color, m: Modifier) {
    Canvas(m) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            moveTo(w * 0.15f, h * 0.62f); lineTo(w * 0.55f, h * 0.18f); lineTo(w * 0.88f, h * 0.48f); lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.3f, h * 0.88f); close()
        }
        drawPath(body, tint, style = Stroke(w * 0.09f))
        drawLine(tint, Offset(w * 0.32f, h * 0.44f), Offset(w * 0.68f, h * 0.76f), strokeWidth = w * 0.09f)
        drawLine(tint, Offset(w * 0.3f, h * 0.88f), Offset(w * 0.92f, h * 0.88f), strokeWidth = w * 0.07f)
    }
}

@Composable
private fun ColorButton(ed: Editor, colorDrop: ColorDropCallback) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val cb by rememberUpdatedState(colorDrop)
    val slop = with(LocalDensity.current) { 10.dp.toPx() }
    Box(
        Modifier.size(34.dp).onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .clip(CircleShape).background(Color(ed.color)).border(2.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val t0 = SystemClock.uptimeMillis()
                    var dragging = false
                    var longDone = false
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        last = c.position
                        if (!c.pressed) break
                        if (!dragging && (c.position - down.position).getDistance() > slop) { dragging = true; ed.panel = null }
                        if (dragging) { cb(origin.x + c.position.x, origin.y + c.position.y, 1); c.consume() }
                        else if (!longDone && SystemClock.uptimeMillis() - t0 > 550) { longDone = true; ed.swapWithPrevious() }
                    }
                    if (dragging) cb(origin.x + last.x, origin.y + last.y, 2)
                    else if (!longDone) ed.panel = if (ed.panel == Panel.COLOR) null else Panel.COLOR
                }
            },
    )
}

@Composable
private fun Sidebar(ed: Editor, modifier: Modifier) {
    val t = ed.tool
    var showPreview by remember { mutableStateOf<Int>(0) } // 0 none, 1 size, 2 opacity
    val density = LocalDensity.current
    Box(modifier.padding(horizontal = 6.dp)) {
        Column(
            Modifier.width(48.dp).clip(RoundedCornerShape(14.dp)).background(UI.bar).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VerticalSlider(ed.toolSize[t] ?: 0.3f, Modifier.width(40.dp).height(170.dp),
                onStart = { showPreview = 1 }, onEnd = { showPreview = 0; ed.saveToolState() }) { ed.toolSize[t] = it }
            Spacer(Modifier.height(12.dp))
            // Modify button: tap = floating eyedropper, hold = eyedropper while held
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (ed.eyedropperArmed || ed.eyedropperHeld) UI.accent else Color(0xFF4A4A50))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val t0 = SystemClock.uptimeMillis()
                            var held = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.all { !it.pressed }) break
                                if (!held && SystemClock.uptimeMillis() - t0 > 250) { held = true; ed.eyedropperHeld = true }
                            }
                            if (held) ed.eyedropperHeld = false else ed.eyedropperArmed = !ed.eyedropperArmed
                        }
                    },
            )
            Spacer(Modifier.height(12.dp))
            VerticalSlider(ed.toolOpacity[t] ?: 1f, Modifier.width(40.dp).height(170.dp),
                onStart = { showPreview = 2 }, onEnd = { showPreview = 0; ed.saveToolState() }) { ed.toolOpacity[t] = it }
            Spacer(Modifier.height(12.dp))
            RepeatButton(enabled = ed.canUndo, onFire = { ed.undo() }) { tint -> Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소", tint = tint) }
            RepeatButton(enabled = ed.canRedo, onFire = { ed.redo() }) { tint -> Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행", tint = tint) }
        }
        if (showPreview != 0) {
            val dx = with(density) { (if (ed.prefs.rightHanded) -170 else 64).dp.roundToPx() }
            Box(Modifier.offset { IntOffset(dx, 0) }.size(150.dp).clip(RoundedCornerShape(16.dp)).background(UI.panel), contentAlignment = Alignment.Center) {
                if (showPreview == 1) {
                    val px = ed.brushDocSize(1f, t)
                    val dpSize = with(density) { (px.coerceAtMost(130f * this.density)).toDp() }
                    Box(Modifier.size(dpSize.coerceAtLeast(2.dp)).clip(CircleShape).background(Color(ed.color)))
                    Text("${((ed.toolSize[t] ?: 0f) * 100).roundToInt()}% · ${px.roundToInt()}px", color = UI.text2, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
                } else {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(Color(ed.color).copy(alpha = ed.toolOpacity[t] ?: 1f)))
                    Text("${((ed.toolOpacity[t] ?: 0f) * 100).roundToInt()}%", color = UI.text2, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
                }
            }
        }
    }
}

/** Tap fires once; holding fires repeatedly (rapid undo / redo). */
@Composable
private fun RepeatButton(enabled: Boolean, onFire: () -> Unit, icon: @Composable (Color) -> Unit) {
    val fire by rememberUpdatedState(onFire)
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(450)
        while (pressed) { fire(); delay(90) }
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                pressed = true
                val t0 = SystemClock.uptimeMillis()
                while (true) { val ev = awaitPointerEvent(); if (ev.changes.all { !it.pressed }) break }
                pressed = false
                if (SystemClock.uptimeMillis() - t0 < 450) fire()
            }
        },
        contentAlignment = Alignment.Center,
    ) { icon(if (enabled) UI.text else UI.text2.copy(alpha = 0.4f)) }
}

