package com.prosketch.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.LaunchedEffect
import com.prosketch.app.ui.common.UpdateController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.data.ArtworkInfo
import com.prosketch.app.data.ExportFormat
import com.prosketch.app.data.Exporter
import com.prosketch.app.data.ProjectStore
import com.prosketch.app.data.Psd
import com.prosketch.app.engine.Compositor
import com.prosketch.app.engine.Document
import com.prosketch.app.engine.Layer
import com.prosketch.app.ui.common.IconBtn
import com.prosketch.app.ui.common.SwitchRow
import com.prosketch.app.ui.common.TextBtn
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.checkerboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class CanvasPreset(val name: String, val w: Int, val h: Int)

/** Creates and saves a new document; returns its id. Runs on a background thread. */
fun createDocument(store: ProjectStore, w: Int, h: Int, name: String, transparent: Boolean = false, layers: List<Layer>? = null): String {
    val doc = Document(store.newId(), w, h)
    doc.name = name
    doc.backgroundVisible = !transparent
    if (layers.isNullOrEmpty()) doc.layers.add(Layer.blank(w, h, "레이어 1")) else doc.layers.addAll(layers)
    doc.normalize()
    doc.activeIndex = doc.layers.indexOfLast { !it.isGroup }.coerceAtLeast(0)
    val thumb = Compositor.flatten(doc, min(1f, 640f / max(w, h)))
    store.write(store.snapshot(doc, thumb))
    thumb.recycle()
    return doc.id
}

