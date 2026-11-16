package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing

/**
 * 화면 아래에서 올라온 확인 시트 모양(어두운 바탕 + 위가 둥근 연회색 판). 차단 · 나가기 · 신고처럼 짧게 확인하는 화면에 쓴다.
 * 열림 · 닫힘 상태는 호출 화면이 소유한다. 바탕을 누르면 [onDismiss](null 이면 닫지 않음 — 처리 중).
 * 바탕 탭은 보조 수단이다(화면 리더에는 보이지 않고, 시트가 화면을 다 채우면 누를 곳도 없다). 닫는 버튼은 호출 화면이 시트 안에 둔다.
 * 내용이 길거나 키보드가 올라오면 시트 안에서 스크롤한다.
 */
@Composable
fun ActionSheet(onDismiss: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dismiss = rememberUpdatedState(onDismiss)
    // clickable 은 아래 글자를 한 접근성 노드로 합쳐 버려서 pointerInput 으로 탭만 받는다
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
        .pointerInput(Unit) { detectTapGestures { dismiss.value?.invoke() } }
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth()
            // 시트 안을 눌러도 바탕의 닫기가 실행되지 않게 탭을 여기서 끝낸다
            .pointerInput(Unit) { detectTapGestures {} },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = AppColors.Sheet) {
            // 키보드 높이는 스크롤 바깥에서 빼야 입력칸이 키보드 위로 따라 올라온다. 나머지 아래 · 좌우 안전영역은 안쪽 여백으로 줘서 판이 화면 맨 아래까지 닿게 한다
            Column(Modifier.imePadding().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(AppSpacing.Section), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .background(AppColors.BorderSubtle, CircleShape))
                content()
            }
        }
    }
}

/** 시트 안의 결과 안내: (성공이면 초록 체크) + 제목 + 설명, 가운데 정렬. 결과가 바뀌면 화면 리더가 읽는다. */
@Composable
fun SheetResult(done: Boolean, title: String, help: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (done) Box(Modifier.size(AppSizes.Avatar).background(AppColors.SelectionContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", style = MaterialTheme.typography.headlineMedium, color = AppColors.OnSelection, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(help, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
    }
}
