package com.prosketch.app.ui.editor

import com.prosketch.app.ui.common.screen
import com.prosketch.app.L

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.ui.common.PanelBox
import com.prosketch.app.ui.common.UI

/**
 * Undo history as a list (Krita / Clip Studio style): tap any step to go back or forward to it.
 * Steps after the current one stay available until something new is done.
 */
@Composable
fun HistoryPanel(ed: Editor, modifier: Modifier = Modifier) {
    ed.version.let { }
    ed.canUndo.let { }; ed.canRedo.let { }
    val done = ed.history.undoLabels()
    val undone = ed.history.redoLabels()
    val rows = listOf(L.t("작품 열기")) + done + undone
    val current = done.size
    val state = rememberLazyListState()
    LaunchedEffect(rows.size, current) { state.scrollToItem((current - 4).coerceAtLeast(0)) }
    PanelBox(modifier, title = L.t("작업 내역")) {
        Text(L.tf("{0}단계 완료 · {1}단계 다시 실행 가능 (최대 250)", done.size, undone.size), color = UI.text2, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        LazyColumn(Modifier.heightIn(max = screen().body(520.dp, 70.dp)), state = state) {
            items(rows.size) { i ->
                val isCurrent = i == current
                val future = i > current
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) UI.accent else Color.Transparent)
                        .clickable { if (!isCurrent) ed.jumpHistory(i) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (i == 0) "·" else "$i", color = if (isCurrent) Color.White else UI.text2, fontSize = 11.sp, modifier = Modifier.width(34.dp))
                    Text(rows[i], color = when { isCurrent -> Color.White; future -> UI.text2.copy(alpha = 0.55f); else -> UI.text }, fontSize = 14.sp)
                }
            }
        }
    }
}

