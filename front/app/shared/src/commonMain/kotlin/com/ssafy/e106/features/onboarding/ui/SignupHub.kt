package com.ssafy.e106.features.onboarding.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val HubStatuses = listOf(OnboardingStatus.PROFILE_REQUIRED, OnboardingStatus.SURVEY_REQUIRED,
    OnboardingStatus.PREFERENCES_REQUIRED, OnboardingStatus.SYNC_PENDING)

/** 허브 줄 번호(0 = 키링 등록, 늘 완료). 허브에 없는 단계(약관 · 키링 등록 · READY)는 -1 */
internal fun hubStep(status: OnboardingStatus) = HubStatuses.indexOf(status).let { if (it < 0) -1 else it + 1 }

/** 처음 펼칠 줄: 직전에 펼쳐 둔 줄이 지금보다 앞이면(단계를 끝내고 돌아옴) 그 줄부터 보여 주고 접는다 */
internal fun hubFirstShown(previous: Int, current: Int) = if (previous in 1 until current) previous else current

private class HubRow(val name: StringResource, val desc: StringResource? = null, val facts: List<StringResource> = emptyList(), val start: StringResource? = null)
private val Rows = listOf(
    HubRow(Res.string.onboarding_hub_keyring),
    HubRow(Res.string.onboarding_hub_profile, Res.string.onboarding_hub_profile_desc, listOf(Res.string.onboarding_hub_fact_3min), Res.string.onboarding_hub_profile_start),
    HubRow(Res.string.onboarding_hub_survey, Res.string.onboarding_hub_survey_desc,
        listOf(Res.string.onboarding_hub_fact_100, Res.string.onboarding_hub_fact_15min), Res.string.onboarding_hub_survey_start),
    HubRow(Res.string.onboarding_hub_preferences, Res.string.onboarding_hub_preferences_desc, listOf(Res.string.onboarding_hub_fact_1min), Res.string.onboarding_hub_preferences_start),
    HubRow(Res.string.onboarding_hub_sync, Res.string.onboarding_hub_sync_desc,
        listOf(Res.string.onboarding_hub_fact_keyring, Res.string.onboarding_hub_fact_1min), Res.string.onboarding_hub_sync_start),
)
private val DoneBackground = Color(0xFFF7FAF6)
private val OpenBorder = Color(0xFFC9C9C9)   // 펼친 단계 테두리: 검정 대신 연한 회색(시안)

/**
 * 가입 준비 허브(아코디언 시안): 진행 막대 + 단계 목록. 진행 중인 단계만 펼친다.
 * [previous] 는 이 허브에서 마지막으로 펼쳐 둔 줄. 한 단계를 끝내고 돌아오면 그 줄을 잠깐 펼친 채 보여 준 뒤 접고 다음 줄을 편다(버튼 없이 자동).
 */
@Composable
internal fun SignupHub(current: Int, previous: Int, onSettled: (Int) -> Unit, onStart: (() -> Unit)?, busy: Boolean, error: String?, onLogout: () -> Unit) {
    var shown by remember(current) { mutableStateOf(hubFirstShown(previous, current)) }
    LaunchedEffect(current) {
        if (shown != current) { delay(900); shown = current }
        onSettled(current)
    }
    val total = Rows.size - 1
    val progress by animateFloatAsState(shown.toFloat() / Rows.size)
    OnboardingPanel(stringResource(Res.string.onboarding_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(stringResource(Res.string.onboarding_hub_count, shown.coerceAtMost(total), total),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.BorderSubtle)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(AppColors.SelectionBorder))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Rows.forEachIndexed { i, row -> Step(row, done = i < shown, open = i == shown, onStart = onStart?.takeIf { i == current && !busy }) }
        }
        error?.let { OnboardingError(it) }
        TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.onboarding_logout), color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun Step(row: HubRow, done: Boolean, open: Boolean, onStart: (() -> Unit)?) {
    val ink = when { done -> AppColors.OnSelection; open -> AppColors.TextPrimary; else -> AppColors.TextSecondary }   // 대기 줄도 읽을 수 있는 대비(4.5:1 이상)
    Surface(shape = RoundedCornerShape(14.dp), color = if (done) DoneBackground else AppColors.Background,
        border = if (open) BorderStroke(1.5.dp, OpenBorder) else null, shadowElevation = if (open) 2.dp else 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Medium, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
                Text(stringResource(row.name), Modifier.weight(1f), color = ink, fontWeight = FontWeight.Bold,
                    style = if (open) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                Text(stringResource(when { done -> Res.string.onboarding_hub_done; open -> Res.string.onboarding_hub_now; else -> Res.string.onboarding_hub_wait }),
                    color = ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(open && row.desc != null, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(start = AppSpacing.Medium, end = AppSpacing.Medium, bottom = AppSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    row.desc?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.facts.forEach {
                            Text(stringResource(it), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(AppColors.SurfaceMuted).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    row.start?.let { AppButton(stringResource(it), Modifier.fillMaxWidth(), enabled = onStart != null, onClick = { onStart?.invoke() }) }
                }
            }
        }
    }
}
