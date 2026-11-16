package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenPaddingValues

/**
 * 상태 화면 + 지우면 안 되는 안내 한 줄("확인 전에는 다시 보내지 않아요" 같은 중복 요청 방지 문구).
 * 공용 StateScreen 에는 설명 자리가 없어 같은 모양으로 따로 둔다. 제안 · 차단 · 신고의 결과 확인 화면이 함께 쓴다.
 */
@Composable
internal fun StateNoteScreen(kind: StateKind, title: String, note: String, modifier: Modifier = Modifier, barTitle: String? = null,
    onBack: (() -> Unit)? = null, primary: StateAction? = null, secondary: StateAction? = null) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (barTitle != null) AppTopBar(barTitle, onBack = onBack)
            Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                StateView(kind, title)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
            if (primary != null || secondary != null) Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = AppSpacing.Large).padding(top = AppSpacing.Small, bottom = AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                primary?.let { AppButton(it.label, Modifier.fillMaxWidth(), loading = it.loading, enabled = it.enabled, onClick = it.onClick) }
                secondary?.let { AppButton(it.label, Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                    loading = it.loading, enabled = it.enabled, onClick = it.onClick) }
            }
        }
    }
}

/** 이전 요청을 확인하는 동안: 위쪽 줄 + 뼈대. 시트가 뜨기 전이라 빈 화면 대신 둔다. 화면 리더에는 [label] 한 문장만 읽힌다 */
@Composable
internal fun StateLoadingScreen(barTitle: String, label: String, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(barTitle, onBack = onBack)
            SkeletonGroup(Modifier.padding(screenPaddingValues()), label = label) {
                SkeletonBone(Modifier.fillMaxWidth(0.6f), height = 24.dp)
                repeat(3) { SkeletonRow(avatar = AppSizes.Icon) }
            }
        }
    }
}
