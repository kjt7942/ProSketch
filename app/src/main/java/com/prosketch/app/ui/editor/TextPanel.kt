package com.prosketch.app.ui.editor

import com.prosketch.app.L

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.engine.Layer
import com.prosketch.app.engine.TextRenderer
import com.prosketch.app.engine.TextSpec
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.SectionLabel
import com.prosketch.app.ui.common.Segmented
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Imported fonts live in files/fonts (TTF, OTF, TTC). */
object Fonts {
    fun dir(ctx: android.content.Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }

    fun imported(ctx: android.content.Context): List<File> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.extension.lowercase() in setOf("ttf", "otf", "ttc") }.sortedBy { it.name.lowercase() }

    fun import(ctx: android.content.Context, uri: Uri): File? = runCatching {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "font.ttf"
        val ext = name.substringAfterLast('.', "ttf").lowercase()
        require(ext in setOf("ttf", "otf", "ttc")) { L.t("TTF, OTF, TTC 글꼴만 가져올 수 있습니다") }
        val f = File(dir(ctx), name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        // reject files Android cannot load
        android.graphics.Typeface.createFromFile(f)
        f
    }.getOrNull()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextPanel(ed: Editor, layer: Layer, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var spec by remember(layer) { mutableStateOf(layer.text ?: TextSpec("")) }
    var fontsRev by remember { mutableIntStateOf(0) }
    fun upd(s: TextSpec) { spec = s }
    // re-render after the state settles so fast typing is never blocked by full-canvas redraws
    LaunchedEffect(spec) { kotlinx.coroutines.delay(16); ed.previewText(spec) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = Fonts.import(ctx, uri)
            if (f == null) ed.hud(L.t("글꼴을 불러올 수 없습니다")) else { fontsRev++; upd(spec.copy(font = "file:" + f.absolutePath)); ed.hud(L.tf("글꼴 추가: {0}", f.nameWithoutExtension)) }
        }
    }
    val maxDim = max(ed.doc.width, ed.doc.height).toFloat()

    PanelBox(modifier, title = if (ed.textIsNew) L.t("텍스트 추가") else L.t("텍스트 편집"), trailing = {
        TextBtn(L.t("취소")) { ed.endTextEdit(false) }
        Spacer(Modifier.width(6.dp))
        TextBtn(L.t("완료"), true) { ed.previewText(spec); ed.endTextEdit(true) }
    }) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                spec.text, { upd(spec.copy(text = it)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = UI.text, unfocusedTextColor = UI.text),
            )
            SectionLabel(L.t("글꼴"))
            fontsRev.let { }
            val fonts = TextRenderer.systemFamilies + Fonts.imported(ctx).map { "file:" + it.absolutePath to it.nameWithoutExtension }
            Column(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)).background(UI.panel2).verticalScroll(rememberScrollState())) {
                fonts.forEach { (key, label) ->
                    val sel = spec.font == key
                    val family = remember(key) { FontFamily(TextRenderer.typeface(TextSpec("", font = key))) }
                    Row(Modifier.fillMaxWidth().background(if (sel) UI.accentDim else Color.Transparent)
                        .combinedClickable(onClick = { upd(spec.copy(font = key)) }).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = UI.text, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                        Text(L.t("가나다 Aa 123"), color = UI.text, fontSize = 17.sp, fontFamily = family, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextBtn(L.t("글꼴 가져오기 (TTF · OTF)")) { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")) }

            SectionLabel(L.t("스타일"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextBtn(L.t("굵게"), spec.bold) { upd(spec.copy(bold = !spec.bold)) }
                TextBtn(L.t("기울임"), spec.italic) { upd(spec.copy(italic = !spec.italic)) }
                TextBtn(L.t("밑줄"), spec.underline) { upd(spec.copy(underline = !spec.underline)) }
                TextBtn(L.t("외곽선"), spec.outline) { upd(spec.copy(outline = !spec.outline)) }
                TextBtn(L.t("세로쓰기"), spec.vertical) { upd(spec.copy(vertical = !spec.vertical)) }
                TextBtn(L.t("대문자"), spec.allCaps) { upd(spec.copy(allCaps = !spec.allCaps)) }
            }
            Spacer(Modifier.height(6.dp))
            Segmented(listOf(L.t("왼쪽"), L.t("가운데"), L.t("오른쪽"), L.t("양쪽")), spec.align, Modifier.fillMaxWidth()) { upd(spec.copy(align = it)) }

            SectionLabel(L.t("디자인"))
            LabeledSlider(L.t("크기"), spec.size, 8f..maxDim / 2f, "${spec.size.roundToInt()}px", { upd(spec.copy(size = it)) })
            LabeledSlider(L.t("자간"), spec.tracking, -0.2f..1f, "%.2f".format(spec.tracking), { upd(spec.copy(tracking = it)) })
            LabeledSlider(L.t("행간"), spec.leading, 0.6f..3f, "%.2f".format(spec.leading), { upd(spec.copy(leading = it)) })
            LabeledSlider(L.t("기준선"), spec.baseline, -spec.size..spec.size, "${spec.baseline.roundToInt()}px", { upd(spec.copy(baseline = it)) })
            val boxW = TextRenderer.wrapWidth(spec)
            LabeledSlider(L.t("상자 너비"), boxW, spec.size * 0.5f..ed.doc.width.toFloat() * 1.5f, if (spec.autoWidth) L.t("자동") else "${boxW.roundToInt()}px",
                { upd(spec.copy(boxWidth = it, autoWidth = false)) })
            LabeledSlider(L.t("불투명도"), layer.opacity, 0f..1f, "${(layer.opacity * 100).roundToInt()}%", { ed.setOpacityLive(layer, it) }, { ed.commitOpacity(layer) })

            SectionLabel(L.t("색상 · 위치"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(spec.color)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp)))
                TextBtn(L.t("현재 색상 적용")) { upd(spec.copy(color = ed.color)) }
                TextBtn(L.t("가운데로")) {
                    val (w, h) = TextRenderer.boxSize(spec)
                    upd(spec.copy(x = (ed.doc.width - w * spec.scale) / 2f, y = (ed.doc.height - h * spec.scale) / 2f, rotation = 0f))
                }
                TextBtn(L.t("너비 자동"), spec.autoWidth) { upd(spec.copy(autoWidth = !spec.autoWidth, boxWidth = TextRenderer.wrapWidth(spec))) }
            }
            Text(L.t("이동·크기·회전은 변형 도구로 조절합니다. 텍스트는 래스터화하기 전까지 계속 편집할 수 있습니다."),
                color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
