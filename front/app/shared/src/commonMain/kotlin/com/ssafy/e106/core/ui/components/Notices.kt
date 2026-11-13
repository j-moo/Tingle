package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 아래 띠: 보던 화면은 그대로 두고 한 줄로 알린다(새로고침 실패 · 연결 끊김). 동작은 하나만 둔다.
 * 화면 맨 아래(아래 탭이나 버튼 위)에 두고, 다시 성공하면 호출부가 지운다.
 */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier, actionText: String? = stringResource(Res.string.state_retry),
    actionEnabled: Boolean = true, onAction: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small)
        .semantics { liveRegion = LiveRegionMode.Polite }, shape = AppShapes.Control, color = AppColors.ActionPrimary) {
        Row(Modifier.heightIn(min = AppSizes.TouchTarget).padding(start = AppSpacing.Large, end = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(text, Modifier.weight(1f).padding(vertical = AppSpacing.Medium), style = MaterialTheme.typography.bodyMedium, color = AppColors.OnPrimary)
            if (actionText != null && onAction != null) Text(actionText, style = MaterialTheme.typography.labelLarge,
                color = AppColors.OnPrimary.copy(alpha = if (actionEnabled) 1f else 0.5f),
                modifier = Modifier.clip(AppShapes.Small).clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                    .heightIn(min = AppSizes.TouchTarget).wrapContentHeight().padding(horizontal = AppSpacing.Medium))
        }
    }
}

/** 저장 · 전송 실패: 입력은 그대로 두고 버튼 바로 위에 빨간 한 줄로 알린다. 버튼 이름은 호출부가 "다시 시도"로 바꾼다 */
@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Icon(painterResource(Res.drawable.ic_state_circle_alert), null, Modifier.padding(top = 2.dp).size(18.dp), tint = AppColors.Error)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.Error)
    }
}

/** 목록 끝의 상태 */
enum class ListTailState { Loading, Failed, End }

/** 목록 이어 받기: 받는 중은 뼈대 줄, 실패는 한 줄 + "다시 시도", 끝은 작은 글 한 줄. 더 없으면 호출부가 아예 그리지 않는다 */
@Composable
fun ListTail(state: ListTailState, modifier: Modifier = Modifier, onRetry: () -> Unit = {}, retryEnabled: Boolean = true,
    failedText: String = stringResource(Res.string.list_more_failed), endText: String = stringResource(Res.string.list_end),
    loading: @Composable () -> Unit = { SkeletonRow(); SkeletonRow() }) {
    when (state) {
        ListTailState.Loading -> SkeletonGroup(modifier) { loading() }
        ListTailState.Failed -> Row(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(failedText, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            TextAction(stringResource(Res.string.state_retry), onRetry, enabled = retryEnabled, icon = false)
        }
        ListTailState.End -> Text(endText, modifier.fillMaxWidth().padding(vertical = AppSpacing.Medium),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
    }
}
