package com.prosketch.app.ui.editor

import com.prosketch.app.L

import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.data.ExportFormat
import com.prosketch.app.data.Exporter
import com.prosketch.app.data.FingerMode
import com.prosketch.app.data.PenButtonAction
import com.prosketch.app.data.PressureCurve
import com.prosketch.app.ui.common.LabeledSlider
import com.prosketch.app.ui.common.MenuRow
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.SectionLabel
import com.prosketch.app.ui.common.Segmented
import com.prosketch.app.ui.common.SwitchRow
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun decodeUri(ctx: android.content.Context, uri: Uri, maxDim: Int): android.graphics.Bitmap? = runCatching {
    val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
    android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        val s = minOf(1f, maxDim.toFloat() / maxOf(info.size.width, info.size.height))
        if (s < 1f) dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
    }
}.getOrNull()

fun runExport(ed: Editor, fmt: ExportFormat, toGallery: Boolean) {
    val ctx = ed.ctx
    ed.panel = null
    ed.busy = L.tf("{0} 내보내는 중…", fmt.label)
    ed.save {
        ed.scope.launch(Dispatchers.Default) {
            val result = runCatching { Exporter.export(ctx, ed.store, ed.doc, fmt) }
            withContext(Dispatchers.Main) {
                ed.busy = null
                result.onSuccess { f ->
                    if (toGallery) {
                        val uri = runCatching { Exporter.saveToGallery(ctx, f, fmt.mime) }.getOrNull()
                        ed.hud(if (uri != null) L.t("갤러리에 저장됨") else L.t("저장 실패"))
                    } else Exporter.share(ctx, f, fmt.mime)
                }.onFailure { ed.hud(L.tf("내보내기 실패: {0}", it.message ?: it.javaClass.simpleName)) }
            }
        }
    }
}

