package com.ssafy.e106.features.proposals.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.push.PushEvents
import com.ssafy.e106.core.push.PushTarget
import com.ssafy.e106.core.ui.components.NoticeBar
import com.ssafy.e106.core.ui.components.StateAction
import com.ssafy.e106.core.ui.components.StateKind
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.proposals.reason.MatchReasonViewModel
import com.ssafy.e106.features.safety.ui.StateNoteScreen
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 화면은 서버 데이터를 직접 변경하지 않고 사용자의 명시적 선택만 ViewModel에 전달한다.
 * [onTabBar] 로 틀(하단 바)에 (목록 루트인지, 바를 눌러도 되는지)를 알린다. 수락 · 거절을 보내는 중에는 바로 이동하지 못한다
 */
@Composable
fun ConnectedProposalRoute(session: Session.SignedIn, onHome: () -> Unit, onOpenChat: (Long) -> Unit,
    model: ProposalViewModel = koinViewModel(), reasonModel: MatchReasonViewModel = koinViewModel(), pushes: PushEvents? = null,
    onTabBar: (root: Boolean, enabled: Boolean) -> Unit = { _, _ -> }) {
    val state by model.state.collectAsStateWithLifecycle()
    // 목록(저장소 문제 · 채팅으로 넘어가는 순간 제외)일 때만 하단 바를 보인다
    val root = state.session != session || !state.storageIssue && state.page == ProposalPageMode.List && state.openChat == null
    // 조회(자동 · 수동)는 이동을 막지 않는다. 이탈 시 pause/refresh 가 취소와 응답 무효화를 맡고, 수락 · 거절을 보내는 중에만 막는다
    val navigationBlocked = state.writing
    SideEffect { onTabBar(root, !navigationBlocked) }
    // 목록 탭(응답 대기 | 기다리는 중): 프로필에 갔다 와도 그대로
    var listTab by rememberSaveable(session.userId) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 전경에서 받은 푸시는 열린 목록만 갱신한다. 상세나 수락·거절 중이면 일반 재조회 흐름에 맡긴다.
    LaunchedEffect(pushes, lifecycle, session, model) {
        if (pushes == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            pushes.proposalArrivals.collect { userId ->
                if (userId == session.userId) model.refreshAfterProposalPush(session)
            }
        }
    }
    if (pushes != null) {
        val tap by pushes.pending.collectAsStateWithLifecycle()
        LaunchedEffect(tap, state.page, state.session, state.busy, state.pending, state.storageIssue) {
            // 이미 제안 목록을 보는 상태에서 알림을 누르면 다른 화면을 열지 않고 목록을 다시 읽는다.
            val target = tap as? PushTarget.Proposal ?: return@LaunchedEffect
            if (model.refreshAfterProposalPush(session)) pushes.consume(target)
        }
    }
    val reason by reasonModel.state.collectAsStateWithLifecycle()
    val context = state.review?.current?.context
    LaunchedEffect(context) { reasonModel.bind(context) }
    LaunchedEffect(reason.context, reason.accessLost) {
        if (reason.accessLost) reason.context?.let(model::revokeProfile)
    }
    DisposableEffect(reasonModel) { onDispose { reasonModel.bind(null) } }
    // NavHost 이동 · 하단 바 이동으로 컴포지션만 제거되어도 자동 조회를 멈춘다. ViewModel 정리와 화면 이탈은 다르다.
    DisposableEffect(model) { onDispose { model.pause() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    if (state.session != session) return
    fun leave(action: () -> Unit) { if (!navigationBlocked) { model.pause(); action() } }
    // 서로 수락 · 채팅방 확인되면 결과 화면 없이 바로 채팅
    LaunchedEffect(state.openChat, navigationBlocked) {
        val room = state.openChat
        if (room != null && !navigationBlocked) { model.chatOpened(); leave { onOpenChat(room) } }
    }
    ProfileBackHandler(true) {
        if (!navigationBlocked) if (state.storageIssue || state.page == ProposalPageMode.List) leave(onHome) else model.refresh()
    }
    // 저장소 복구 전에는 목록/푸시가 새 결정을 열지 않는다. 나가기는 허용하고 재조회만 제공한다.
    if (state.storageIssue) {
        StateNoteScreen(StateKind.Unknown, stringResource(Res.string.proposals_storage_unavailable), stringResource(Res.string.proposals_storage_note),
            primary = StateAction(stringResource(Res.string.state_retry), enabled = !state.busy, onClick = model::resume),
            secondary = StateAction(stringResource(Res.string.proposals_storage_back), enabled = !state.busy) { leave(onHome) })
        return
    }
    // 오류는 화면 위 빨간 글 대신 각 화면의 상태 화면 · 아래 띠가 알린다
    when (state.page) {
        // 채팅으로 넘어가는 순간엔 비어 있는 목록을 그리지 않는다
        ProposalPageMode.List -> if (state.openChat == null) ProposalListScreen(state.list, model::open, onAction = { action ->
            when (action) {
                ProposalListAction.Refresh -> model.refresh()
                ProposalListAction.LoadMore -> model.more()
            }
        }, tab = listTab, onTab = { listTab = it }, error = state.error, notice = {
            // 이전에 보낸 선택의 결과를 모르면 새 제안을 열지 않는다. 결과 확인은 아래 띠에서 한다
            if (state.pending != null) NoticeBar(stringResource(Res.string.proposals_recover_pending),
                actionText = stringResource(Res.string.proposal_decision_check), actionEnabled = !state.busy, onAction = model::recover)
        })
        ProposalPageMode.Profile -> state.review?.let { review ->
            // 코드값을 번역 리소스로 바꾸되, 수락은 원래 서버 버전/스냅샷으로 수행한다.
            val translated = review.latest?.let { snapshot -> snapshot.copy(profile = snapshot.profile.copy(
                reason = if (reason.context == snapshot.context) reason.view else MatchReasonState(),
                profile = snapshot.profile.profile?.localized())) }
            ProposalReviewScreen(review.copy(latest = translated, confirmation = review.confirmation?.let {
                it.copy(snapshot = translated ?: it.snapshot)
            }), canLeave = !navigationBlocked, error = state.error) { action -> when (action) {
                ProposalReviewAction.Reload -> model.reloadProfile()
                ProposalReviewAction.Back -> if (!navigationBlocked) model.refresh()
                ProposalReviewAction.CheckResult -> model.checkResult()
                ProposalReviewAction.Dismiss -> model.dismiss()
                is ProposalReviewAction.Ask -> model.ask(action.confirmation.choice, action.confirmation.snapshot.context)
                is ProposalReviewAction.Submit -> model.submit(action.confirmation.snapshot.context)
                is ProposalReviewAction.ProfileAction -> when (action.action) {
                    ProposalProfileAction.RetryPhoto, ProposalProfileAction.Reload -> model.reloadProfile()
                    ProposalProfileAction.CheckReason -> reasonModel.check()
                    ProposalProfileAction.RetryReason -> reasonModel.check(retry = true)
                    else -> Unit
                }
            } }
        }
        ProposalPageMode.Result -> state.result?.let { result ->
            // 자동 갱신 중에도 목록 복귀는 허용한다. 결과 재조회와 결정 관련 동작만 중복 요청을 막는다.
            ProposalDecisionScreen(if (state.busy && !state.refreshingResult) result.copy(stage = ProposalDecisionStage.Checking) else result, onAction = { action ->
                when (action) {
                    ProposalDecisionAction.CheckResult -> if (!state.busy) model.checkResult()
                    ProposalDecisionAction.ProposalList -> if (!navigationBlocked) model.refresh()
                }
            }, canLeave = !navigationBlocked)
        }
    }
}

@Composable private fun PublicProposalProfile.localized() = copy(
    genderLabel = label(genderLabel), smokingLabel = label(smokingLabel), drinkingLabel = label(drinkingLabel),
    religionLabel = label(religionLabel, allowText = true))

@Composable private fun label(code: String?, allowText: Boolean = false): String? {
    val resource: StringResource? = when (code) {
        "MALE" -> Res.string.profile_male
        "FEMALE" -> Res.string.profile_female
        "NON_SMOKER" -> Res.string.profile_smoking_none
        "E_CIGARETTE" -> Res.string.profile_smoking_electronic
        "ONLY_WHEN_DRINKING" -> Res.string.profile_smoking_occasional
        "SMOKER" -> Res.string.profile_smoking_yes
        "NEVER" -> Res.string.profile_drinking_never
        "SPECIAL_OCCASIONS_ONLY" -> Res.string.profile_drinking_special
        "MONTHLY_AT_MOST_ONCE" -> Res.string.profile_drinking_monthly_less
        "MONTHLY_TWO_TO_FOUR" -> Res.string.profile_drinking_monthly
        "WEEKLY_TWO_TO_THREE" -> Res.string.profile_drinking_weekly
        "WEEKLY_FOUR_OR_MORE" -> Res.string.profile_drinking_often
        "NONE" -> Res.string.profile_religion_none
        "PROTESTANT" -> Res.string.profile_religion_protestant
        "CATHOLIC" -> Res.string.profile_religion_catholic
        "BUDDHIST" -> Res.string.profile_religion_buddhist
        "OTHER" -> Res.string.profile_religion_other
        "UNDISCLOSED" -> Res.string.profile_religion_private
        else -> null
    }
    return resource?.let { stringResource(it) } ?: code?.takeIf { allowText }
}
