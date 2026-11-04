package com.ssafy.e106.features.proposals.ui

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
    // 서버 기준 기한을 호출부가 지역화한 문구. Preview의 시각은 예시이며 자체 타이머를 돌리지 않는다.
    val deadlineLabel: String? = null,
    val photoState: ProposalPhotoState = ProposalPhotoState.NotProvided,
    val photoRequest: ProposalPhotoRequest? = null,
    val photoImage: ProposalPhotoImage? = null,
)

data class ProposalListState(
    val load: ProposalListLoad = ProposalListLoad.Loading,
    val items: List<ProposalListItem> = emptyList(),
    val page: ProposalPageState = ProposalPageState.End,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
)

enum class ProposalListAction { Refresh, LoadMore, Home, Chats, Profile }
