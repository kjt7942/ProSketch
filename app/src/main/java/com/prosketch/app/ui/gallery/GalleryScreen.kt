package com.prosketch.app.ui.gallery

import com.prosketch.app.L

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.prosketch.app.data.ArtStack
import com.prosketch.app.data.GalleryEntry
import com.prosketch.app.data.GalleryLayout
import java.io.File
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
    if (layers.isNullOrEmpty()) doc.layers.add(Layer.blank(w, h, L.t("레이어 1"))) else doc.layers.addAll(layers)
    doc.normalize()
    doc.activeIndex = doc.layers.indexOfLast { !it.isGroup }.coerceAtLeast(0)
    val thumb = Compositor.flatten(doc, min(1f, 1024f / max(w, h)))
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
    val density = LocalDensity.current
    var refresh by remember { mutableIntStateOf(0) }
    val layout = remember { GalleryLayout(File(store.root, "gallery.json")) }
    // null until the first listing: reconciling the layout against an empty list would wipe the stacks
    val loaded by produceState<List<ArtworkInfo>?>(null, refresh) { value = withContext(Dispatchers.IO) { store.list() } }
    val all = loaded ?: emptyList()
    var openStack by remember { mutableStateOf<String?>(null) }
    val top = remember(loaded, refresh) { loaded?.let { layout.resolve(it) } ?: emptyList() }
    val stackEntry = openStack?.let { id -> top.firstOrNull { it.key == id } as? GalleryEntry.Stack }
    LaunchedEffect(top, openStack) { if (openStack != null && stackEntry == null) openStack = null }
    val entries: List<GalleryEntry> = stackEntry?.items?.map { GalleryEntry.Art(it) } ?: top

    var newDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var renameArt by remember { mutableStateOf<ArtworkInfo?>(null) }
    var renameStack by remember { mutableStateOf<ArtStack?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Int?>(null) }

    // long-press drag: reorder, or drop onto another card to stack
    val grid = rememberLazyGridState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var hoverKey by remember { mutableStateOf<String?>(null) }
    var hoverMode by remember { mutableIntStateOf(0) }   // 1 before, 2 after, 3 stack onto
    var gestureEnd by remember { mutableStateOf(0L) }       // a long press must not also count as a tap

    fun work(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { message = L.tf("실패: {0}", it.message ?: it.javaClass.simpleName) }
            busy = null; refresh++
        }
    }

    fun updateHover() {
        val p = dragStart + dragDelta
        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key != dragKey && it.key is String &&
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height }
        if (hit == null) { hoverKey = null; hoverMode = 0; return }
        val rx = (p.x - hit.offset.x) / hit.size.width
        val ry = (p.y - hit.offset.y) / hit.size.height
        val draggedIsArt = entries.firstOrNull { it.key == dragKey } is GalleryEntry.Art
        hoverKey = hit.key as String
        hoverMode = if (openStack == null && draggedIsArt && rx in 0.25f..0.75f && ry in 0.15f..0.75f) 3 else if (rx < 0.5f) 1 else 2
    }

    fun endDrag() {
        val k = dragKey; val h = hoverKey
        val moved = dragDelta.getDistance() > with(density) { 14.dp.toPx() }
        if (k != null && !moved) menuFor = k
        if (k != null && moved && h != null) {
            if (hoverMode == 3) layout.addTo(h, k)
            else {
                val keys = entries.map { it.key }
                val idx = keys.indexOf(h) + if (hoverMode == 2) 1 else 0
                layout.move(openStack, k, idx)
            }
            refresh++
        }
        dragKey = null; hoverKey = null; hoverMode = 0; dragDelta = Offset.Zero
        gestureEnd = android.os.SystemClock.uptimeMillis()
    }

    fun tapAllowed() = dragKey == null && android.os.SystemClock.uptimeMillis() - gestureEnd > 350

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) work(L.t("사진 불러오는 중…")) {
            val bmp = decodeLimited(ctx, uri, 4096) ?: error(L.t("이미지를 열 수 없습니다"))
            val l = Layer.blank(bmp.width, bmp.height, L.t("사진"))
            Canvas(l.bitmap).drawBitmap(bmp, 0f, 0f, null)
            val id = createDocument(store, bmp.width, bmp.height, L.t("사진 작품"), layers = listOf(l))
            bmp.recycle()
            withContext(Dispatchers.Main) { onOpen(id) }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work(L.t("가져오는 중…")) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: L.t("가져온 작품")
            val lower = name.lowercase()
            when {
                lower.endsWith(".prosketch") || lower.endsWith(".zip") -> {
                    ctx.contentResolver.openInputStream(uri)?.use { store.importZip(it) } ?: error(L.t("ProSketch 파일이 아닙니다"))
                }
                lower.endsWith(".psd") -> {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val psd = Psd.read(bytes)
                    require(max(psd.width, psd.height) <= 8192) { L.t("8192px 이하의 PSD만 지원합니다") }
                    createDocument(store, psd.width, psd.height, name.substringBeforeLast('.'), transparent = true, layers = psd.layers)
                }
                else -> {
                    val bmp = decodeLimited(ctx, uri, 4096) ?: error(L.t("지원하지 않는 파일입니다"))
                    val l = Layer.blank(bmp.width, bmp.height, name.substringBeforeLast('.'))
                    Canvas(l.bitmap).drawBitmap(bmp, 0f, 0f, null)
                    createDocument(store, bmp.width, bmp.height, name.substringBeforeLast('.'), layers = listOf(l))
                    bmp.recycle()
                }
            }
        }
    }

    LaunchedEffect(Unit) { if (com.prosketch.app.BuildConfig.SELF_UPDATE) UpdateController.check(ctx, silent = true) }
    BackHandler(enabled = openStack != null || selecting) {
        if (selecting) { selecting = false; selected.clear() } else openStack = null
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111113)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stackEntry != null) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, L.t("갤러리")) { openStack = null; selecting = false; selected.clear() }
                Spacer(Modifier.width(6.dp))
                Text(stackEntry.stack.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { renameStack = stackEntry.stack })
                Spacer(Modifier.width(10.dp))
                Text(L.tf("작품 {0}개", stackEntry.items.size), color = UI.text2, fontSize = 14.sp)
            } else {
                Text("ProSketch", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(L.t("갤러리"), color = UI.text2, fontSize = 16.sp)
            }
            Spacer(Modifier.weight(1f))
            if (selecting) {
                val arts = selected.filter { id -> all.any { it.id == id } }
                if (openStack == null) {
                    TextBtn(L.tf("스택 ({0})", arts.size), enabled = arts.size >= 2) { layout.createStack(arts); selected.clear(); selecting = false; refresh++ }
                } else {
                    TextBtn(L.tf("꺼내기 ({0})", arts.size), enabled = arts.isNotEmpty()) { arts.forEach { layout.takeOut(it) }; selected.clear(); selecting = false; refresh++ }
                }
                Spacer(Modifier.width(6.dp))
                TextBtn(L.tf("복제 ({0})", arts.size), enabled = arts.isNotEmpty()) { work(L.t("복제 중…")) { arts.forEach { store.duplicate(it) } }; selected.clear(); selecting = false }
                Spacer(Modifier.width(6.dp))
                TextBtn(L.tf("삭제 ({0})", arts.size), enabled = arts.isNotEmpty()) { deleteIds = arts }
                Spacer(Modifier.width(6.dp))
                TextBtn(L.t("완료"), true) { selecting = false; selected.clear() }
            } else {
                TextBtn(L.t("선택")) { selecting = true }
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Filled.FileOpen, L.t("가져오기")) { filePicker.launch(arrayOf("image/*", "application/octet-stream", "application/zip", "image/vnd.adobe.photoshop", "*/*")) }
                IconBtn(Icons.Filled.PhotoLibrary, L.t("사진")) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                IconBtn(Icons.Filled.Add, L.t("새 캔버스")) { newDialog = true }
                if (com.prosketch.app.BuildConfig.SELF_UPDATE) IconBtn(Icons.Filled.SystemUpdate, L.t("업데이트 확인")) { UpdateController.check(ctx, silent = false) }
            }
        }
        if (loaded == null) Spacer(Modifier.fillMaxSize())
        else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L.t("아직 작품이 없습니다"), color = UI.text, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    TextBtn(L.t("+ 새 캔버스 만들기"), true) { newDialog = true }
                }
            }
        } else LazyVerticalGrid(GridCells.Adaptive(230.dp), Modifier.fillMaxSize().padding(horizontal = 14.dp), state = grid,
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(entries, key = { it.key }) { e ->
                val dragging = dragKey == e.key
                val hover = if (hoverKey == e.key) hoverMode else 0
                val gesture = Modifier.pointerInput(e.key, selecting, openStack) {
                    if (selecting) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == e.key }
                            dragStart = if (info != null) Offset(info.offset.x + off.x, info.offset.y + off.y) else off
                            dragDelta = Offset.Zero
                            dragKey = e.key
                        },
                        onDrag = { ch, amount -> ch.consume(); dragDelta += amount; updateHover() },
                        onDragEnd = { endDrag() },
                        // the card's tap handler consumes the release of a still long press, which ends the drag as "cancelled"
                        onDragCancel = { endDrag() },
                    )
                }
                Box(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                    if (dragging) { translationX = dragDelta.x; translationY = dragDelta.y; scaleX = 1.04f; scaleY = 1.04f; alpha = 0.92f }
                }) {
                    when (e) {
                        is GalleryEntry.Art -> ArtworkCard(e.info, refresh, selecting, e.key in selected, hover, gesture,
                            onClick = { if (!tapAllowed()) Unit else if (selecting) { if (e.key in selected) selected.remove(e.key) else selected.add(e.key) } else onOpen(e.key) },
                            onRename = { renameArt = e.info })
                        is GalleryEntry.Stack -> StackCard(e, refresh, hover, gesture) { if (!selecting && tapAllowed()) openStack = e.key }
                    }
                    DropdownMenu(menuFor == e.key, { menuFor = null }) {
                        when (e) {
                            is GalleryEntry.Art -> {
                                val a = e.info
                                DropdownMenuItem(text = { Text(L.t("미리보기")) }, onClick = {
                                    menuFor = null
                                    preview = entries.filterIsInstance<GalleryEntry.Art>().indexOfFirst { it.key == a.id }
                                })
                                DropdownMenuItem(text = { Text(L.t("이름 변경")) }, onClick = { menuFor = null; renameArt = a })
                                DropdownMenuItem(text = { Text(L.t("복제")) }, onClick = { menuFor = null; work(L.t("복제 중…")) { store.duplicate(a.id) } })
                                if (openStack != null) DropdownMenuItem(text = { Text(L.t("스택에서 꺼내기")) }, onClick = { menuFor = null; layout.takeOut(a.id); refresh++ })
                                listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.PSD, ExportFormat.PROSKETCH).forEach { f ->
                                    DropdownMenuItem(text = { Text(L.tf("{0}로 공유", f.label)) }, onClick = {
                                        menuFor = null
                                        work(L.t("내보내는 중…")) {
                                            val doc = store.load(a.id) ?: error(L.t("작품을 열 수 없습니다"))
                                            val file = Exporter.export(ctx, store, doc, f)
                                            withContext(Dispatchers.Main) { Exporter.share(ctx, file, f.mime) }
                                        }
                                    })
                                }
                                DropdownMenuItem(text = { Text(L.t("삭제"), color = UI.danger) }, onClick = { menuFor = null; deleteIds = listOf(a.id) })
                            }
                            is GalleryEntry.Stack -> {
                                DropdownMenuItem(text = { Text(L.t("이름 변경")) }, onClick = { menuFor = null; renameStack = e.stack })
                                DropdownMenuItem(text = { Text(L.t("스택 풀기")) }, onClick = { menuFor = null; layout.unstack(e.key); refresh++ })
                                DropdownMenuItem(text = { Text(L.tf("작품 {0}개 모두 삭제", e.items.size), color = UI.danger) }, onClick = { menuFor = null; deleteIds = e.items.map { it.id } })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (newDialog) NewCanvasDialog(onDismiss = { newDialog = false }) { w, h, name, transparent ->
        newDialog = false
        work(L.t("캔버스 만드는 중…")) { val id = createDocument(store, w, h, name, transparent); withContext(Dispatchers.Main) { onOpen(id) } }
    }
    renameArt?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(onDismissRequest = { renameArt = null }, title = { Text(L.t("이름 변경")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.rename(a.id, name.ifBlank { a.name }); renameArt = null; refresh++ }) { Text(L.t("확인")) } },
            dismissButton = { TextButton({ renameArt = null }) { Text(L.t("취소")) } })
    }
    renameStack?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        AlertDialog(onDismissRequest = { renameStack = null }, title = { Text(L.t("스택 이름 변경")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ layout.rename(s.id, name.ifBlank { s.name }); renameStack = null; refresh++ }) { Text(L.t("확인")) } },
            dismissButton = { TextButton({ renameStack = null }) { Text(L.t("취소")) } })
    }
    deleteIds?.let { ids ->
        AlertDialog(onDismissRequest = { deleteIds = null }, title = { Text(L.t("작품 삭제")) }, text = { Text(L.tf("{0}개의 작품을 삭제할까요? 되돌릴 수 없습니다.", ids.size)) },
            confirmButton = { TextButton({ ids.forEach { store.delete(it) }; selected.removeAll(ids); deleteIds = null; refresh++ }) { Text(L.t("삭제"), color = UI.danger) } },
            dismissButton = { TextButton({ deleteIds = null }) { Text(L.t("취소")) } })
    }
    preview?.let { start ->
        val arts = entries.filterIsInstance<GalleryEntry.Art>().map { it.info }
        if (start in arts.indices) PreviewViewer(arts, start, onClose = { preview = null }, onOpen = { preview = null; onOpen(it) })
    }
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton({ message = null }) { Text(L.t("확인")) } }) }
    busy?.let {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(UI.panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = UI.accent); Spacer(Modifier.height(12.dp)); Text(it, color = UI.text)
            }
        }
    }
}

