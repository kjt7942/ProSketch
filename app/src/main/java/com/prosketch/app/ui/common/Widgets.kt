package com.prosketch.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object UI {
    val bg = Color(0xFF1C1C1E)
    val bar = Color(0xE61A1A1C)
    val panel = Color(0xFF262628)
    val panel2 = Color(0xFF323236)
    val divider = Color(0x22FFFFFF)
    val text = Color(0xFFEDEDED)
    val text2 = Color(0xFF9A9AA0)
    val accent = Color(0xFF2F8CFF)
    val accentDim = Color(0xFF1D4F8F)
    val danger = Color(0xFFFF5A5F)
    val green = Color(0xFF30C46C)

    val scheme: ColorScheme = darkColorScheme(
        primary = accent, onPrimary = Color.White, surface = Color(0xFF262628), onSurface = text,
        background = bg, onBackground = text, secondary = accent, surfaceVariant = panel2,
    )
}

@Composable
fun ProTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = UI.scheme, content = content)

/** Rounded floating panel used for all popovers. */
@Composable
fun PanelBox(modifier: Modifier = Modifier, title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .shadow(18.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(UI.panel)
            // swallow taps so they don't reach the scrim
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(14.dp),
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = UI.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, selected: Boolean = false, enabled: Boolean = true, size: Dp = 40.dp, tint: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp))
            .background(if (selected) UI.accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint ?: if (!enabled) UI.text2.copy(alpha = 0.4f) else if (selected) UI.accent else UI.text, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
fun TextBtn(text: String, selected: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) UI.accent else UI.panel2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else UI.text2, fontSize = 13.sp, maxLines = 1) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(UI.panel2).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (i == selected) UI.accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (i == selected) Color.White else UI.text, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = UI.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = UI.text2, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            onValueChangeFinished = onDone, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = UI.accent, inactiveTrackColor = UI.panel2),
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = UI.text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = UI.text2, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = UI.accent))
    }
}

@Composable
fun MenuRow(label: String, icon: ImageVector? = null, sub: String? = null, danger: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (danger) UI.danger else UI.text, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = if (danger) UI.danger else UI.text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = UI.text2, fontSize = 11.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String) = Text(text, color = UI.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

fun Modifier.checkerboard(cell: Dp = 6.dp): Modifier = drawBehind {
    val c = cell.toPx()
    drawRect(Color.White)
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else c
        while (x < size.width) { drawRect(Color(0xFFD0D0D0), Offset(x, y), Size(c, c)); x += 2 * c }
        y += c; row++
    }
}

/** Procreate-style vertical sidebar slider (value 0..1, top = 1). */
@Composable
fun VerticalSlider(value: Float, modifier: Modifier = Modifier, onStart: () -> Unit = {}, onEnd: () -> Unit = {}, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var fine by remember { mutableStateOf(1f) }
    val cur by androidx.compose.runtime.rememberUpdatedState(value)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { o -> start(); change((1f - o.y / size.height).coerceIn(0f, 1f)); end() }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true; start() },
                    onDragEnd = { dragging = false; fine = 1f; end() },
                    onDragCancel = { dragging = false; fine = 1f; end() },
                ) { ch, drag ->
                    ch.consume()
                    // drag sideways away from the slider for finer control
                    val away = kotlin.math.abs(ch.position.x - size.width / 2f)
                    fine = (1f / (1f + away / 60f)).coerceIn(0.08f, 1f)
                    change((cur - drag.y / size.height * fine).coerceIn(0f, 1f))
                }
            },
    ) {
        val w = size.width
        val trackW = w * 0.42f
        val left = (w - trackW) / 2
        drawRoundRect(Color(0xFF3A3A3E), Offset(left, 0f), Size(trackW, size.height), CornerRadius(trackW / 2))
        val fillH = size.height * value
        drawRoundRect(Color(0xFF6F6F76), Offset(left, size.height - fillH), Size(trackW, fillH), CornerRadius(trackW / 2))
        val ty = size.height * (1f - value)
        drawRoundRect(Color.White, Offset(left - 3.dp.toPx(), (ty - 7.dp.toPx()).coerceIn(0f, size.height - 14.dp.toPx())), Size(trackW + 6.dp.toPx(), 14.dp.toPx()), CornerRadius(5.dp.toPx()))
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 28.dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).checkerboard(4.dp).background(color)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Color.White else Color(0x55FFFFFF), CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}