@Composable
fun ActionsPanel(ed: Editor, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var cropDialog by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf(false) }
    var purgeAsk by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) decodeUri(ctx, uri, maxOf(ed.doc.width, ed.doc.height))?.let { ed.insertImage(it); it.recycle() } ?: ed.hud(L.t("이미지를 열 수 없습니다"))
    }
    val privatePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) decodeUri(ctx, uri, maxOf(ed.doc.width, ed.doc.height))?.let { ed.insertImage(it, L.t("비공개 사진"), private = true); it.recycle() } ?: ed.hud(L.t("이미지를 열 수 없습니다"))
    }
    val fileInsert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) decodeUri(ctx, uri, maxOf(ed.doc.width, ed.doc.height))?.let { ed.insertImage(it, L.t("삽입된 파일")); it.recycle() } ?: ed.hud(L.t("이미지를 열 수 없습니다"))
    }
    val cameraFile = remember { java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }.let { java.io.File(it, "photo.jpg") } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) decodeUri(ctx, android.net.Uri.fromFile(cameraFile), maxOf(ed.doc.width, ed.doc.height))?.let { ed.insertImage(it, L.t("사진")); it.recycle() }
    }
    val refPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) decodeUri(ctx, uri, 1200)?.let { ed.referenceImage = it; ed.referenceVisible = true }
    }

    PanelBox(modifier, title = L.t("동작")) {
        Segmented(listOf(L.t("추가"), L.t("캔버스"), L.t("공유"), L.t("비디오"), L.t("설정"), L.t("도움말")), tab, Modifier.fillMaxWidth()) { tab = it }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.height(500.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    MenuRow(L.t("사진 삽입"), Icons.Filled.Image) { ed.panel = null; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    MenuRow(L.t("파일 삽입"), Icons.Filled.Image, sub = "PNG · JPEG · WEBP") { ed.panel = null; fileInsert.launch(arrayOf("image/*")) }
                    MenuRow(L.t("사진 찍기"), Icons.Filled.Image) {
                        ed.panel = null
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile)
                        runCatching { camera.launch(uri) }.onFailure { ed.hud(L.t("카메라 앱을 열 수 없습니다")) }
                    }
                    MenuRow(L.t("비공개 사진 삽입"), Icons.Filled.Image, sub = L.t("타임랩스와 갤러리 미리보기에서 제외")) { ed.panel = null; privatePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    MenuRow(L.t("텍스트 추가"), Icons.Filled.TextFields) { ed.startNewText() }
                    MenuRow(L.t("잘라내기"), Icons.Filled.ContentCut) { ed.cut() }
                    MenuRow(L.t("복사"), Icons.Filled.ContentCopy) { ed.copy() }
                    MenuRow(L.t("모두 복사"), Icons.Filled.ContentCopy) { ed.copy(all = true) }
                    MenuRow(L.t("붙여넣기"), Icons.Filled.ContentPaste) { ed.paste(); ed.panel = null }
                    MenuRow(L.t("참조 이미지 불러오기"), Icons.Filled.PictureInPicture) { ed.panel = null; refPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                1 -> {
                    MenuRow(L.t("자르기 및 크기 조정"), Icons.Filled.AspectRatio) { cropDialog = true }
                    SwitchRow(L.t("애니메이션 어시스트"), ed.doc.animation.enabled, L.t("레이어 = 프레임, 어니언 스킨")) { ed.doc.animation.enabled = it; ed.playing = false; ed.bump() }
                    SwitchRow(L.t("그리기 가이드"), ed.doc.guides.enabled) { ed.doc.guides.enabled = it; ed.bump() }
                    MenuRow(L.t("그리기 가이드 편집"), Icons.Filled.GridOn) { ed.exitModes(); ed.doc.guides.enabled = true; ed.mode = Mode.GUIDE_EDIT; ed.panel = null; ed.invalidate() }
                    SwitchRow(L.t("참조 창"), ed.referenceVisible) { ed.referenceVisible = it; ed.referenceVersion++ }
                    MenuRow(L.t("캔버스 수평 뒤집기"), Icons.Filled.Flip) { ed.flipCanvas(true) }
                    MenuRow(L.t("캔버스 수직 뒤집기"), Icons.Filled.Flip) { ed.flipCanvas(false) }
                    MenuRow(L.t("캔버스 정보"), Icons.Filled.Info) { infoDialog = true }
                    SectionLabel(L.t("보기"))
                    SwitchRow(L.t("좌우 반전 보기"), ed.mirrorView, L.t("그림은 그대로 두고 화면만 뒤집어 비율 확인 (M)")) { if (it != ed.mirrorView) ed.toggleMirrorView() }
                    SwitchRow(L.t("흑백 보기"), ed.grayscaleView, L.t("명암(밸류) 확인용, 내보내기에는 영향 없음 (G)")) { if (it != ed.grayscaleView) ed.toggleGrayscaleView() }
                    MenuRow(L.t("작업 내역"), Icons.Filled.Info, sub = L.t("실행 취소 단계를 목록으로 보고 원하는 단계로 이동 (Ctrl+H)")) { ed.panel = Panel.HISTORY }
                }
                2 -> {
                    SectionLabel(L.t("이미지 공유"))
                    listOf(ExportFormat.PROSKETCH, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TIFF).forEach { f ->
                        MenuRow(f.label, Icons.Filled.Share, sub = if (f == ExportFormat.PSD) L.t("레이어 유지 · 포토샵 호환") else null) { runExport(ed, f, false) }
                    }
                    SectionLabel(L.t("레이어 공유"))
                    MenuRow(ExportFormat.LAYERS_PNG.label, Icons.Filled.Share) { runExport(ed, ExportFormat.LAYERS_PNG, false) }
                    MenuRow(ExportFormat.LAYERS_PDF.label, Icons.Filled.Share) { runExport(ed, ExportFormat.LAYERS_PDF, false) }
                    MenuRow(ExportFormat.GIF.label, Icons.Filled.Share, sub = L.t("애니메이션 어시스트 프레임")) { runExport(ed, ExportFormat.GIF, false) }
                    MenuRow(ExportFormat.APNG.label, Icons.Filled.Share, sub = L.t("투명 배경 유지")) { runExport(ed, ExportFormat.APNG, false) }
                    MenuRow(ExportFormat.MP4.label, Icons.Filled.Share) { runExport(ed, ExportFormat.MP4, false) }
                    SectionLabel(L.t("기기에 저장"))
                    MenuRow(L.t("갤러리에 PNG 저장"), Icons.Filled.SaveAlt) { runExport(ed, ExportFormat.PNG, true) }
                    MenuRow(L.t("갤러리에 JPEG 저장"), Icons.Filled.SaveAlt) { runExport(ed, ExportFormat.JPEG, true) }
                }
                3 -> {
                    MenuRow(L.t("타임랩스 다시 보기"), Icons.Filled.PlayArrow, sub = L.tf("{0} 프레임", ed.timelapse.frameCount)) { replay = true }
                    SwitchRow(L.t("타임랩스 녹화"), ed.doc.timelapseEnabled) { if (!it) purgeAsk = true else { ed.doc.timelapseEnabled = true; ed.bump() } }
                    MenuRow(L.t("타임랩스 내보내기 (전체 길이)"), Icons.Filled.Timelapse) { runExport(ed, ExportFormat.TIMELAPSE, false) }
                    MenuRow(L.t("타임랩스 내보내기 (30초)"), Icons.Filled.Timelapse) { runExport(ed, ExportFormat.TIMELAPSE30, false) }
                    MenuRow(L.t("타임랩스를 갤러리에 저장"), Icons.Filled.SaveAlt) { runExport(ed, ExportFormat.TIMELAPSE, true) }
                }
                4 -> PrefsView(ed)
                else -> HelpView()
            }
        }
    }

    if (cropDialog) CropDialog(ed) { cropDialog = false }
    if (infoDialog) InfoDialog(ed) { infoDialog = false }
    if (replay) TimelapseReplay(ed) { replay = false }
    if (purgeAsk) AlertDialog(
        onDismissRequest = { purgeAsk = false },
        title = { Text(L.t("타임랩스 녹화 일시 정지")) },
        text = { Text(L.t("지금까지의 녹화를 삭제할까요? 삭제하지 않으면 녹화만 일시 정지됩니다.")) },
        confirmButton = { TextButton({ ed.timelapse.purge(); ed.doc.timelapseEnabled = false; ed.bump(); purgeAsk = false }) { Text(L.t("삭제"), color = UI.danger) } },
        dismissButton = { TextButton({ ed.doc.timelapseEnabled = false; ed.bump(); purgeAsk = false }) { Text(L.t("삭제 안 함")) } },
    )
}

