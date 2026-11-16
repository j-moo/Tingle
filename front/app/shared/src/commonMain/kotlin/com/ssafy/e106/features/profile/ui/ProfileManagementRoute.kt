package com.ssafy.e106.features.profile.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.ssafy.e106.features.profile.photo.ProfilePhotoBytes
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private enum class ProfileDestination { Overview, Edit, Optional, Correction }

@Composable
fun ProfileManagementRoute(session: Session.SignedIn, onExit: () -> Unit, model: ProfileEditViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(session) { mutableStateOf(ProfileDestination.Overview) }
    var unknownExitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    // 구성 변경에서는 저장을 유지하고, 재생성된 화면이 저장 완료 상태를 소비해 이동한다.
    LaunchedEffect(session, state.session, state.saveCompleted, state.busy) {
        if (state.session == session && model.takeSaveCompletion()) page = ProfileDestination.Overview
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (page != ProfileDestination.Optional) model.load() }
    if (state.session != session) return
    fun overview() { page = ProfileDestination.Overview }
    fun returnFromOptional() { page = ProfileDestination.Edit; model.load() }
    fun exit() { model.leave(); onExit() }
    fun back() {
        if (state.busy) return
        if (state.recoveryRequired) { unknownExitPrompt = true; return }
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
        ProfileDestination.Optional -> OptionalProfileRoute(session, { returnFromOptional() }, ::returnFromOptional, editingExisting = true)
        ProfileDestination.Correction -> HelpGuideScreen(HelpGuideState(HelpGuideKind.Correction), onAction = {
            if (it == HelpGuideAction.Back) page = ProfileDestination.Edit
        })
        ProfileDestination.Edit -> ProfileConnectedEditScreen(state,
            onChange = model::change, onSave = { model.save() },
            onBack = ::back, onDiscard = { model.discard(::overview) }, onKeep = model::keepEditing,
            onOptional = { if (state.canEdit) page = ProfileDestination.Optional },
            onCorrection = { if (state.canEdit) page = ProfileDestination.Correction }, onReload = model::load)
        ProfileDestination.Overview -> ProfileOverviewScreen(state, devNotice = !session.isReady,
            onEdit = { page = ProfileDestination.Edit }, onReload = model::load, onBack = ::back)
    }
    // 오프라인에서도 사용자를 가두지 않는다. 나가더라도 서버 반영 여부는 미확인으로 남긴다.
    if (unknownExitPrompt) ProfileUnknownExitDialog(
        onDismiss = { unknownExitPrompt = false },
        onExit = { unknownExitPrompt = false; exit() })
}

/**
 * 내 프로필: 불러오는 중은 뼈대, 처음 불러오기 실패는 상태 화면, 보던 중 새로고침 실패는 아래 띠.
 * 다시 불러오기는 당겨서 새로고침으로 하고, 아래 "프로필 수정"은 화면 바닥에 붙여 둔다.
 */
@Composable
private fun ProfileOverviewScreen(state: ProfileManagementState, devNotice: Boolean, onEdit: () -> Unit, onReload: () -> Unit, onBack: () -> Unit) {
    val title = stringResource(Res.string.profile_manage_title)
    val profile = state.profile
    if (profile == null) {
        if (state.loadFailed) ProfileLoadFailed(state, title, onBack, onReload)
        else ProfileSkeletonFrame(title, stringResource(Res.string.profile_edit_title)) {
            SkeletonGroup {
                // 실제 화면 순서(큰 사진 · 닉네임 · 나이 성별 · 자기소개 · 종교)대로
                SkeletonBone(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f), shape = AppShapes.Card)
                SkeletonBone(width = 160.dp, height = 28.dp)
                SkeletonBone(width = 120.dp)
                BoneField(Res.string.profile_view_intro) { SkeletonBone(Modifier.fillMaxWidth(0.7f)) }
                BoneField(Res.string.profile_view_religion) { SkeletonBone(Modifier.fillMaxWidth(0.4f)) }
            }
        }
        return
    }
    val (refreshing, refresh) = pullRefresh(state.busy, onReload)
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = onBack.takeIf { !state.busy })
            PullDownRefreshBox(refreshing, refresh, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    if (devNotice) Text(stringResource(Res.string.profile_manage_dev_notice), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    ProfileManagementNotice(state, onReload)
                    // 사진은 마이가 아니라 여기서 크게 보여 준다. 없으면 기본 사람 모양, 받는 중이면 회색 칸
                    Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(AppShapes.Card).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
                        when {
                            state.photo != null -> ProfilePhotoBytes(state.photo)
                            !profile.hasProfilePhoto || state.photoFailed -> Canvas(Modifier.fillMaxSize()) {
                                val w = size.width
                                drawCircle(PhotoSilhouette, radius = w * 0.17f, center = Offset(w / 2, w * 0.38f))
                                drawOval(PhotoSilhouette, topLeft = Offset(w * 0.16f, w * 0.62f), size = Size(w * 0.68f, w * 0.8f))
                            }
                        }
                    }
                    Text(profile.nickname.orEmpty(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    // 상대에게 보이는 것처럼 나이(만) · 성별을 닉네임 아래에. 모르는 값은 빼고 보여 준다
                    val age = profile.birthDate?.let { internationalAge(it, todayKst()) }?.let { stringResource(Res.string.profile_view_age, it) }
                    val gender = when (profile.gender) { "MALE" -> stringResource(Res.string.profile_male); "FEMALE" -> stringResource(Res.string.profile_female); else -> null }
                    listOfNotNull(age, gender).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
                    }
                    OptionalProfileSummary(state)
                }
            }
            // 보던 내용은 그대로 두고 새로고침 실패만 아래 띠로 알린다(결과 미확인은 위 안내가 따로 맡는다)
            if (state.error != null && !state.unknown) NoticeBar(state.error.stateTitle(), actionEnabled = !state.busy, onAction = onReload)
            AppButton(stringResource(Res.string.profile_edit_title), Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), enabled = state.canEdit, onClick = onEdit)
        }
    }
}

