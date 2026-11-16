package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.account.ui.HelpGuideAction
import com.ssafy.e106.features.account.ui.HelpGuideKind
import com.ssafy.e106.features.account.ui.HelpGuideScreen
import com.ssafy.e106.features.account.ui.HelpGuideState
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private enum class ProfileDestination { Overview, Edit, Optional, Correction }

@Composable
fun ProfileManagementRoute(session: Session.SignedIn, onExit: () -> Unit, model: ProfileEditViewModel = koinViewModel(),
    // 수정 화면의 종교 · 소개 시트와 📷 선택 정보 화면이 같은 인스턴스를 쓴다
    optionalModel: OptionalProfileViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(session) { mutableStateOf(ProfileDestination.Overview) }
    var unknownExitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    // 종교 · 소개 저장 결과를 모르는 채 수정 화면을 나가려 할 때. 나가도 보기 화면으로만 간다
    var optionalExitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    // 구성 변경에서는 저장을 유지하고, 재생성된 화면이 저장 완료 상태를 소비해 이동한다.
    LaunchedEffect(session, state.session, state.saveCompleted, state.busy) {
        if (state.session == session && model.takeSaveCompletion()) { optionalModel.leave(); page = ProfileDestination.Overview }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (page != ProfileDestination.Optional) model.load()
        if (page == ProfileDestination.Edit) optionalModel.load()
    }
    if (state.session != session) return
    // 수정 화면을 떠날 때만 선택 정보 요청을 끊는다(📷 선택 정보 화면은 같은 모델을 이어 쓴다)
    fun overview() { optionalModel.leave(); page = ProfileDestination.Overview; model.load() }
    fun edit() { page = ProfileDestination.Edit; optionalModel.load() }
    fun returnFromOptional() { edit(); model.load() }
    fun exit() { model.leave(); optionalModel.leave(); onExit() }
    fun back() {
        if (state.busy) return
        if (state.recoveryRequired) { unknownExitPrompt = true; return }
        // 수정 화면의 선택 정보 요청은 끊는다: 읽기는 그냥 취소되고, 쓰기는 결과 미확인이 되어 나가기 전에 묻는다
        if (page == ProfileDestination.Edit) {
            if (optionalModel.state.value.busy) optionalModel.leave()
            if (optionalModel.state.value.recoveryRequired) { optionalExitPrompt = true; return }
        }
        when (page) {
            ProfileDestination.Edit -> model.requestBack(::overview)
            ProfileDestination.Correction -> page = ProfileDestination.Edit
            else -> exit()
        }
    }
    ProfileBackHandler(page != ProfileDestination.Optional) {
        back()
    }
    when (page) {
        ProfileDestination.Optional -> OptionalProfileRoute(session, { returnFromOptional() }, ::returnFromOptional, model = optionalModel, editingExisting = true)
        ProfileDestination.Correction -> HelpGuideScreen(HelpGuideState(HelpGuideKind.Correction), onAction = {
            if (it == HelpGuideAction.Back) page = ProfileDestination.Edit
        })
        ProfileDestination.Edit -> ProfileEditPage(session, state, model, optionalModel,
            onBack = ::back, onDiscard = { model.discard(::overview) },
            onOptional = { if (state.canEdit) page = ProfileDestination.Optional },
            onCorrection = { if (state.canEdit) page = ProfileDestination.Correction })
        ProfileDestination.Overview -> ProfileOverviewScreen(state, devNotice = !session.isReady,
            onEdit = ::edit, onReload = model::load, onBack = ::back)
    }
    // 오프라인에서도 사용자를 가두지 않는다. 나가더라도 서버 반영 여부는 미확인으로 남긴다.
    if (unknownExitPrompt) ProfileUnknownExitDialog(
        onDismiss = { unknownExitPrompt = false },
        onExit = { unknownExitPrompt = false; exit() })
    // 선택 정보 쪽 확인은 고치던 닉네임 · 흡연 · 음주를 지키는 기존 "나갈까요?"를 거쳐 보기 화면으로 간다
    // 기본 정보를 다시 불러오는 중(busy)이면 requestBack 이 무시되므로, 끝날 때까지 기다렸다 나간다
    var optionalExitPending by rememberSaveable(session) { mutableStateOf(false) }
    LaunchedEffect(optionalExitPending, state.busy) {
        if (optionalExitPending && !state.busy) { optionalExitPending = false; model.requestBack(::overview) }
    }
    if (optionalExitPrompt) ProfileUnknownExitDialog(
        // 계속 고치면 끊긴 선택 정보를 다시 확인해 칩을 바로 쓸 수 있게 한다
        onDismiss = { optionalExitPrompt = false; optionalModel.load() },
        onExit = { optionalExitPrompt = false; optionalExitPending = true })
}