@Composable
private fun PrefsView(ed: Editor) {
    val p = ed.prefs
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    fun ch(f: () -> Unit) { f(); rev++; ed.invalidate() }
    SectionLabel("Language · 언어")
    Segmented(listOf(L.t("시스템 설정"), "한국어", "English"), p.language, Modifier.fillMaxWidth()) { lang ->
        if (lang != p.language) {
            p.language = lang
            ed.save {
                // labels are fixed per process: restart into the gallery with the new language
                val i = ed.ctx.packageManager.getLaunchIntentForPackage(ed.ctx.packageName)!!
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ed.ctx.startActivity(i)
                Runtime.getRuntime().exit(0)
            }
        }
    }
    SectionLabel(L.t("인터페이스"))
    SwitchRow(L.t("오른손잡이 인터페이스"), p.rightHanded, L.t("사이드바를 오른쪽에 표시")) { ch { p.rightHanded = it; ed.version++ } }
    SwitchRow(L.t("브러시 커서"), p.brushCursor, L.t("S펜 호버/그리는 중 브러시 외곽선 표시")) { ch { p.brushCursor = it } }
    SwitchRow(L.t("화면 기준 브러시 크기"), p.screenRelativeBrush, L.t("끄면 캔버스 기준(확대하면 브러시도 커짐)")) { ch { p.screenRelativeBrush = it } }
    LabeledSlider(L.t("선택 마스크 가시성"), p.selectionMaskOpacity, 0f..1f, "${(p.selectionMaskOpacity * 100).roundToInt()}%", { ch { p.selectionMaskOpacity = it } })
    SectionLabel(L.t("ColorDrop 채우기"))
    LabeledSlider(L.t("틈 닫기 (최대 틈 크기)"), p.colorDropGap.toFloat(), 0f..100f, if (p.colorDropGap == 0) L.t("끔") else "${p.colorDropGap}px",
        { ch { p.colorDropGap = it.roundToInt() } })
    Text(L.t("선화에 작은 틈이 있어도 바깥으로 새지 않고 채웁니다. 선에서 틈 크기만큼 안쪽까지 채운 뒤 다시 선 아래로 넓힙니다."), color = UI.text2, fontSize = 11.sp)
    SectionLabel(L.t("S펜 및 손가락"))
    Text(L.t("손가락 입력"), color = UI.text, fontSize = 13.sp)
    Column { FingerMode.entries.forEach { m -> Row(Modifier.fillMaxWidth().clickable { ch { p.fingerMode = m } }.padding(vertical = 5.dp)) {
        Text((if (p.fingerMode == m) "● " else "○ ") + m.label, color = if (p.fingerMode == m) UI.accent else UI.text, fontSize = 13.sp) } } }
    Spacer(Modifier.height(6.dp))
    Text(L.t("S펜 버튼"), color = UI.text, fontSize = 13.sp)
    Column { PenButtonAction.entries.forEach { m -> Row(Modifier.fillMaxWidth().clickable { ch { p.penButton = m } }.padding(vertical = 5.dp)) {
        Text((if (p.penButton == m) "● " else "○ ") + m.label, color = if (p.penButton == m) UI.accent else UI.text, fontSize = 13.sp) } } }
    SectionLabel("QuickMenu")
    SwitchRow(L.t("손가락으로 길게 눌러 QuickMenu 열기"), p.quickMenuGesture == 1, L.t("켜면 길게 누르기 스포이드 대신 열립니다 · 키보드 Space · S펜 버튼에도 지정 가능")) {
        ch { p.quickMenuGesture = if (it) 1 else 0 }
    }
    SectionLabel(L.t("압력 곡선"))
    PressureCurveEditor(ed)
    SectionLabel(L.t("제스처"))
    SwitchRow(L.t("두 손가락 탭 → 실행 취소"), p.twoFingerUndo) { ch { p.twoFingerUndo = it } }
    SwitchRow(L.t("세 손가락 탭 → 다시 실행"), p.threeFingerRedo) { ch { p.threeFingerRedo = it } }
    SwitchRow(L.t("길게 눌러 스포이드"), p.holdEyedropper) { ch { p.holdEyedropper = it } }
    LabeledSlider(L.t("스포이드 지연"), p.holdEyedropperDelay, 0.15f..1.2f, L.t("%.2f초").format(p.holdEyedropperDelay), { ch { p.holdEyedropperDelay = it } })
    SwitchRow(L.t("세 손가락 문지르기 → 레이어 지우기"), p.scrubClear) { ch { p.scrubClear = it } }
    SwitchRow(L.t("세 손가락 아래로 쓸기 → 복사/붙여넣기"), p.threeSwipeMenu) { ch { p.threeSwipeMenu = it } }
    SwitchRow(L.t("네 손가락 탭 → 전체 화면"), p.fourTapFullscreen) { ch { p.fourTapFullscreen = it } }
    SwitchRow(L.t("핀치 확대 시 회전"), p.rotateWithPinch) { ch { p.rotateWithPinch = it } }
    SwitchRow(L.t("QuickShape (그린 후 길게 유지)"), p.quickShape) { ch { p.quickShape = it } }
    LabeledSlider(L.t("QuickShape 지연"), p.quickShapeDelay, 0.25f..1.5f, L.t("%.2f초").format(p.quickShapeDelay), { ch { p.quickShapeDelay = it } })
    LabeledSlider(L.t("빠른 실행 취소 지연"), p.rapidUndoDelay, 0.3f..1.5f, L.t("%.2f초").format(p.rapidUndoDelay), { ch { p.rapidUndoDelay = it } })
}

