package com.ssafy.e106.features.proposals.ui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TimeSource

enum class ProposalListLoad { Loading, Ready, Failed }
enum class ProposalPageState { More, Loading, Failed, End }
enum class MyProposalDecision { NotResponded, AcceptedWaiting }
enum class ProposalPhotoState { NotProvided, Loading, Ready, Failed, Removed, Unavailable }

/** 활성 제안만 전달한다. 상대의 결정·결정 시각 필드는 의도적으로 포함하지 않는다. */
data class ProposalListItem(
    val proposalId: Long,
    val nickname: String?,
    val age: Int?,
    val myDecision: MyProposalDecision = MyProposalDecision.NotResponded,
    // 마감 시점(기기 단조 시계 기준). 서버의 (마감 시각 − 서버 시각)으로 잡아 기기 시계가 틀려도 남은 시간이 맞다
    val closesAt: TimeSource.Monotonic.ValueTimeMark? = null,
    val photoState: ProposalPhotoState = ProposalPhotoState.NotProvided,
    val photoRequest: ProposalPhotoRequest? = null,
    val photoImage: ProposalPhotoImage? = null,
    // 내가 수락했는데 끝난 제안(매칭 안 됨 · 기한 지남). "응답 대기" 맨 위에 한 번 보이고, 눌러 확인하면 사라진다
    val ended: ProposalDecisionStage? = null,
)

data class ProposalListState(
    val load: ProposalListLoad = ProposalListLoad.Loading,
    val items: List<ProposalListItem> = emptyList(),
    val page: ProposalPageState = ProposalPageState.End,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
)

enum class ProposalListAction { Refresh, LoadMore }

/** 서버 기준 남은 시간 = 마감 시각 − 서버 시각(기기 시계와 무관). 못 읽으면 null, 이미 지났으면 0. */
fun remainingUntil(expiresAt: String?, serverTime: String?): Duration? {
    val end = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val server = serverTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return maxOf(end - server, Duration.ZERO)
}

/** 지금 남은 시간: 받은 뒤 흐른 시간(단조 시계)만 뺀다. 마감을 모르면 null */
fun ProposalListItem.remaining(): Duration? = closesAt?.let { maxOf(-it.elapsedNow(), Duration.ZERO) }

/** 이보다 적게 남으면 남은 시간을 빨간색으로 */
val ProposalUrgent = 3.hours

/** 남은 시간 표시: n시간(내림) · n분(최소 1분) · 곧 마감(지났거나 정각) */
sealed interface RemainingLabel {
    data class Hours(val hours: Int) : RemainingLabel
    data class Minutes(val minutes: Int) : RemainingLabel
    data object Closing : RemainingLabel
}
fun remainingLabel(left: Duration): RemainingLabel = when {
    left >= 1.hours -> RemainingLabel.Hours(left.inWholeHours.toInt())
    left > Duration.ZERO -> RemainingLabel.Minutes(maxOf(1, left.inWholeMinutes.toInt()))
    else -> RemainingLabel.Closing
}