/**
 * 내 프로필: 불러오는 중은 뼈대, 처음 불러오기 실패는 상태 화면, 보던 중 새로고침 실패는 아래 띠.
 * 위쪽은 사진이 상태 바까지 꽉 찬 [ProfileHero] 이고, 수정은 그 오른쪽 위 ✏️로 간다. 다시 불러오기는 당겨서 새로고침으로 한다.
 */
@Composable
private fun ProfileOverviewScreen(state: ProfileManagementState, devNotice: Boolean, onEdit: () -> Unit, onReload: () -> Unit, onBack: () -> Unit) {
    val title = stringResource(Res.string.profile_manage_title)
    val profile = state.profile
    if (profile == null) {
        if (state.loadFailed) ProfileLoadFailed(state, title, onBack, onReload)
        else ProfileSkeletonFrame(title, null) {
            SkeletonGroup {
                // 실제 화면 순서(큰 사진 · 생활 칩 · 소개 카드)대로
                SkeletonBone(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f), shape = AppShapes.Card)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) { repeat(3) { SkeletonBone(width = 88.dp, height = 30.dp, shape = CircleShape) } }
                SkeletonBone(height = 96.dp, shape = AppShapes.Card)
            }
        }
        return
    }
    val (refreshing, refresh) = pullRefresh(state.busy, onReload)
    val (photo, image) = rememberHeroPhoto(state.photo, profile.hasProfilePhoto, state.photoFailed)
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        // 사진은 상태 바 밑까지 깔고, 나머지는 가로 · 아래 안전 영역만 피한다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            PullDownRefreshBox(refreshing, refresh, Modifier.weight(1f).fillMaxWidth(), indicatorInsets = WindowInsets.statusBars) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileHero(profile.heroInfo(), photo, image, onBack.takeIf { !state.busy }, title,
                        onRetryPhoto = onReload.takeIf { !state.busy },
                        // 수정할 수 없는 상태(결과 미확인 등)면 숨기고, 불러오는 동안은 깜빡이지 않게 끄기만 한다
                        action = { if (state.editor != null && !state.recoveryRequired) HeroRoundButton(Res.drawable.ic_pencil,
                            stringResource(Res.string.profile_edit_title), onEdit, enabled = state.canEdit) })
                    Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth().padding(screenPaddingValues()),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                        if (devNotice) Text(stringResource(Res.string.profile_manage_dev_notice), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        ProfileManagementNotice(state, onReload)
                        ProfileLifestyleChips(profile.lifestyle())
                        ProfileIntroCard(profile.introduction)
                    }
                }
            }
            // 보던 내용은 그대로 두고 새로고침 실패만 아래 띠로 알린다(결과 미확인은 위 안내가 따로 맡는다)
            if (state.error != null && !state.unknown) NoticeBar(state.error.stateTitle(), actionEnabled = !state.busy, onAction = onReload)
        }
    }
}

