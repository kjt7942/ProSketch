package com.prosketch.app.ui.editor

import com.prosketch.app.L

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.data.Palette
import com.prosketch.app.ui.common.ColorDot
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.Segmented
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.checkerboard
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun hsvOf(c: Int, hueKeep: Float): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c, hsv)
    if (hsv[1] < 0.01f || hsv[2] < 0.01f) hsv[0] = hueKeep
    return hsv
}

private fun hsvColor(h: Float, s: Float, v: Float) = android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

/** Continuous drag/tap handler that reports positions; [onEnd] fires on release. */
private fun Modifier.dragPositions(onPos: (Offset, Boolean, Size) -> Unit, onEnd: () -> Unit = {}): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPos(down.position, true, Size(size.width.toFloat(), size.height.toFloat()))
        down.consume()
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            onPos(ch.position, false, Size(size.width.toFloat(), size.height.toFloat()))
            ch.consume()
        }
        onEnd()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPanel(ed: Editor, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(ed.prefs.lastColorTab) }
    PanelBox(modifier, title = L.t("색상"), trailing = {
        // primary / previous swatches (tap previous to swap)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(34.dp, 24.dp).clip(RoundedCornerShape(6.dp)).background(Color(ed.color)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp)))
            Box(Modifier.size(34.dp, 24.dp).clip(RoundedCornerShape(6.dp)).background(Color(ed.previousColor)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); ed.swapWithPrevious() } })
        }
    }) {
        Box(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            when (tab) {
                0 -> DiscPicker(ed)
                1 -> ClassicPicker(ed)
                2 -> HarmonyPicker(ed)
                3 -> ValuePicker(ed)
                else -> PalettesView(ed)
            }
        }
        if (tab != 4) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.t("최근"), color = UI.text2, fontSize = 11.sp, modifier = Modifier.width(34.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ed.recentColors.take(10).forEach { c -> ColorDot(Color(c), 24.dp) { ed.setColor(c) } }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ed.palettes.active.name, color = UI.text2, fontSize = 11.sp, modifier = Modifier.width(34.dp), maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ed.palettes.active.colors.take(20).forEach { c -> ColorDot(Color(c), 24.dp) { ed.setColor(c) } }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Segmented(listOf(L.t("디스크"), L.t("클래식"), L.t("하모니"), L.t("값"), L.t("팔레트")), tab, Modifier.fillMaxWidth()) { tab = it; ed.prefs.lastColorTab = it }
    }
}

