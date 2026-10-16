package com.prosketch.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.prosketch.app.data.BrushStore
import com.prosketch.app.data.PaletteStore
import com.prosketch.app.data.Prefs
import com.prosketch.app.data.ProjectStore
import com.prosketch.app.engine.BrushTextures
import com.prosketch.app.engine.Document
import com.prosketch.app.ui.canvas.CanvasView
import com.prosketch.app.ui.common.ProTheme
import com.prosketch.app.ui.common.UI
import com.prosketch.app.ui.common.UpdateDialog
import com.prosketch.app.ui.editor.Editor
import com.prosketch.app.ui.editor.Mode
import com.prosketch.app.ui.editor.Panel
import com.prosketch.app.ui.editor.Tool
import com.prosketch.app.ui.editor.EditorScreen
import com.prosketch.app.ui.gallery.GalleryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: ProjectStore
    private lateinit var prefs: Prefs
    private lateinit var brushes: BrushStore
    private lateinit var palettes: PaletteStore
    private var editor: Editor? = null
    private var canvasView: CanvasView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // language first: enum labels and default names are read at class initialisation
        L.init(this, getSharedPreferences("prosketch", MODE_PRIVATE).getInt("language", 0))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        prefs = Prefs(this)
        brushes = BrushStore(this)
        palettes = PaletteStore(this)
        BrushTextures.customDir = brushes.customDir
        com.prosketch.app.data.CrashLog.install(this) { editor?.emergencySave() }
        hideSystemBars()

        setContent {
            ProTheme {
                var openId by remember { mutableStateOf<String?>(null) }
                var doc by remember { mutableStateOf<Document?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(openId) {
                    val id = openId ?: return@LaunchedEffect
                    doc = null
                    val d = withContext(Dispatchers.IO) { runCatching { store.load(id) }.getOrNull() }
                    if (d == null) { error = L.t("작품을 열 수 없습니다"); openId = null } else doc = d
                }
                val d = doc
                when {
                    openId == null -> GalleryScreen(store) { openId = it }
                    d == null -> Box(Modifier.fillMaxSize().background(UI.bg), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = UI.accent)
                            Spacer(Modifier.height(12.dp))
                            Text(L.t("작품 여는 중…"), color = UI.text)
                        }
                    }
                    else -> {
                        val ed = remember(d) { Editor(this@MainActivity, d, store, prefs, brushes, palettes, lifecycleScope).also { editor = it } }
                        EditorScreen(ed, onCanvasView = { canvasView = it }) {
                            editor = null; canvasView = null
                            doc = null; openId = null
                        }
                    }
                }
                error?.let { androidx.compose.material3.AlertDialog(onDismissRequest = { error = null }, text = { Text(it) },
                    confirmButton = { androidx.compose.material3.TextButton({ error = null }) { Text(L.t("확인")) } }) }
                if (BuildConfig.SELF_UPDATE) UpdateDialog(this@MainActivity)
                CrashReportDialog()
                var guide by remember { mutableStateOf(!prefs.onboarded) }
                if (guide) com.prosketch.app.ui.common.OnboardingDialog { prefs.onboarded = true; guide = false }
            }
        }
    }

    /** Shown once after an unexpected exit: the work was saved; the report can be sent as a GitHub issue. */
    @androidx.compose.runtime.Composable
    private fun CrashReportDialog() {
        var report by remember { mutableStateOf(com.prosketch.app.data.CrashLog.pending(this)) }
        val r = report ?: return
        fun dismiss() { com.prosketch.app.data.CrashLog.clear(this); report = null }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text(L.t("ProSketch가 예기치 않게 종료되었습니다")) },
            text = { Text(L.t("작업 중이던 작품은 자동으로 저장했습니다. 오류 보고를 보내 주시면 문제를 고치는 데 큰 도움이 됩니다.\n\n") + r.lineSequence().take(6).joinToString("\n")) },
            dismissButton = {
                androidx.compose.material3.TextButton({
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ProSketch crash", r))
                    dismiss()
                }) { Text(L.t("복사")) }
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    val body = android.net.Uri.encode("```\n" + r.take(5000) + "\n```")
                    val url = "https://github.com/${com.prosketch.app.data.Updater.REPO}/issues/new?title=" + android.net.Uri.encode(L.t("앱 종료 오류")) + "&body=" + body
                    runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    dismiss()
                }) { Text(L.t("GitHub에 보고")) }
            },
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        editor?.let {
            // floating transform content is not part of the layer yet: commit it so nothing is lost
            if (it.mode == Mode.TRANSFORM) it.exitModes()
            if (!it.strokeActive) it.save()
        }
    }

    /** Hardware keyboard shortcuts (Book Cover Keyboard etc.), following Procreate's keyboard table. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ed = editor ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // while a text field is focused (layer name, text tool, …) keys go to it, not to shortcuts
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (imm.isAcceptingText) return super.dispatchKeyEvent(event)
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val transforming = ed.mode == Mode.TRANSFORM && ed.transformTool.session != null
        fun size(delta: Float) { ed.toolSize[ed.tool] = ((ed.toolSize[ed.tool] ?: 0.3f) + delta).coerceIn(0f, 1f); ed.hud(L.tf("크기 {0}%", ((ed.toolSize[ed.tool] ?: 0f) * 100).toInt())) }
        fun nudge(dx: Float, dy: Float): Boolean { if (!transforming) return false; val s = if (shift) 10f else 1f; ed.transformTool.nudge(dx * s, dy * s); return true }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_Z -> if (ctrl) { if (shift) ed.redo() else ed.undo(); true } else false
            KeyEvent.KEYCODE_Y -> if (ctrl) { ed.redo(); true } else false
            KeyEvent.KEYCODE_B -> when {
                ctrl -> { ed.adjustTool.begin(com.prosketch.app.engine.AdjustmentType.COLOR_BALANCE); true }
                else -> { ed.selectTool(Tool.BRUSH); true }
            }
            KeyEvent.KEYCODE_E -> if (!ctrl) { ed.selectTool(Tool.ERASER); true } else false
            KeyEvent.KEYCODE_S -> when {
                ctrl -> { ed.save(); ed.hud(L.t("저장됨")); true }
                shift -> { ed.selectTool(Tool.SMUDGE); true }
                else -> { if (ed.mode == Mode.SELECT) ed.exitModes() else ed.selectionTool.begin(); true }
            }
            KeyEvent.KEYCODE_V -> when {
                ctrl -> { ed.paste(); true }
                else -> { if (ed.mode == Mode.TRANSFORM) ed.transformTool.commit() else { ed.exitModes(); ed.transformTool.begin() }; true }
            }
            KeyEvent.KEYCODE_X -> if (ctrl) { ed.cut(); true } else { ed.swapWithPrevious(); true }
            KeyEvent.KEYCODE_C -> if (ctrl) { ed.copy(); true } else { ed.panel = if (ed.panel == Panel.COLOR) null else Panel.COLOR; true }
            KeyEvent.KEYCODE_A -> if (ctrl) { ed.copy(all = true); true } else false
            KeyEvent.KEYCODE_J -> if (ctrl) { ed.duplicateLayer(ed.doc.activeIndex); true } else false
            KeyEvent.KEYCODE_U -> if (ctrl) { ed.adjustTool.begin(com.prosketch.app.engine.AdjustmentType.HSB); true } else false
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (ctrl) { ed.clearLayer(); true } else false
            KeyEvent.KEYCODE_LEFT_BRACKET -> { size(if (ctrl) -0.01f else if (shift) -0.10f else -0.05f); true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { size(if (ctrl) 0.01f else if (shift) 0.10f else 0.05f); true }
            KeyEvent.KEYCODE_0 -> if (ctrl) { canvasView?.fitToScreen(); true } else false
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> if (ctrl) { canvasView?.zoomBy(1.25f); true } else false
            KeyEvent.KEYCODE_MINUS -> if (ctrl) { canvasView?.zoomBy(0.8f); true } else false
            KeyEvent.KEYCODE_D -> if (ctrl) { ed.clearSelection(); true } else false
            KeyEvent.KEYCODE_T -> if (ctrl) { ed.exitModes(); ed.transformTool.begin(); true } else false
            KeyEvent.KEYCODE_L -> { ed.panel = if (ed.panel == Panel.LAYERS) null else Panel.LAYERS; true }
            KeyEvent.KEYCODE_K -> if (ctrl) { ed.panel = if (ed.panel == Panel.ACTIONS) null else Panel.ACTIONS; true } else false
            KeyEvent.KEYCODE_H -> if (ctrl) { ed.panel = if (ed.panel == Panel.HISTORY) null else Panel.HISTORY; true } else false
            KeyEvent.KEYCODE_M -> if (!ctrl) { ed.toggleMirrorView(); true } else false
            KeyEvent.KEYCODE_G -> if (ctrl) { ed.createGroup(); true } else if (!ctrl) { ed.toggleGrayscaleView(); true } else false
            KeyEvent.KEYCODE_SPACE -> { canvasView?.toggleQuickMenu(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-1f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(1f, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(0f, -1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0f, 1f)
            KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    ed.quickMenuAt != null -> ed.quickMenuAt = null
                    ed.textEditing != null -> ed.endTextEdit(false)
                    ed.panel != null -> ed.panel = null
                    ed.mode != Mode.PAINT -> ed.exitModes()
                }
                true
            }
            KeyEvent.KEYCODE_ENTER -> if (ed.mode != Mode.PAINT) { ed.exitModes(); true } else false
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }
}
