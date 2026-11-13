package com.ssafy.e106.features.onboarding.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
import com.ssafy.e106.core.ui.components.NoticeBar
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
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

/** [first] 는 이 단계가 지금 차례일 때 뒤 단계의 막힌 버튼에 쓰는 문구("프로필부터 작성해 주세요") */
private class HubRow(val name: StringResource, val desc: StringResource? = null, val facts: List<StringResource> = emptyList(), val start: StringResource? = null,
    val first: StringResource? = null)
private val Rows = listOf(
    HubRow(Res.string.onboarding_hub_keyring),
    HubRow(Res.string.onboarding_hub_profile, Res.string.onboarding_hub_profile_desc, listOf(Res.string.onboarding_hub_fact_3min), Res.string.onboarding_hub_profile_start,
        Res.string.onboarding_hub_profile_first),
    HubRow(Res.string.onboarding_hub_survey, Res.string.onboarding_hub_survey_desc,
        listOf(Res.string.onboarding_hub_fact_100, Res.string.onboarding_hub_fact_15min), Res.string.onboarding_hub_survey_start,
        Res.string.onboarding_hub_survey_first),
    HubRow(Res.string.onboarding_hub_preferences, Res.string.onboarding_hub_preferences_desc, listOf(Res.string.onboarding_hub_fact_1min), Res.string.onboarding_hub_preferences_start,
        Res.string.onboarding_hub_preferences_first),
    HubRow(Res.string.onboarding_hub_sync, Res.string.onboarding_hub_sync_desc,
        listOf(Res.string.onboarding_hub_fact_keyring, Res.string.onboarding_hub_fact_1min), Res.string.onboarding_hub_sync_start),
)
private val DoneBackground = Color(0xFFF7FAF6)
private val OpenBorder = Color(0xFFC9C9C9)   // 펼친 단계 테두리: 검정 대신 연한 회색(시안)

/**
 * 가입 준비 허브(아코디언 시안): 진행 막대 + 단계 목록. 진행 중인 단계만 펼친다.
 * [previous] 는 이 허브에서 마지막으로 펼쳐 둔 줄. 한 단계를 끝내고 돌아오면 그 줄을 잠깐 펼친 채 보여 준 뒤 접고 다음 줄을 편다(버튼 없이 자동).
 * 아직 차례가 아닌 단계도 눌러서 펼쳐 볼 수 있다(무엇을 하는지 미리 보기). 그 안의 버튼은 막혀 있고 "○○부터 해 주세요"로 지금 할 단계를 알려 준다.
 * [onRefresh] 는 가입 상태를 다시 확인한다(당겨서 새로고침). 실패([error])는 단계 목록을 그대로 둔 채 아래 띠로 알린다.
 */
@Composable
internal fun SignupHub(current: Int, previous: Int, onSettled: (Int) -> Unit, onStart: (() -> Unit)?, busy: Boolean, error: String?, onLogout: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    /** 있으면 지금 단계의 줄 안에 시작 버튼 대신 이 내용을 펼쳐 넣는다(새 화면으로 들어가지 않고 그 자리에서 입력) */
    inline: (@Composable ColumnScope.() -> Unit)? = null,
    /** 있으면 다음 단계의 줄도 미리 펼쳐 두고 막힌 버튼 대신 이 내용을 넣는다(희망 나이 단계의 키링 동기화) */
    nextInline: (@Composable ColumnScope.() -> Unit)? = null) {
    var shown by remember(current) { mutableStateOf(hubFirstShown(previous, current)) }
    LaunchedEffect(current) {
        if (shown != current) { delay(900); shown = current }
        onSettled(current)
    }
    // 미리 펼쳐 본 대기 단계(한 번에 하나). 단계가 바뀌면 접는다
    var peek by remember(current) { mutableStateOf(-1) }
    val blocked = Rows.getOrNull(current)?.first?.let { stringResource(it) }
    val total = Rows.size - 1
    val progress by animateFloatAsState(shown.toFloat() / Rows.size)
    val (refreshing, pull) = pullRefresh(busy) { onRefresh?.invoke() }
    OnboardingPanel(stringResource(Res.string.onboarding_title), refreshing = refreshing, onRefresh = pull.takeIf { onRefresh != null },
        bottom = { if (error != null) NoticeBar(error, actionEnabled = !busy, onAction = onRefresh) }) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(stringResource(Res.string.onboarding_hub_count, shown.coerceAtMost(total), total),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.BorderSubtle)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(AppColors.SelectionBorder))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Rows.forEachIndexed { i, row ->
                val waiting = i > current && shown == current
                val openNext = nextInline != null && waiting && i == current + 1
                Step(row, done = i < shown, open = i == shown, onStart = onStart?.takeIf { i == current && !busy },
                    peeked = openNext || (waiting && i == peek), blocked = blocked.takeIf { waiting && !openNext },
                    inline = if (openNext) nextInline else inline.takeIf { i == current && shown == current },
                    onToggle = if (waiting && !openNext) ({ peek = if (peek == i) -1 else i }) else null)
            }
        }
        TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.onboarding_logout), color = AppColors.TextSecondary)
        }
    }
}

/** 가입 정보 확인 중: 진행 막대와 단계 줄 자리에 뼈대 */
@Composable
internal fun HubSkeleton() {
    SkeletonGroup(spacing = AppSpacing.Small) {
        SkeletonBone(width = 72.dp, height = 12.dp)
        SkeletonBone(height = 6.dp)
        Spacer(Modifier.height(AppSpacing.Small))
        repeat(Rows.size) { SkeletonBone(height = 44.dp, shape = RoundedCornerShape(14.dp)) }
    }
}

@Composable
private fun Step(row: HubRow, done: Boolean, open: Boolean, onStart: (() -> Unit)?, peeked: Boolean = false, blocked: String? = null,
    onToggle: (() -> Unit)? = null, inline: (@Composable ColumnScope.() -> Unit)? = null) {
    val expanded = open || peeked
    val ink = when { done -> AppColors.OnSelection; open -> AppColors.TextPrimary; else -> AppColors.TextSecondary }   // 대기 줄도 읽을 수 있는 대비(4.5:1 이상)
    Surface(shape = RoundedCornerShape(14.dp), color = if (done) DoneBackground else AppColors.Background,
        border = if (expanded) BorderStroke(1.5.dp, OpenBorder) else null, shadowElevation = if (open) 2.dp else 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth()
                .then(if (onToggle != null) Modifier.heightIn(min = AppSpacing.ControlMinHeight).clickable(role = Role.Button, onClick = onToggle) else Modifier)
                .padding(horizontal = AppSpacing.Medium, vertical = 10.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(row.name), Modifier.weight(1f), color = ink, fontWeight = FontWeight.Bold,
                    style = if (open) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                Text(stringResource(when { done -> Res.string.onboarding_hub_done; open -> Res.string.onboarding_hub_now; else -> Res.string.onboarding_hub_wait }),
                    color = ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(expanded && row.desc != null, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(start = AppSpacing.Medium, end = AppSpacing.Medium, bottom = AppSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    row.desc?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.facts.forEach {
                            Text(stringResource(it), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(AppColors.SurfaceMuted).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    // 차례가 아닌 단계는 시작 버튼 자리에 막힌 버튼으로 지금 할 단계를 알려 준다
                    if (peeked && blocked != null) AppButton(blocked, Modifier.fillMaxWidth(), enabled = false, onClick = {})
                    else if (inline != null) inline()
                    else row.start?.let { AppButton(stringResource(it), Modifier.fillMaxWidth(), enabled = onStart != null, onClick = { onStart?.invoke() }) }
                }
            }
        }
    }
}