@Composable
private fun DiscPicker(ed: Editor) {
    val hsv = hsvOf(ed.color, ed.hue)
    val edRef by rememberUpdatedState(ed)
    var mode by remember { mutableIntStateOf(0) } // 0 none, 1 ring, 2 disc
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp).dragPositions({ p, first, size ->
            val e = edRef
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val d = hypot(p.x - c.x, p.y - c.y)
            val ringIn = r * 0.80f
            if (first) mode = if (d >= ringIn * 0.97f) 1 else 2
            val cur = hsvOf(e.color, e.hue)
            if (mode == 1) {
                val h = (Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f + 360f) % 360f
                e.hue = h
                e.setColor(hsvColor(h, cur[1], cur[2]), commitHistory = false)
            } else {
                val inner = ringIn * 0.92f
                val s = ((p.x - (c.x - inner)) / (2 * inner)).coerceIn(0f, 1f)
                val v = (1f - (p.y - (c.y - inner)) / (2 * inner)).coerceIn(0f, 1f)
                e.setColor(hsvColor(e.hue, s, v), commitHistory = false)
            }
        }, onEnd = { edRef.pushRecentColor(edRef.color) }),
    ) {
        val r = min(size.width, size.height) / 2f
        val c = center
        val ringW = r * 0.18f
        rotate(-90f, c) {
            drawCircle(
                Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red).reversed(), c),
                radius = r - ringW / 2, style = Stroke(ringW),
            )
        }
        // rotate so red is at the top: sweepGradient starts at 3 o'clock → draw marker accordingly
        val inner = r * 0.80f * 0.92f
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, inner)) }
        clipPath(disc) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvColor(ed.hue, 1f, 1f))), c.x - inner, c.x + inner), Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), c.y - inner, c.y + inner), Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2))
        }
        // hue marker
        val ha = Math.toRadians((ed.hue - 90f).toDouble())
        val hm = Offset(c.x + cos(ha).toFloat() * (r - ringW / 2), c.y + sin(ha).toFloat() * (r - ringW / 2))
        drawCircle(Color.White, ringW * 0.42f, hm, style = Stroke(3.dp.toPx()))
        // SB reticle
        var sx = c.x - inner + hsv[1] * 2 * inner
        var sy = c.y - inner + (1f - hsv[2]) * 2 * inner
        // keep the reticle inside the disc
        val dd = hypot(sx - c.x, sy - c.y)
        if (dd > inner) { sx = c.x + (sx - c.x) / dd * inner; sy = c.y + (sy - c.y) / dd * inner }
        drawCircle(Color(ed.color), 11.dp.toPx(), Offset(sx, sy))
        drawCircle(Color.White, 11.dp.toPx(), Offset(sx, sy), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun GradientSlider(value: Float, colors: List<Color>, onChange: (Float) -> Unit, onEnd: () -> Unit) {
    val cb by rememberUpdatedState(onChange)
    Canvas(Modifier.fillMaxWidth().height(30.dp).padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).checkerboard(5.dp)
        .dragPositions({ p, _, size -> cb((p.x / size.width).coerceIn(0f, 1f)) }, onEnd)) {
        drawRect(Brush.horizontalGradient(colors))
        val x = value * size.width
        drawCircle(Color.White, size.height * 0.42f, Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun ClassicPicker(ed: Editor) {
    val hsv = hsvOf(ed.color, ed.hue)
    val edRef by rememberUpdatedState(ed)
    Column {
        Canvas(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)).dragPositions({ p, _, size ->
            val s = (p.x / size.width).coerceIn(0f, 1f); val v = (1f - p.y / size.height).coerceIn(0f, 1f)
            edRef.setColor(hsvColor(edRef.hue, s, v), commitHistory = false)
        }, { edRef.pushRecentColor(edRef.color) })) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvColor(ed.hue, 1f, 1f)))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
            drawCircle(Color.White, 10.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        }
        Spacer(Modifier.height(8.dp))
        val end = { ed.pushRecentColor(ed.color) }
        GradientSlider(ed.hue / 360f, (0..6).map { Color(hsvColor(it * 60f, 1f, 1f)) }, { ed.hue = it * 360f; ed.setColor(hsvColor(it * 360f, hsv[1], hsv[2]), false) }, end)
        GradientSlider(hsv[1], listOf(Color(hsvColor(ed.hue, 0f, hsv[2])), Color(hsvColor(ed.hue, 1f, hsv[2]))), { ed.setColor(hsvColor(ed.hue, it, hsv[2]), false) }, end)
        GradientSlider(hsv[2], listOf(Color.Black, Color(hsvColor(ed.hue, hsv[1], 1f))), { ed.setColor(hsvColor(ed.hue, hsv[1], it), false) }, end)
    }
}

private val harmonyModes = listOf(L.t("보색"), L.t("분할 보색"), L.t("유사색"), L.t("삼원색"), L.t("사원색"))
private fun harmonyOffsets(m: Int) = when (m) {
    0 -> listOf(0f, 180f); 1 -> listOf(0f, 150f, 210f); 2 -> listOf(0f, -30f, 30f); 3 -> listOf(0f, 120f, 240f); else -> listOf(0f, 90f, 180f, 270f)
}