@Composable
private fun PressureCurveEditor(ed: Editor) {
    var ctrl by remember { mutableStateOf(ed.prefs.pressureCurve) }
    val edRef by rememberUpdatedState(ed)
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF151517)).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val n = floatArrayOf(down.position.x / size.width, 1f - down.position.y / size.height)
                val c = ctrl
                val which = if (kotlin.math.hypot(c[0] - n[0], c[1] - n[1]) < kotlin.math.hypot(c[2] - n[0], c[3] - n[1])) 0 else 2
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    val nc = ctrl.copyOf()
                    nc[which] = (ch.position.x / size.width).coerceIn(0f, 1f)
                    nc[which + 1] = (1f - ch.position.y / size.height).coerceIn(0f, 1f)
                    ctrl = nc
                }
                edRef.prefs.pressureCurve = ctrl
                edRef.pressureCurve = PressureCurve(ctrl)
            }
        }) {
            drawLine(Color(0x33FFFFFF), Offset(0f, size.height), Offset(size.width, 0f))
            val pc = PressureCurve(ctrl)
            val path = Path()
            for (i in 0..64) {
                val x = i / 64f
                val y = pc.map(x)
                if (i == 0) path.moveTo(x * size.width, (1 - y) * size.height) else path.lineTo(x * size.width, (1 - y) * size.height)
            }
            drawPath(path, UI.accent, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 8.dp.toPx(), Offset(ctrl[0] * size.width, (1 - ctrl[1]) * size.height))
            drawCircle(Color.White, 8.dp.toPx(), Offset(ctrl[2] * size.width, (1 - ctrl[3]) * size.height))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fun set(v: FloatArray) { ctrl = v; ed.prefs.pressureCurve = v; ed.pressureCurve = PressureCurve(v) }
            TextBtn(L.t("선형")) { set(floatArrayOf(0.33f, 0.33f, 0.67f, 0.67f)) }
            TextBtn(L.t("부드럽게")) { set(floatArrayOf(0.15f, 0.45f, 0.5f, 0.95f)) }
            TextBtn(L.t("단단하게")) { set(floatArrayOf(0.5f, 0.05f, 0.85f, 0.55f)) }
        }
    }
}