private fun decodeLimited(ctx: android.content.Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
    val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
    android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        dec.isMutableRequired = true
        val s = min(1f, maxDim.toFloat() / max(info.size.width, info.size.height))
        if (s < 1f) dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
    }
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(store: ProjectStore, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val items by produceState(emptyList<ArtworkInfo>(), refresh) { value = withContext(Dispatchers.IO) { store.list() } }
    var newDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var renameFor by remember { mutableStateOf<ArtworkInfo?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun work(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { message = "실패: ${it.message ?: it.javaClass.simpleName}" }
            busy = null; refresh++
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) work("사진 불러오는 중…") {
            val bmp = decodeLimited(ctx, uri, 4096) ?: error("이미지를 열 수 없습니다")
            val l = Layer.blank(bmp.width, bmp.height, "사진")
            Canvas(l.bitmap).drawBitmap(bmp, 0f, 0f, null)
            val id = createDocument(store, bmp.width, bmp.height, "사진 작품", layers = listOf(l))
            bmp.recycle()
            withContext(Dispatchers.Main) { onOpen(id) }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work("가져오는 중…") {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "가져온 작품"
            val lower = name.lowercase()
            when {
                lower.endsWith(".prosketch") || lower.endsWith(".zip") -> {
                    ctx.contentResolver.openInputStream(uri)?.use { store.importZip(it) } ?: error("ProSketch 파일이 아닙니다")
                }
                lower.endsWith(".psd") -> {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val psd = Psd.read(bytes)
                    require(max(psd.width, psd.height) <= 8192) { "8192px 이하의 PSD만 지원합니다" }
                    createDocument(store, psd.width, psd.height, name.substringBeforeLast('.'), transparent = true, layers = psd.layers)
                }
                else -> {
                    val bmp = decodeLimited(ctx, uri, 4096) ?: error("지원하지 않는 파일입니다")
                    val l = Layer.blank(bmp.width, bmp.height, name.substringBeforeLast('.'))
                    Canvas(l.bitmap).drawBitmap(bmp, 0f, 0f, null)
                    createDocument(store, bmp.width, bmp.height, name.substringBeforeLast('.'), layers = listOf(l))
                    bmp.recycle()
                }
            }
        }
    }

    LaunchedEffect(Unit) { UpdateController.check(ctx, silent = true) }

    Column(Modifier.fillMaxSize().background(Color(0xFF111113)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ProSketch", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("갤러리", color = UI.text2, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            if (selecting) {
                TextBtn("복제 (${selected.size})", enabled = selected.isNotEmpty()) { val ids = selected.toList(); work("복제 중…") { ids.forEach { store.duplicate(it) } }; selected.clear(); selecting = false }
                Spacer(Modifier.width(6.dp))
                TextBtn("삭제 (${selected.size})", enabled = selected.isNotEmpty()) { deleteIds = selected.toList() }
                Spacer(Modifier.width(6.dp))
                TextBtn("완료", true) { selecting = false; selected.clear() }
            } else {
                TextBtn("선택") { selecting = true }
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Filled.FileOpen, "가져오기") { filePicker.launch(arrayOf("image/*", "application/octet-stream", "application/zip", "image/vnd.adobe.photoshop", "*/*")) }
                IconBtn(Icons.Filled.PhotoLibrary, "사진") { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                IconBtn(Icons.Filled.Add, "새 캔버스") { newDialog = true }
                IconBtn(Icons.Filled.SystemUpdate, "업데이트 확인") { UpdateController.check(ctx, silent = false) }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("아직 작품이 없습니다", color = UI.text, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    TextBtn("+ 새 캔버스 만들기", true) { newDialog = true }
                }
            }
        } else LazyVerticalGrid(GridCells.Adaptive(230.dp), Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items, key = { it.id }) { a ->
                Box {
                    ArtworkCard(a, refresh, selecting, a.id in selected,
                        onClick = { if (selecting) { if (a.id in selected) selected.remove(a.id) else selected.add(a.id) } else onOpen(a.id) },
                        onLong = { menuFor = a.id },
                        onRename = { renameFor = a })
                    DropdownMenu(menuFor == a.id, { menuFor = null }) {
                        DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menuFor = null; renameFor = a })
                        DropdownMenuItem(text = { Text("복제") }, onClick = { menuFor = null; work("복제 중…") { store.duplicate(a.id) } })
                        listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.PSD, ExportFormat.PROSKETCH).forEach { f ->
                            DropdownMenuItem(text = { Text("${f.label}로 공유") }, onClick = {
                                menuFor = null
                                work("내보내는 중…") {
                                    val doc = store.load(a.id) ?: error("작품을 열 수 없습니다")
                                    val file = Exporter.export(ctx, store, doc, f)
                                    withContext(Dispatchers.Main) { Exporter.share(ctx, file, f.mime) }
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("삭제", color = UI.danger) }, onClick = { menuFor = null; deleteIds = listOf(a.id) })
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (newDialog) NewCanvasDialog(onDismiss = { newDialog = false }) { w, h, name, transparent ->
        newDialog = false
        work("캔버스 만드는 중…") { val id = createDocument(store, w, h, name, transparent); withContext(Dispatchers.Main) { onOpen(id) } }
    }
    renameFor?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(onDismissRequest = { renameFor = null }, title = { Text("이름 변경") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.rename(a.id, name.ifBlank { a.name }); renameFor = null; refresh++ }) { Text("확인") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("취소") } })
    }
    deleteIds?.let { ids ->
        AlertDialog(onDismissRequest = { deleteIds = null }, title = { Text("작품 삭제") }, text = { Text("${ids.size}개의 작품을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = { TextButton({ ids.forEach { store.delete(it) }; selected.removeAll(ids); deleteIds = null; refresh++ }) { Text("삭제", color = UI.danger) } },
            dismissButton = { TextButton({ deleteIds = null }) { Text("취소") } })
    }
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton({ message = null }) { Text("확인") } }) }
    busy?.let {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(UI.panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = UI.accent); Spacer(Modifier.height(12.dp)); Text(it, color = UI.text)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtworkCard(a: ArtworkInfo, refresh: Int, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLong: () -> Unit, onRename: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, a.id, a.modified, refresh) {
        value = withContext(Dispatchers.IO) { if (a.thumb.exists()) BitmapFactory.decodeFile(a.thumb.absolutePath) else null }
    }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
            val ratio = a.width.toFloat() / a.height
            Box(Modifier.let { if (ratio >= 1f) it.fillMaxWidth().aspectRatio(ratio) else it.fillMaxSize().aspectRatio(ratio, matchHeightConstraintsFirst = true) }
                .clip(RoundedCornerShape(6.dp)).checkerboard(8.dp)
                .border(if (checked) 3.dp else 0.dp, UI.accent, RoundedCornerShape(6.dp))) {
                thumb?.let { Image(it.asImageBitmap(), a.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                    .background(if (checked) UI.accent else Color(0x66000000)).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(a.name, color = UI.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable(onClick = onRename), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("${a.width} × ${a.height}px · ${SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(a.modified))}", color = UI.text2, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun NewCanvasDialog(onDismiss: () -> Unit, onCreate: (Int, Int, String, Boolean) -> Unit) {
    val cfg = LocalConfiguration.current
    val activity = LocalContext.current as? android.app.Activity
    val bounds = activity?.windowManager?.maximumWindowMetrics?.bounds
    val dm = LocalContext.current.resources.displayMetrics
    val rw = bounds?.width() ?: dm.widthPixels; val rh = bounds?.height() ?: dm.heightPixels
    val screenW = max(rw, rh); val screenH = min(rw, rh)
    val presets = listOf(
        CanvasPreset("화면 크기 (가로)", screenW, screenH),
        CanvasPreset("화면 크기 (세로)", screenH, screenW),
        CanvasPreset("정사각형", 2048, 2048),
        CanvasPreset("4K", 3840, 2160),
        CanvasPreset("A4 (300dpi)", 2480, 3508),
        CanvasPreset("인스타그램 세로", 1080, 1350),
        CanvasPreset("웹툰 (세로 스크롤)", 800, 4000),
        CanvasPreset("움짤 / 애니메이션", 1280, 720),
    )
    var custom by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf("3000") }
    var h by remember { mutableStateOf("2000") }
    var transparent by remember { mutableStateOf(false) }
    val am = LocalContext.current.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val total = remember { android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem }
    fun maxLayers(ww: Int, hh: Int) = Document("x", ww, hh).maxLayers(total)
    cfg.let { }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 캔버스") },
        text = {
            Column {
                if (!custom) presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onCreate(p.w, p.h, "무제 작품", transparent) }.padding(vertical = 10.dp, horizontal = 6.dp)) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        Text("${p.w} × ${p.h}px · 최대 ${maxLayers(p.w, p.h)} 레이어", color = UI.text2, fontSize = 12.sp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(w, { w = it.filter(Char::isDigit).take(5) }, label = { Text("너비 px") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(5) }, label = { Text("높이 px") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    val ww = w.toIntOrNull() ?: 0; val hh = h.toIntOrNull() ?: 0
                    if (ww in 16..8192 && hh in 16..8192) Text("최대 레이어: ${maxLayers(ww, hh)}개", color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    else Text("16 ~ 8192px 사이로 입력하세요", color = UI.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                SwitchRow("투명 배경", transparent) { transparent = it }
            }
        },
        confirmButton = {
            if (custom) TextButton({
                val ww = w.toIntOrNull() ?: return@TextButton; val hh = h.toIntOrNull() ?: return@TextButton
                if (ww in 16..8192 && hh in 16..8192) onCreate(ww, hh, "무제 작품", transparent)
            }) { Text("만들기") } else TextButton({ custom = true }) { Text("사용자 지정 크기…") }
        },
        dismissButton = { TextButton(onDismiss) { Text("취소") } },
    )
}