@Composable
private fun HarmonyPicker(ed: Editor) {
    val hsv = hsvOf(ed.color, ed.hue)
    var mode by remember { mutableIntStateOf(ed.prefs.harmonyMode) }
    var menu by remember { mutableStateOf(false) }
    val edRef by rememberUpdatedState(ed)
    Column {
        Box {
            TextBtn(harmonyModes[mode] + " ▾") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                harmonyModes.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { mode = i; ed.prefs.harmonyMode = i; menu = false }) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(230.dp).padding(6.dp).dragPositions({ p, _, size ->
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val h = (Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f + 360f) % 360f
            val s = (hypot(p.x - c.x, p.y - c.y) / r).coerceIn(0f, 1f)
            edRef.hue = h
            edRef.setColor(hsvColor(h, s, hsvOf(edRef.color, edRef.hue)[2].coerceAtLeast(0.05f)), false)
        }, { edRef.pushRecentColor(edRef.color) })) {
            val r = min(size.width, size.height) / 2f
            val c = center
            rotate(-90f, c) { drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red).reversed(), c), r, c) }
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, r), r, c)
            drawCircle(Color.Black.copy(alpha = 1f - hsv[2]), r, c)
            harmonyOffsets(mode).forEachIndexed { i, off ->
                val a = Math.toRadians((ed.hue + off - 90f).toDouble())
                val pt = Offset(c.x + cos(a).toFloat() * r * hsv[1], c.y + sin(a).toFloat() * r * hsv[1])
                drawCircle(Color(hsvColor(ed.hue + off, hsv[1], hsv[2])), if (i == 0) 12.dp.toPx() else 9.dp.toPx(), pt)
                drawCircle(Color.White, if (i == 0) 12.dp.toPx() else 9.dp.toPx(), pt, style = Stroke(2.5.dp.toPx()))
            }
        }
        GradientSlider(hsv[2], listOf(Color.Black, Color(hsvColor(ed.hue, hsv[1], 1f))), { ed.setColor(hsvColor(ed.hue, hsv[1], it), false) }, { ed.pushRecentColor(ed.color) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            harmonyOffsets(mode).forEach { off ->
                val c = hsvColor(ed.hue + off, hsv[1], hsv[2])
                Box(Modifier.size(44.dp, 30.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                    .pointerInput(c) { awaitEachGesture { awaitFirstDown().consume(); edRef.setColor(c) } })
            }
        }
    }
}