/** Decodes a gallery thumbnail; the grid asks for half size of the 1024 px image. */
private fun decodeThumb(f: File, half: Boolean): Bitmap? {
    if (!f.exists()) return null
    val o = BitmapFactory.Options().apply { inSampleSize = if (half) 2 else 1 }
    return BitmapFactory.decodeFile(f.absolutePath, o)
}

@Composable
private fun rememberThumb(a: ArtworkInfo, refresh: Int, half: Boolean = true): Bitmap? {
    val thumb by produceState<Bitmap?>(null, a.id, a.modified, refresh) { value = withContext(Dispatchers.IO) { decodeThumb(a.thumb, half) } }
    return thumb
}

/** Drop-target outline: a bar on the side for reordering, a full frame for stacking. */
private fun Modifier.dropHint(hover: Int): Modifier = when (hover) {
    3 -> this.border(4.dp, UI.accent, RoundedCornerShape(8.dp))
    1 -> this.drawBehind { drawRect(UI.accent, topLeft = Offset(-12.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
    2 -> this.drawBehind { drawRect(UI.accent, topLeft = Offset(size.width + 8.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
    else -> this
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtworkCard(a: ArtworkInfo, refresh: Int, selecting: Boolean, checked: Boolean, hover: Int, gesture: Modifier, onClick: () -> Unit, onRename: () -> Unit) {
    val thumb = rememberThumb(a, refresh)
    Column(Modifier.dropHint(hover).then(gesture).clickable(onClick = onClick)) {
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

/** A stack: up to three artworks fanned out. */
@Composable
private fun StackCard(e: GalleryEntry.Stack, refresh: Int, hover: Int, gesture: Modifier, onClick: () -> Unit) {
    Column(Modifier.dropHint(hover).then(gesture).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).padding(10.dp), contentAlignment = Alignment.Center) {
            e.items.take(3).reversed().forEachIndexed { i, a ->
                val depth = min(3, e.items.size) - 1 - i
                val thumb = rememberThumb(a, refresh)
                Box(Modifier.fillMaxSize().padding(start = (depth * 10).dp, top = ((2 - depth) * 6).dp, end = ((2 - depth) * 10).dp, bottom = (depth * 6).dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2E)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp))) {
                    thumb?.let { Image(it.asImageBitmap(), a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(e.stack.name, color = UI.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(L.tf("작품 {0}개", e.items.size), color = UI.text2, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Full-screen preview: swipe between artworks, open the one shown. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewViewer(arts: List<ArtworkInfo>, start: Int, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { arts.size }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val a = arts[page]
            val bmp = rememberThumb(a, 0, half = false)
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), a.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        val a = arts[pager.currentPage.coerceIn(0, arts.size - 1)]
        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            Text("${a.width} × ${a.height}px · ${pager.currentPage + 1}/${arts.size}", color = UI.text2, fontSize = 12.sp)
            Spacer(Modifier.width(14.dp))
            TextBtn(L.t("열기"), true) { onOpen(a.id) }
            Spacer(Modifier.width(6.dp))
            TextBtn(L.t("닫기")) { onClose() }
        }
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
        CanvasPreset(L.t("화면 크기 (가로)"), screenW, screenH),
        CanvasPreset(L.t("화면 크기 (세로)"), screenH, screenW),
        CanvasPreset(L.t("정사각형"), 2048, 2048),
        CanvasPreset("4K", 3840, 2160),
        CanvasPreset("A4 (300dpi)", 2480, 3508),
        CanvasPreset(L.t("인스타그램 세로"), 1080, 1350),
        CanvasPreset(L.t("웹툰 (세로 스크롤)"), 800, 4000),
        CanvasPreset(L.t("움짤 / 애니메이션"), 1280, 720),
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
        title = { Text(L.t("새 캔버스")) },
        text = {
            Column {
                if (!custom) presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onCreate(p.w, p.h, L.t("무제 작품"), transparent) }.padding(vertical = 10.dp, horizontal = 6.dp)) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        Text(L.tf("{0} × {1}px · 최대 {2} 레이어", p.w, p.h, maxLayers(p.w, p.h)), color = UI.text2, fontSize = 12.sp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(w, { w = it.filter(Char::isDigit).take(5) }, label = { Text(L.t("너비 px")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(5) }, label = { Text(L.t("높이 px")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    val ww = w.toIntOrNull() ?: 0; val hh = h.toIntOrNull() ?: 0
                    if (ww in 16..8192 && hh in 16..8192) Text(L.tf("최대 레이어: {0}개", maxLayers(ww, hh)), color = UI.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    else Text(L.t("16 ~ 8192px 사이로 입력하세요"), color = UI.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                SwitchRow(L.t("투명 배경"), transparent) { transparent = it }
            }
        },
        confirmButton = {
            if (custom) TextButton({
                val ww = w.toIntOrNull() ?: return@TextButton; val hh = h.toIntOrNull() ?: return@TextButton
                if (ww in 16..8192 && hh in 16..8192) onCreate(ww, hh, L.t("무제 작품"), transparent)
            }) { Text(L.t("만들기")) } else TextButton({ custom = true }) { Text(L.t("사용자 지정 크기…")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(L.t("취소")) } },
    )
}
