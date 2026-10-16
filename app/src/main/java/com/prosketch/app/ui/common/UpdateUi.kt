package com.prosketch.app.ui.common

import com.prosketch.app.L

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prosketch.app.data.ReleaseInfo
import com.prosketch.app.data.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val rel: ReleaseInfo) : UpdatePhase
    data class Downloading(val rel: ReleaseInfo, val progress: Float) : UpdatePhase
    data class Ready(val rel: ReleaseInfo, val apk: File, val needPermission: Boolean = false) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/** Process-wide update state, so the dialog survives switching between gallery and editor. */
object UpdateController {
    var phase by mutableStateOf<UpdatePhase>(UpdatePhase.Idle)
    private var autoChecked = false
    // outlives any screen, so leaving the gallery does not cancel a running download
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** [silent]: only surface a dialog when a newer version exists (used for the launch check). */
    fun check(ctx: Context, silent: Boolean) {
        if (silent) { if (autoChecked) return; autoChecked = true }
        if (phase is UpdatePhase.Downloading || phase is UpdatePhase.Checking) return
        if (!silent) phase = UpdatePhase.Checking
        val local = Updater.currentVersion(ctx)
        scope.launch {
            val result = runCatching { Updater.fetchLatest() }
            phase = result.fold(
                onSuccess = { rel -> if (rel != null && Updater.isNewer(rel.version, local)) UpdatePhase.Available(rel) else if (silent) UpdatePhase.Idle else UpdatePhase.UpToDate },
                onFailure = { if (silent) UpdatePhase.Idle else UpdatePhase.Failed(L.tf("업데이트 확인 실패: {0}", it.message ?: it.javaClass.simpleName)) },
            )
        }
    }

    fun download(ctx: Context, rel: ReleaseInfo) {
        phase = UpdatePhase.Downloading(rel, 0f)
        scope.launch {
            runCatching {
                Updater.download(ctx.applicationContext, rel) { p -> scope.launch(Dispatchers.Main) { if (phase is UpdatePhase.Downloading) phase = UpdatePhase.Downloading(rel, p) } }
            }.onSuccess { phase = UpdatePhase.Ready(rel, it); install(ctx) }
                .onFailure { phase = UpdatePhase.Failed(L.tf("다운로드 실패: {0}", it.message ?: it.javaClass.simpleName)) }
        }
    }

    fun install(ctx: Context) {
        val ready = phase as? UpdatePhase.Ready ?: return
        if (Updater.needsInstallPermission(ctx)) {
            phase = ready.copy(needPermission = true)
            Updater.openInstallPermissionSettings(ctx)
        } else {
            phase = ready.copy(needPermission = false)
            Updater.install(ctx, ready.apk)
        }
    }
}

private fun mb(bytes: Long) = "%.1fMB".format(bytes / 1048576f)

/** Release notes as plain text: drop markdown headings, bullets and code ticks. */
private fun plainNotes(md: String): String = md.lines()
    .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().replace("`", "").replace("**", "") }
    .map { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
    .joinToString("\n").trim()

@Composable
fun UpdateDialog(ctx: Context) {
    val c = UpdateController
    // after the user allowed "install unknown apps" in Settings and came back, continue right away
    androidx.lifecycle.compose.LifecycleResumeEffect(c.phase) {
        val p = c.phase
        if (p is UpdatePhase.Ready && p.needPermission && !Updater.needsInstallPermission(ctx)) c.install(ctx)
        onPauseOrDispose { }
    }
    val close = { c.phase = UpdatePhase.Idle }
    when (val p = c.phase) {
        UpdatePhase.Idle -> {}
        UpdatePhase.Checking -> AlertDialog(onDismissRequest = close,
            title = { Text(L.t("업데이트 확인")) },
            text = { Column { Text(L.t("GitHub에서 최신 버전을 확인하는 중…")); Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = UI.accent) } },
            confirmButton = { TextButton(close) { Text(L.t("닫기")) } })
        UpdatePhase.UpToDate -> AlertDialog(onDismissRequest = close,
            title = { Text(L.t("최신 버전입니다")) },
            text = { Text(L.tf("현재 버전 {0}이(가) 최신입니다.", Updater.currentVersion(ctx))) },
            confirmButton = { TextButton(close) { Text(L.t("확인")) } })
        is UpdatePhase.Available -> AlertDialog(onDismissRequest = close,
            title = { Text(L.tf("새 버전 {0}", p.rel.version)) },
            text = {
                Column {
                    Text(L.tf("현재 {0} → {1}  ·  {2}", Updater.currentVersion(ctx), p.rel.version, mb(p.rel.apkSize)), color = UI.text2, fontSize = 13.sp)
                    if (p.rel.notes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(plainNotes(p.rel.notes), fontSize = 14.sp, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(L.t("작품은 그대로 유지됩니다."), color = UI.text2, fontSize = 12.sp)
                }
            },
            dismissButton = { TextButton(close) { Text(L.t("나중에")) } },
            confirmButton = { TextButton({ c.download(ctx, p.rel) }) { Text(L.t("업데이트")) } })
        is UpdatePhase.Downloading -> AlertDialog(onDismissRequest = {},
            title = { Text(L.tf("{0} 다운로드 중", p.rel.version)) },
            text = { Column { LinearProgressIndicator({ p.progress }, Modifier.fillMaxWidth(), color = UI.accent); Spacer(Modifier.height(8.dp)); Text("${(p.progress * 100).toInt()}%", color = UI.text2) } },
            confirmButton = {})
        is UpdatePhase.Ready -> AlertDialog(onDismissRequest = close,
            title = { Text(L.t("설치 준비 완료")) },
            text = {
                Text(if (p.needPermission) L.t("설정에서 'ProSketch'의 '이 출처 허용'을 켠 뒤 돌아와서 [설치]를 누르세요.")
                     else L.t("Android 설치 화면에서 [업데이트]를 누르면 설치됩니다."))
            },
            dismissButton = { TextButton(close) { Text(L.t("닫기")) } },
            confirmButton = { TextButton({ c.install(ctx) }) { Text(L.t("설치")) } })
        is UpdatePhase.Failed -> AlertDialog(onDismissRequest = close,
            title = { Text(L.t("업데이트")) },
            text = { Text(p.message) },
            dismissButton = { TextButton(close) { Text(L.t("닫기")) } },
            confirmButton = {
                TextButton({
                    close()
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(L.t("릴리스 페이지 열기")) }
            })
    }
}