/** Same pure editor as the Preview, with optional operations delegated to their consent flow. */
@Composable
fun ProfileConnectedEditScreen(
    state: ProfileManagementState, onChange: (ProfileEditDraft) -> Unit, onSave: () -> Unit,
    onBack: () -> Unit, onDiscard: () -> Unit, onKeep: () -> Unit,
    onOptional: () -> Unit, onCorrection: () -> Unit, onReload: () -> Unit,
) {
    val editor = state.editor
    if (editor == null) {
        val title = stringResource(Res.string.profile_edit_title)
        if (state.loadFailed) ProfileLoadFailed(state, title, onBack, onReload)
        else ProfileSkeletonFrame(title, stringResource(Res.string.profile_edit_save)) {
            SkeletonGroup(spacing = AppSpacing.Section) {
                BoneField(Res.string.profile_photo_title) { PhotoBone() }
                BoneField(Res.string.profile_nickname)
                BoneField(Res.string.profile_edit_smoking)
                BoneField(Res.string.profile_edit_drinking)
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
    ProfileEditScreen(editor.copy(saving = state.busy, interactionEnabled = !state.recoveryRequired, saveError = saveError),
        onChange, onSave, onBack, onDiscard, onKeep, onOptional, onCorrection,
        photoContent = { state.photo?.let { ProfilePhotoBytes(it) } ?: Text(stringResource(Res.string.optional_photo_unavailable)) },
        optionalContent = {
            OptionalProfileSummary(state, viewing = false)
            Text(stringResource(Res.string.profile_manage_optional_help), style = MaterialTheme.typography.bodySmall)
        },
        photoActionLabel = stringResource(Res.string.profile_manage_optional),
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

@Composable private fun OptionalProfileSummary(state: ProfileManagementState, viewing: Boolean = true) {
    val p = state.profile ?: return
    val intro = p.introduction?.takeIf { it.isNotBlank() }
    val religion = p.religion.religion()?.let { stringResource(it.label()) }
    if (!viewing) {
        // 수정 화면은 입력 항목답게 "선택" 표시를 그대로 둔다
        TagLabel(stringResource(Res.string.profile_intro_title), style = MaterialTheme.typography.titleMedium)
        Text(intro ?: stringResource(Res.string.profile_manage_none))
        TagLabel(stringResource(Res.string.profile_religion_title), style = MaterialTheme.typography.titleMedium)
        Text(religion ?: stringResource(Res.string.profile_manage_none))
        return
    }
    // 보기 화면이라 입력 화면의 "선택" 표시는 빼고, 입력한 값은 상자에 담아 제목과 구분한다
    SummaryField(Res.string.profile_view_intro, intro)
    SummaryField(Res.string.profile_view_religion, religion)
}

@Composable private fun SummaryField(title: StringResource, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(value ?: stringResource(Res.string.profile_manage_none), Modifier.fillMaxWidth().clip(AppShapes.Control)
            .background(AppColors.SurfaceMuted).padding(AppSpacing.Medium),
            style = MaterialTheme.typography.bodyLarge, color = if (value == null) AppColors.TextSecondary else AppColors.TextPrimary)
    }
}

/** 저장했어요 · 결과 미확인 안내. 결과 미확인 문구는 같은 내용을 두 번 저장하지 않게 하는 설명이라 줄이지 않는다 */
@Composable private fun ProfileManagementNotice(state: ProfileManagementState, onReload: () -> Unit) {
    if (state.savedNotice) Text(stringResource(Res.string.profile_manage_saved), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
    if (state.unknown) {
        Text(stringResource(Res.string.profile_manage_unknown), style = MaterialTheme.typography.bodyMedium, color = AppColors.Error)
        TextAction(stringResource(Res.string.profile_state_recheck), onReload, enabled = !state.busy)
    }
}

/** 사진이 없을 때 기본 사람 모양 색(회색 바탕 위에서 보이는 정도). */
private val PhotoSilhouette = androidx.compose.ui.graphics.Color(0xFFD4D4D4)

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