/** Same pure editor as the Preview, with optional operations delegated to their consent flow. */
@Composable
fun ProfileConnectedEditScreen(
    state: ProfileManagementState, onChange: (ProfileEditDraft) -> Unit, onSave: () -> Unit,
    onBack: () -> Unit, onDiscard: () -> Unit, onKeep: () -> Unit,
    onOptional: () -> Unit, onCorrection: () -> Unit, onReload: () -> Unit,
    religionChip: @Composable () -> Unit = {}, belowChips: @Composable () -> Unit = {}, introduction: @Composable () -> Unit = {},
) {
    val editor = state.editor
    if (editor == null) {
        val title = stringResource(Res.string.profile_edit_title)
        if (state.loadFailed) ProfileLoadFailed(state, title, onBack, onReload)
        else ProfileSkeletonFrame(title, stringResource(Res.string.profile_edit_save)) {
            SkeletonGroup(spacing = AppSpacing.Section) {
                BoneField(Res.string.profile_photo_title) { PhotoBone() }
                BoneField(Res.string.profile_nickname)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) { repeat(3) { SkeletonBone(width = 88.dp, height = 30.dp, shape = CircleShape) } }
            }
        }
        return
    }
    // 저장 실패(결과 미확인 제외)는 저장 버튼 바로 위 한 줄로 알린다. 입력을 고치면 ViewModel 이 오류를 지운다
    val saveError = if (state.unknown || state.busy) null else when (state.problem) {
        ProfileEditProblem.DuplicateNickname -> stringResource(Res.string.onboarding_duplicate_nickname)
        ProfileEditProblem.InvalidNickname -> stringResource(Res.string.profile_edit_nickname_invalid)
        ProfileEditProblem.InvalidProfile -> stringResource(Res.string.profile_manage_invalid)
        else -> state.error?.message
    }
    val (refreshing, refresh) = pullRefresh(state.busy, onReload)
    val (photo, image) = rememberHeroPhoto(state.photo, state.profile?.hasProfilePhoto == true, state.photoFailed)
    ProfileEditScreen(editor.copy(saving = state.busy, interactionEnabled = !state.recoveryRequired, saveError = saveError),
        onChange, onSave, onBack, onDiscard, onKeep, onOptional, onCorrection,
        heroPhoto = photo, heroImage = image, onRetryPhoto = onReload, religionChip = religionChip, belowChips = belowChips, introduction = introduction,
        header = { ProfileManagementNotice(state, onReload) },
        refreshing = refreshing, onRefresh = refresh)
}

/** 프로필이 없는데 불러오기도 끝났다 = 못 불러왔다(오류) 또는 수정할 프로필이 아니다(입력 미완료) */
private val ProfileManagementState.loadFailed get() = !busy && (error != null || problem != null)
/** 저장 결과를 확인하지 못한 상태. 다시 저장하지 않고 먼저 서버 상태를 확인하게 한다 */
private val ProfileManagementState.unknown get() = recoveryRequired || problem == ProfileEditProblem.ResultUnknown

@Composable private fun ProfileLoadFailed(state: ProfileManagementState, title: String, onBack: () -> Unit, onReload: () -> Unit) {
    val invalid = state.problem == ProfileEditProblem.InvalidProfile
    StateScreen(if (invalid) StateKind.Locked else state.error.stateKind(),
        if (invalid) stringResource(Res.string.profile_manage_invalid) else state.error.stateTitle(),
        barTitle = title, onBack = onBack, refreshing = state.busy, onRefresh = onReload)
}

/** 저장했어요 · 결과 미확인 안내. 결과 미확인 문구는 같은 내용을 두 번 저장하지 않게 하는 설명이라 줄이지 않는다 */
@Composable private fun ProfileManagementNotice(state: ProfileManagementState, onReload: () -> Unit) {
    if (state.savedNotice) Text(stringResource(Res.string.profile_manage_saved), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
    if (state.unknown) {
        Text(stringResource(Res.string.profile_manage_unknown), style = MaterialTheme.typography.bodyMedium, color = AppColors.Error)
        TextAction(stringResource(Res.string.profile_state_recheck), onReload, enabled = !state.busy)
    }
}

/** 오늘 날짜(한국 시간) 연 · 월 · 일. */
internal fun todayKst(nowMillis: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): Triple<Int, Int, Int> {
    // 1970-01-01부터 지난 날수 → 그레고리력 날짜(Howard Hinnant의 civil_from_days)
    val days = (nowMillis + 9 * 3_600_000L).floorDiv(86_400_000L) + 719_468
    val era = days.floorDiv(146_097L)
    val doe = days - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((yoe + era * 400 + if (month <= 2) 1 else 0).toInt(), month, day)
}

/** 만 나이. 생년월일("yyyy-MM-dd")을 읽지 못하면 null. */
internal fun internationalAge(birthDate: String, today: Triple<Int, Int, Int>): Int? {
    val parts = birthDate.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    val (y, m, d) = parts
    val (ty, tm, td) = today
    return (ty - y - if (tm < m || (tm == m && td < d)) 1 else 0).takeIf { it >= 0 }
}
