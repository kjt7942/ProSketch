package com.prosketch.app.ui.common

import com.prosketch.app.L

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private class Page(val icon: ImageVector, val title: String, val items: List<Pair<String, String>>)

private val pages = listOf(
    Page(Icons.Filled.Brush, L.t("ProSketch에 오신 것을 환영합니다"), listOf(
        L.t("브러시") to L.t("기본 브러시 31종 · 브러시 스튜디오에서 직접 만들기"),
        L.t("레이어") to L.t("그룹 · 마스크 · 클리핑 · 혼합 모드 26종"),
        L.t("조정") to L.t("흐림, 노이즈, 글리치, 픽셀 유동화, 복제 · 펜슬로 칠해서 적용"),
        L.t("저장") to L.t("자동 저장 · PSD(레이어·그룹·마스크 유지), PNG, 타임랩스 영상"),
    )),
    Page(Icons.Filled.TouchApp, L.t("손가락 제스처"), listOf(
        L.t("두 손가락 탭") to L.t("실행 취소 (누르고 있으면 연속)"),
        L.t("세 손가락 탭") to L.t("다시 실행"),
        L.t("두 손가락 핀치 · 비틀기") to L.t("확대 · 회전 / 빠르게 오므리면 화면에 맞춤"),
        L.t("손가락 길게 누르기") to L.t("스포이드 (설정에서 QuickMenu로 변경 가능)"),
        L.t("세 손가락 문지르기 / 아래로 쓸기") to L.t("레이어 지우기 / 복사·붙여넣기 메뉴"),
        L.t("네 손가락 탭") to L.t("전체 화면"),
    )),
    Page(Icons.Filled.Layers, L.t("레이어와 색상"), listOf(
        L.t("색상 버튼을 캔버스로 끌기") to L.t("ColorDrop 채우기 · 누른 채 좌우로 임계값"),
        L.t("그리고 멈추기") to L.t("QuickShape: 선·원·사각형으로 자동 보정"),
        L.t("레이어를 오른쪽으로 밀기") to L.t("여러 레이어 선택 → 그룹 / 병합 / 삭제"),
        L.t("레이어를 길게 눌러 끌기") to L.t("순서 변경 · 그룹 안으로 넣기 (왼쪽으로 끌면 밖으로)"),
        L.t("레이어를 왼쪽으로 밀기") to L.t("잠금 · 복제 · 삭제"),
    )),
    Page(Icons.Filled.Edit, L.t("S펜"), listOf(
        L.t("필압 · 기울기") to L.t("브러시 크기와 불투명도에 반영 · 압력 곡선은 설정에서"),
        L.t("S펜 버튼") to L.t("누르는 동안 지우개 (스포이드 · 실행 취소 · QuickMenu로 변경)"),
        L.t("호버") to L.t("캔버스 위에 띄우면 브러시 크기 미리보기"),
        L.t("손바닥") to L.t("S펜을 쓰기 시작하면 손가락은 화면 이동만 (설정에서 변경)"),
        L.t("키보드") to L.t("B 브러시 · E 지우개 · S 선택 · V 변형 · Space QuickMenu"),
    )),
)

/** First-launch guide (also reachable from Actions › Help). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingDialog(onDone: () -> Unit) {
    val state = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF0101012)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(state, Modifier.fillMaxWidth().height(520.dp)) { i ->
                    val p = pages[i]
                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(UI.panel).padding(32.dp)) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(UI.accent), contentAlignment = Alignment.Center) {
                            Icon(p.icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(p.title, color = UI.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        p.items.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(k, color = UI.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(250.dp))
                                Text(v, color = UI.text2, fontSize = 15.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages.size) { i ->
                        Box(Modifier.size(if (state.currentPage == i) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (state.currentPage == i) UI.accent else Color(0x55FFFFFF)))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextBtn(L.t("건너뛰기")) { onDone() }
                    val last = state.currentPage == pages.size - 1
                    TextBtn(if (last) L.t("시작하기") else L.t("다음"), true) {
                        if (last) onDone() else scope.launch { state.animateScrollToPage(state.currentPage + 1) }
                    }
                }
            }
        }
    }
}