@Composable
private fun ValuePicker(ed: Editor) {
    val hsv = hsvOf(ed.color, ed.hue)
    val r = android.graphics.Color.red(ed.color); val g = android.graphics.Color.green(ed.color); val b = android.graphics.Color.blue(ed.color)
    val end = { ed.pushRecentColor(ed.color) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        @Composable
        fun row(label: String, value: Float, text: String, colors: List<Color>, f: (Float) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = UI.text2, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                Box(Modifier.weight(1f)) { GradientSlider(value, colors, f, end) }
                Text(text, color = UI.text, fontSize = 12.sp, modifier = Modifier.width(48.dp).padding(start = 8.dp))
            }
        }
        row("H", ed.hue / 360f, "${ed.hue.roundToInt()}°", (0..6).map { Color(hsvColor(it * 60f, 1f, 1f)) }) { ed.hue = it * 360f; ed.setColor(hsvColor(it * 360f, hsv[1], hsv[2]), false) }
        row("S", hsv[1], "${(hsv[1] * 100).roundToInt()}%", listOf(Color(hsvColor(ed.hue, 0f, hsv[2])), Color(hsvColor(ed.hue, 1f, hsv[2])))) { ed.setColor(hsvColor(ed.hue, it, hsv[2]), false) }
        row("B", hsv[2], "${(hsv[2] * 100).roundToInt()}%", listOf(Color.Black, Color(hsvColor(ed.hue, hsv[1], 1f)))) { ed.setColor(hsvColor(ed.hue, hsv[1], it), false) }
        Spacer(Modifier.height(6.dp))
        row("R", r / 255f, "$r", listOf(Color(0, g, b), Color(255, g, b))) { ed.setColor(android.graphics.Color.rgb((it * 255).roundToInt(), g, b), false) }
        row("G", g / 255f, "$g", listOf(Color(r, 0, b), Color(r, 255, b))) { ed.setColor(android.graphics.Color.rgb(r, (it * 255).roundToInt(), b), false) }
        row("B", b / 255f, "$b", listOf(Color(r, g, 0), Color(r, g, 255))) { ed.setColor(android.graphics.Color.rgb(r, g, (it * 255).roundToInt()), false) }
        Spacer(Modifier.height(8.dp))
        var hex by remember(ed.color) { mutableStateOf(String.format("%06X", ed.color and 0xFFFFFF)) }
        OutlinedTextField(
            value = hex, singleLine = true, label = { Text(L.t("16진수")) },
            onValueChange = { v ->
                hex = v.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                if (hex.length == 6) ed.setColor((0xFF000000 or hex.toLong(16)).toInt())
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PalettesView(ed: Editor) {
    var refresh by remember { mutableIntStateOf(0) }
    val store = ed.palettes
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun addPalette(name: String, colors: List<Int>) {
        store.palettes.add(0, Palette("p_" + System.nanoTime(), name.ifBlank { L.t("무제 팔레트") }, colors.take(60).toMutableList()))
        store.activeId = store.palettes[0].id
        store.save(); refresh++
    }
    val fileImport = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "palette"
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val list = com.prosketch.app.data.PaletteIO.read(bytes, name)
            list.forEach { addPalette(it.name, it.colors) }
            ed.hud(L.tf("팔레트 {0}개를 가져왔습니다", list.size))
        }.onFailure { ed.hud(it.message ?: L.t("팔레트를 가져올 수 없습니다")) }
    }
    val photoImport = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
            val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val k = minOf(1f, 640f / maxOf(info.size.width, info.size.height))
                d.setTargetSize(maxOf(1, (info.size.width * k).toInt()), maxOf(1, (info.size.height * k).toInt()))
            }
            val colors = com.prosketch.app.data.PaletteIO.fromImage(bmp)
            bmp.recycle()
            addPalette(L.t("사진 팔레트"), colors)
            ed.hud(L.tf("사진에서 색상 {0}개를 뽑았습니다", colors.size))
        }.onFailure { ed.hud(L.t("이미지를 열 수 없습니다")) }
    }
    fun share(p: Palette) {
        runCatching {
            val f = java.io.File(com.prosketch.app.data.Exporter.exportDir(ctx), com.prosketch.app.data.Exporter.safeName(p.name) + ".swatches")
            f.outputStream().use { com.prosketch.app.data.PaletteIO.writeSwatches(p.name, p.colors, it) }
            com.prosketch.app.data.Exporter.share(ctx, f, "application/zip")
        }
    }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        refresh.let { }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L.t("팔레트"), color = UI.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TextBtn(L.t("+ 새 팔레트")) { addPalette(L.t("무제 팔레트"), emptyList()) }
            TextBtn(L.t("파일에서")) { fileImport.launch(arrayOf("*/*")) }
            TextBtn(L.t("사진에서")) { photoImport.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        for (p in store.palettes.toList()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val active = p.id == store.activeId
                Text((if (active) "✓ " else "") + p.name, color = if (active) UI.accent else UI.text, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    .combinedClickable(onClick = { store.activeId = p.id; store.save(); refresh++ }))
                Icon(Icons.Filled.Share, L.t("공유"), tint = UI.text2, modifier = Modifier.padding(end = 10.dp).size(18.dp).combinedClickable(onClick = { share(p) }))
                if (store.palettes.size > 1) Icon(Icons.Filled.Delete, L.t("삭제"), tint = UI.text2, modifier = Modifier.size(18.dp)
                    .combinedClickable(onClick = {}, onLongClick = { store.palettes.remove(p); if (store.activeId == p.id) store.activeId = store.palettes[0].id; store.save(); refresh++ }))
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                p.colors.toList().forEachIndexed { i, c ->
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).background(Color(c))
                        .combinedClickable(onClick = { ed.setColor(c); store.activeId = p.id; store.save(); refresh++ },
                            onLongClick = { p.colors.removeAt(i); store.save(); refresh++; ed.hud(L.t("견본 삭제")) }))
                }
                if (p.colors.size < 30) Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, UI.text2, RoundedCornerShape(5.dp))
                    .combinedClickable(onClick = { p.colors.add(ed.color); store.save(); refresh++ }), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, L.t("현재 색상 추가"), tint = UI.text2, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(L.t("견본을 길게 눌러 삭제 · + 를 눌러 현재 색상 저장 · 가져오기: .swatches(Procreate), .ase, .aco · 공유는 .swatches"), color = UI.text2, fontSize = 11.sp)
    }
}

@Suppress("unused") private val keepPI = PI
@Suppress("unused") private fun Color.argb() = toArgb()