@Composable
private fun HelpView() {
    val items = listOf(
        L.t("두 손가락 탭") to L.t("실행 취소 (누르고 있으면 연속 실행 취소)"),
        L.t("세 손가락 탭") to L.t("다시 실행"),
        L.t("두 손가락 핀치 / 비틀기") to L.t("확대·축소 / 회전"),
        L.t("빠르게 핀치 닫기") to L.t("화면에 맞추기 (빠르게 벌리면 이전 보기)"),
        L.t("길게 누르기 (손가락)") to L.t("스포이드 · 다른 손가락 탭: 현재 레이어 샘플링 전환"),
        L.t("그린 뒤 멈추고 유지") to L.t("QuickShape · 유지한 채 다른 손가락 탭: 완벽한 도형"),
        L.t("세 손가락 좌우 문지르기") to L.t("레이어(또는 선택 영역) 지우기"),
        L.t("세 손가락 아래로 쓸기") to L.t("복사 및 붙여넣기 메뉴"),
        L.t("네 손가락 탭") to L.t("전체 화면 전환"),
        L.t("색상 버튼을 캔버스로 드래그") to L.t("ColorDrop 채우기 · 유지 후 좌우로 임계값"),
        L.t("S펜 버튼") to L.t("설정에 따라 지우개/스포이드/실행 취소/QuickMenu"),
        L.t("브러시 다시 탭") to L.t("브러시 라이브러리 · 선택한 브러시 다시 탭: 브러시 스튜디오"),
        L.t("도구 길게 누르기") to L.t("현재 브러시를 그대로 가져와 도구 전환"),
        L.t("사이드바 슬라이더를 옆으로 끌기") to L.t("더 세밀한 조절"),
        L.t("키보드 · 도구") to L.t("B 브러시 · E 지우개 · Shift+S 문지르기 · S 선택 · V 변형 · C 색상 · L 레이어 · Space QuickMenu"),
        L.t("키보드 · 편집") to L.t("Ctrl+Z / Ctrl+Shift+Z · Ctrl+C/X/V · Ctrl+A 모두 복사 · Ctrl+J 복제 · Ctrl+G 그룹 · Ctrl+⌫ 지우기 · X 이전 색상"),
        L.t("키보드 · 보기") to L.t("[ ] 크기 (Ctrl 1%, Shift 10%) · Ctrl+0 맞춤 · M 좌우 반전 보기 · G 흑백 보기 · Ctrl+H 작업 내역 · 방향키: 변형 중 1px 이동"),
    )
    val ctx = LocalContext.current
    var guide by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    if (guide) com.prosketch.app.ui.common.OnboardingDialog { guide = false }
    if (licenses) androidx.compose.material3.AlertDialog(onDismissRequest = { licenses = false },
        title = { Text(L.t("오픈소스 라이선스")) },
        text = {
            Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                listOf(
                    "Kotlin · kotlinx.coroutines" to "Apache License 2.0 · JetBrains",
                    "AndroidX (Core, Activity, Lifecycle)" to "Apache License 2.0 · Google",
                    "Jetpack Compose (UI, Foundation, Material 3)" to "Apache License 2.0 · Google",
                    "Material Icons" to "Apache License 2.0 · Google",
                ).forEach { (n, l) ->
                    Text(n, color = UI.text, fontSize = 14.sp); Text(l, color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(L.t("Apache License 2.0 전문: https://www.apache.org/licenses/LICENSE-2.0"), color = UI.text2, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ licenses = false }) { Text(L.t("닫기")) } })
    MenuRow(L.t("사용 안내 다시 보기"), Icons.Filled.Info) { guide = true }
    MenuRow(L.t("개인정보 처리방침"), Icons.Filled.Info, sub = L.t("ProSketch는 개인정보를 수집하지 않습니다")) {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/kjt7942/ProSketch/blob/main/PRIVACY.md"))) }
    }
    MenuRow(L.t("오픈소스 라이선스"), Icons.Filled.Info) { licenses = true }
    Text("ProSketch ${com.prosketch.app.data.Updater.currentVersion(ctx)} (" + (if (com.prosketch.app.BuildConfig.SELF_UPDATE) "GitHub" else "Google Play") + ")",
        color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    if (com.prosketch.app.BuildConfig.SELF_UPDATE) MenuRow(L.t("업데이트 확인"), Icons.Filled.SystemUpdate, sub = L.tf("ProSketch {0} · GitHub 릴리스", com.prosketch.app.data.Updater.currentVersion(ctx))) {
        com.prosketch.app.ui.common.UpdateController.check(ctx, silent = false)
    }
    Spacer(Modifier.height(6.dp))
    items.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(k, color = UI.text, fontSize = 13.sp, modifier = Modifier.width(190.dp))
            Text(v, color = UI.text2, fontSize = 13.sp)
        }
    }
}


