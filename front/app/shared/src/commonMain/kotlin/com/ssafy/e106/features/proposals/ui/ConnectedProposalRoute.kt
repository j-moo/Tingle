package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.proposals.reason.MatchReasonViewModel
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 화면은 서버 데이터를 직접 변경하지 않고 사용자의 명시적 선택만 ViewModel에 전달한다. */
@Composable
fun ConnectedProposalRoute(session: Session.SignedIn, onHome: () -> Unit, onProfile: () -> Unit,
    onChats: () -> Unit, onOpenChat: (Long) -> Unit, model: ProposalViewModel = koinViewModel(),
    reasonModel: MatchReasonViewModel = koinViewModel(), initialProposalId: Long? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var openedPush by rememberSaveable(session.userId, initialProposalId) { mutableStateOf(false) }
    // 미확인 기존 결정을 먼저 복구한다. 푸시의 ID만으로 수락하거나 프로필 내용을 만들어 내지 않는다.
    LaunchedEffect(session, initialProposalId, state.busy, state.list.load) {
        if(initialProposalId != null && initialProposalId > 0 && !openedPush && state.session == session &&
            !state.busy && !state.storageIssue && state.pending == null && state.list.load == ProposalListLoad.Ready) {
            openedPush = true; model.open(initialProposalId)
        }
    }
    val reason by reasonModel.state.collectAsStateWithLifecycle()
    val context = state.review?.current?.context
    LaunchedEffect(context) { reasonModel.bind(context) }
    LaunchedEffect(reason.context, reason.accessLost) {
        if (reason.accessLost) reason.context?.let(model::revokeProfile)
    }
    DisposableEffect(reasonModel) { onDispose { reasonModel.bind(null) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    if (state.session != session) return
    fun leave(action: () -> Unit) { if (!state.busy) { model.pause(); action() } }
    ProfileBackHandler(true) {
        if (!state.busy) if (state.storageIssue || state.page == ProposalPageMode.List) leave(onHome) else model.refresh()
    }
    // 저장소 복구 전에는 목록/푸시가 새 결정을 열지 않는다. 나가기는 허용하고 재조회만 제공한다.
    if (state.storageIssue) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(AppSpacing.Medium)) {
            Text(stringResource(Res.string.proposals_storage_unavailable))
            AppButton(stringResource(Res.string.proposals_storage_retry), enabled = !state.busy, onClick = model::resume)
            TextButton(enabled = !state.busy, onClick = { leave(onHome) }) { Text(stringResource(Res.string.proposals_storage_back)) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        state.error?.let { error ->
            Text(if (error is AppError.OnboardingRequired) stringResource(Res.string.proposals_stage_required) else error.message,
                Modifier.statusBarsPadding().padding(AppSpacing.Medium), color = MaterialTheme.colorScheme.error)
        }
        if (state.pending != null && state.page != ProposalPageMode.Result)
            AppButton(stringResource(Res.string.proposals_recover_pending), enabled = !state.busy, onClick = model::recover)
        Box(Modifier.weight(1f)) {
            when (state.page) {
                ProposalPageMode.List -> ProposalListScreen(state.list, model::open, onAction = { action ->
                    when (action) {
                        ProposalListAction.Refresh -> model.refresh()
                        ProposalListAction.LoadMore -> model.more()
                        ProposalListAction.Home -> leave(onHome)
                        ProposalListAction.Profile -> leave(onProfile)
                        ProposalListAction.Chats -> leave(onChats)
                    }
                })
                ProposalPageMode.Profile -> state.review?.let { review ->
                    // 코드값을 번역 리소스로 바꾸되, 수락은 원래 서버 버전/스냅샷으로 수행한다.
                    val translated = review.latest?.let { snapshot -> snapshot.copy(profile = snapshot.profile.copy(
                        reason = if (reason.context == snapshot.context) reason.view else MatchReasonState(),
                        profile = snapshot.profile.profile?.localized())) }
                    ProposalReviewScreen(review.copy(latest = translated, confirmation = review.confirmation?.let {
                        it.copy(snapshot = translated ?: it.snapshot)
                    })) { action -> when (action) {
                        ProposalReviewAction.Reload -> model.reloadProfile()
                        ProposalReviewAction.Back -> if (!state.busy) model.refresh()
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
                    ProposalDecisionScreen(if (state.busy) result.copy(stage = ProposalDecisionStage.Checking) else result, onAction = { action ->
                        if (!state.busy) when (action) {
                            ProposalDecisionAction.CheckResult -> model.checkResult()
                            ProposalDecisionAction.ProposalList -> model.refresh()
                            ProposalDecisionAction.ReviewProfile -> model.reloadProfile()
                            ProposalDecisionAction.OpenChat -> state.chatRoomId?.let { leave { onOpenChat(it) } }
                        }
                    })
                }
            }
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
