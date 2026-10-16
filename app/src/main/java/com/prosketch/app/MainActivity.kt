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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        prefs = Prefs(this)
        brushes = BrushStore(this)
        palettes = PaletteStore(this)
        BrushTextures.customDir = brushes.customDir
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
                    if (d == null) { error = "작품을 열 수 없습니다"; openId = null } else doc = d
                }
                val d = doc
                when {
                    openId == null -> GalleryScreen(store) { openId = it }
                    d == null -> Box(Modifier.fillMaxSize().background(UI.bg), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = UI.accent)
                            Spacer(Modifier.height(12.dp))
                            Text("작품 여는 중…", color = UI.text)
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
                    confirmButton = { androidx.compose.material3.TextButton({ error = null }) { Text("확인") } }) }
                UpdateDialog(this@MainActivity)
            }
        }
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

    /** Hardware keyboard shortcuts (Book Cover Keyboard etc.). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ed = editor ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // a focused text field (layer name, text tool, …) gets the key first; shortcuts only see the rest
        if (super.dispatchKeyEvent(event)) return true
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_Z -> if (ctrl) { if (shift) ed.redo() else ed.undo(); true } else false
            KeyEvent.KEYCODE_Y -> if (ctrl) { ed.redo(); true } else false
            KeyEvent.KEYCODE_B -> if (!ctrl) { ed.exitModes(); ed.tool = Tool.BRUSH; true } else false
            KeyEvent.KEYCODE_S -> if (!ctrl) { ed.exitModes(); ed.tool = Tool.SMUDGE; true } else { ed.save(); ed.hud("저장됨"); true }
            KeyEvent.KEYCODE_E -> if (!ctrl) { ed.exitModes(); ed.tool = Tool.ERASER; true } else false
            KeyEvent.KEYCODE_LEFT_BRACKET -> { ed.toolSize[ed.tool] = ((ed.toolSize[ed.tool] ?: 0.3f) - 0.03f).coerceIn(0f, 1f); true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { ed.toolSize[ed.tool] = ((ed.toolSize[ed.tool] ?: 0.3f) + 0.03f).coerceIn(0f, 1f); true }
            KeyEvent.KEYCODE_0 -> if (ctrl) { canvasView?.fitToScreen(); true } else false
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> if (ctrl) { canvasView?.zoomBy(1.25f); true } else false
            KeyEvent.KEYCODE_MINUS -> if (ctrl) { canvasView?.zoomBy(0.8f); true } else false
            KeyEvent.KEYCODE_C -> if (ctrl) { ed.copy(); true } else false
            KeyEvent.KEYCODE_X -> if (ctrl) { ed.cut(); true } else false
            KeyEvent.KEYCODE_V -> if (ctrl) { ed.paste(); true } else false
            KeyEvent.KEYCODE_D -> if (ctrl) { ed.clearSelection(); true } else false
            KeyEvent.KEYCODE_T -> if (ctrl) { ed.exitModes(); ed.transformTool.begin(); true } else false
            KeyEvent.KEYCODE_L -> { ed.panel = if (ed.panel == com.prosketch.app.ui.editor.Panel.LAYERS) null else com.prosketch.app.ui.editor.Panel.LAYERS; true }
            KeyEvent.KEYCODE_ESCAPE -> { if (ed.panel != null) ed.panel = null else if (ed.mode != Mode.PAINT) ed.exitModes(); true }
            KeyEvent.KEYCODE_ENTER -> if (ed.mode != Mode.PAINT) { ed.exitModes(); true } else false
            else -> false
        }
        return handled
    }
}