@Composable
private fun CropDialog(ed: Editor, close: () -> Unit) {
    var w by remember { mutableStateOf(ed.doc.width.toString()) }
    var h by remember { mutableStateOf(ed.doc.height.toString()) }
    var resample by remember { mutableStateOf(false) }
    var anchor by remember { mutableIntStateOf(4) }
    var keepRatio by remember { mutableStateOf(true) }
    val ratio = ed.doc.width.toFloat() / ed.doc.height
    AlertDialog(
        onDismissRequest = close,
        title = { Text(L.t("자르기 및 크기 조정")) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { v -> w = v.filter(Char::isDigit).take(5); if (keepRatio) w.toIntOrNull()?.let { h = (it / ratio).roundToInt().toString() } },
                        label = { Text(L.t("너비")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(h, { v -> h = v.filter(Char::isDigit).take(5); if (keepRatio) h.toIntOrNull()?.let { w = (it * ratio).roundToInt().toString() } },
                        label = { Text(L.t("높이")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                SwitchRow(L.t("비율 유지"), keepRatio) { keepRatio = it }
                SwitchRow(L.t("캔버스 리샘플링"), resample, L.t("켜면 그림을 늘리거나 줄임, 끄면 캔버스만 자르거나 확장")) { resample = it }
                if (!resample) {
                    Text(L.t("기준점"), color = UI.text2, fontSize = 12.sp)
                    Column(Modifier.padding(top = 4.dp)) {
                        for (r in 0..2) Row { for (c in 0..2) {
                            val i = r * 3 + c
                            Box(Modifier.size(30.dp).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(if (anchor == i) UI.accent else UI.panel2).clickable { anchor = i })
                        } }
                    }
                }
                val ww = w.toIntOrNull() ?: 0; val hh = h.toIntOrNull() ?: 0
                val maxDim = 8192
                if (ww > maxDim || hh > maxDim) Text(L.tf("최대 {0}px까지 지원합니다", maxDim), color = UI.danger, fontSize = 12.sp)
                else if (ww > 0 && hh > 0) Text(L.tf("최대 레이어 약 {0}개", (ed.maxLayers * ed.doc.width.toLong() * ed.doc.height / (ww.toLong() * hh)).toInt().coerceIn(4, 200)), color = UI.text2, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton({
                val ww = w.toIntOrNull() ?: return@TextButton; val hh = h.toIntOrNull() ?: return@TextButton
                if (ww !in 16..8192 || hh !in 16..8192) return@TextButton
                val ax = anchor % 3; val ay = anchor / 3
                val offX = ((ww - ed.doc.width) * ax / 2f).roundToInt()
                val offY = ((hh - ed.doc.height) * ay / 2f).roundToInt()
                ed.panel = null
                ed.resizeCanvas(ww, hh, offX, offY, resample)
                close()
            }) { Text(L.t("완료")) }
        },
        dismissButton = { TextButton(close) { Text(L.t("취소")) } },
    )
}

@Composable
private fun InfoDialog(ed: Editor, close: () -> Unit) {
    val d = ed.doc
    val mins = (d.timeSpentMs / 60000)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(L.t("캔버스 정보")) },
        text = {
            Column {
                listOf(
                    L.t("이름") to d.name, L.t("크기") to "${d.width} × ${d.height} px",
                    L.t("레이어") to L.tf("{0} / 최대 {1}", d.layers.size, ed.maxLayers), L.t("획 수") to "${d.strokeCount}",
                    L.t("작업 시간") to L.tf("{0}시간 {1}분", mins / 60, mins % 60), L.t("타임랩스 프레임") to "${ed.timelapse.frameCount}",
                    L.t("실행 취소 단계") to "${ed.history.size}",
                ).forEach { (k, v) -> Row(Modifier.padding(vertical = 3.dp)) { Text(k, modifier = Modifier.width(120.dp), color = UI.text2); Text(v) } }
            }
        },
        confirmButton = { TextButton(close) { Text(L.t("닫기")) } },
    )
}

