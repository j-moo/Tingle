package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.photo.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileRoute(session: Session.SignedIn, model: ProfileViewModel = koinViewModel(), optionalModel: OptionalProfileViewModel = koinViewModel(),
    // 기본 정보(또는 불러오는 중)에서 뒤로 가면 돌아갈 곳(가입 준비). 없으면 뒤로가기를 가로채지 않는다
    onBack: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val optional by optionalModel.state.collectAsStateWithLifecycle()
    // 사진 선택기가 떠 있는 동안 회전해도 실행기를 다시 등록할 수 있도록 편집 여부를 복원한다
    var editingPhoto by rememberSaveable(session) { mutableStateOf(false) }
    // 선택 프로필 저장이 끝나면(회전으로 화면이 바뀌어도) 최종 확인으로 한 번 넘긴다
    LaunchedEffect(session, optional.session, optional.completion, optional.busy) {
        if (optional.session == session) optionalModel.takeCompletion()?.let(model::optionalFinished)
    }
    // 사진은 기본 정보에 있으므로 들어오자마자, 그리고 사진 선택기 · 문서에서 돌아올 때마다 선택 프로필을 다시 불러온다
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { optionalModel.load() }
    if (state.session != session) return
    if (editingPhoto) {
        ProfilePhotoPreparation(onReady = { bytes -> editingPhoto = false; optionalModel.upload(bytes) },
            onClose = { editingPhoto = false }, canApply = optional.editable && optional.details.photoConsent)
        return
    }
    // 종교 동의는 가입 중 여기서 한 번 받을 수 있다: 기본 확인 창 대신 무엇에 동의하는지 적은 시트로 묻는다
    val religionAsk = optional.decision?.takeIf { it.type == OptionalConsentType.PROFILE_RELIGION && it.agreed }
    OptionalProfileDialogs(if (religionAsk != null) optional.copy(decision = null) else optional,
        optionalModel::confirmDecision, optionalModel::dismissDecision, optionalModel::deletePhoto)
    if (religionAsk != null) consentSheetText(OptionalConsentType.PROFILE_RELIGION).let { (title, lines) ->
        ConsentSheet(title, lines, optional.busy, optionalModel::confirmDecision, optionalModel::dismissDecision) {
            OptionalConsentInfo(optional, OptionalConsentType.PROFILE_RELIGION)
        }
    }
    state.basicsConfirmation?.let { basics ->
        ProfileBasicsConfirmSheet(isoBirthDate(basics.birthDateDigits),
            stringResource(if (basics.gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female),
            onConfirm = model::confirmBasics, onEdit = model::cancelBasicsConfirmation)
    }
    ProfileFlowScreen(state, model::load, model::refresh, model::nickname, model::birthDate, model::gender,
        model::saveBasics, model::smoking, model::drinking, model::saveLifestyle, model::page, model::complete, model::returnToLogin,
        optional = ProfileOptionalSlot(
            optional.details.copy(saving = optional.busy, interactionEnabled = optional.editable),
            // 선택 프로필 API 를 못 불러와도 가입은 이어 간다(빈 채로 넘김)
            canSave = if (!optional.loaded) optional.canContinue else optional.editable && optional.details.canContinue,
            consentsLoaded = optional.loaded,
            onChoosePhoto = { editingPhoto = true }, onDeletePhoto = optionalModel::requestDeletePhoto,
            onIntroduction = optionalModel::introduction, onReligion = optionalModel::religion,
            onSave = { if (!optional.loaded) optionalModel.skip() else optionalModel.save() },
            photoContent = {
                val bytes = optional.photo
                if (bytes != null) ProfilePhotoBytes(bytes) else Text(stringResource(Res.string.optional_photo_unavailable))
            },
            onRequestReligionConsent = { optionalModel.decide(OptionalConsentType.PROFILE_RELIGION, true) },
            consentInfo = { OptionalConsentInfo(optional, it) },
            canChangeConsent = { type -> optional.canDecide(type, optional.consent(type)?.agreed != true) },
            status = { OptionalProfileStatus(optional, optionalModel::load, optionalModel::retryUnresolvedConsent) },
        ), onBack = onBack)
}

@Composable
fun ProfileFlowScreen(
    state: ProfileFlowState, onLoad: () -> Unit, onRefresh: () -> Unit,
    onNickname: (String) -> Unit, onBirthDate: (String) -> Unit, onGender: (ProfileGender) -> Unit,
    onSaveBasics: () -> Unit, onSmoking: (ProfileSmoking) -> Unit, onDrinking: (ProfileDrinking) -> Unit,
    onSaveLifestyle: () -> Unit, onPage: (ProfilePage) -> Unit, onComplete: () -> Unit,
    onReturnToLogin: () -> Unit,
    optional: ProfileOptionalSlot = ProfileOptionalSlot(ProfileDetailsState(), canSave = true),
    onBack: (() -> Unit)? = null,
) {
    val entry = state.entry()
    when (entry) {
        ProfileEntry.UnderAge -> { UnderAgeScreen(state, onReturnToLogin); return }
        ProfileEntry.Saved -> {
            // 저장은 끝났고 가입 단계만 다시 확인하면 된다. 그 확인에 실패했으면 이유와 "다시 시도"를 보여 준다
            val failed = state.error != null && !state.busy
            StateScreen(if (failed) state.error.stateKind() else StateKind.Done,
                if (failed) state.error.stateTitle() else stringResource(
                    if (state.session?.status == OnboardingStatus.KEYRING_REQUIRED) Res.string.profile_saved_without_keyring else Res.string.profile_saved_title),
                primary = StateAction(stringResource(if (failed) Res.string.state_retry else Res.string.profile_state_confirm), loading = state.busy, onClick = onRefresh))
            return
        }
        ProfileEntry.Loading, ProfileEntry.Failed -> {
            // 불러오는 중 · 못 불러왔을 때는 뒤로 가서 가입 준비로 돌아갈 수 있다
            ProfileBackHandler(onBack != null) { onBack?.invoke() }
            if (entry == ProfileEntry.Loading) ProfileAccordionSkeleton(onBack)
            else StateScreen(state.error.stateKind(), state.error.stateTitle(), barTitle = stringResource(Res.string.profile_flow_title),
                onBack = onBack, onRefresh = onLoad)
            return
        }
        ProfileEntry.Form -> Unit
    }
    val validation = state.validation?.let { stringResource(when (it) {
        ProfileValidation.Nickname -> Res.string.onboarding_invalid_nickname
        ProfileValidation.BirthDate -> Res.string.onboarding_invalid_birth
        ProfileValidation.Duplicate -> Res.string.onboarding_duplicate_nickname
        ProfileValidation.Missing -> Res.string.onboarding_missing_profile
    }) }
    val basics = state.basicsForDisplay(validation)
    val hasFieldError = state.page == ProfilePage.Basics &&
        listOf(basics.nicknameError, basics.birthDateError, basics.genderError).any { it != null }
    val pageError = if (hasFieldError) null else validation ?: state.error?.message
    // 뒤로 가기 = 앞 섹션(최종 확인 → 선택 프로필 → 생활 습관 → 기본 정보 → 가입 준비). 저장 중엔 막는다(저장 결과가 버려짐)
    val back = {
        if (state.page == ProfilePage.Basics) { if (!state.busy) onBack?.invoke() }
        else if (!optional.details.saving) onPage(when (state.page) {
            ProfilePage.Confirmation -> ProfilePage.Optional; ProfilePage.Optional -> ProfilePage.Lifestyle; else -> ProfilePage.Basics })
    }
    ProfileBackHandler(state.page != ProfilePage.Basics || onBack != null, back)
    if (state.page == ProfilePage.Confirmation) {
        val p = state.saved
        if (p != null && hasRequiredSaved(p)) ProfileConfirmationScreen(
            ProfileConfirmationState(p.nickname!!, p.birthDate!!,
                ProfileGender.entries.first { it.code() == p.gender },
                ProfileSmoking.entries.first { it.code() == p.smokingStatus },
                ProfileDrinking.entries.first { it.code() == p.drinkingFrequency },
                p.religion.religion(), p.introduction.orEmpty(), p.hasProfilePhoto,
                canRegister = !state.busy, registering = state.busy),
            { onPage(ProfilePage.Basics) }, { onPage(ProfilePage.Lifestyle) }, { onPage(ProfilePage.Optional) }, onComplete,
            photoContent = { Text(stringResource(Res.string.onboarding_existing_photo)) },
            error = pageError,
        )
        return
    }
    ProfileAccordionScreen(state.page, state.saved.reachable(), onPage, basics,
        onNickname, onBirthDate, onGender, onSaveBasics,
        state.lifestyle.copy(saving = state.busy), onSmoking, onDrinking, onSaveLifestyle, optional, pageError,
        // 위쪽 "이전"은 뒤로가기와 같은 동작. 기본 정보에서 돌아갈 곳이 없으면 보이지 않는다
        onBack = back.takeIf { state.page != ProfilePage.Basics || onBack != null })
}

/** 만 19세 미만: 서버가 이미 탈퇴 처리했다. 안내 문구는 줄이지 않고 그대로 보여 준다 */
@Composable
private fun UnderAgeScreen(state: ProfileFlowState, onReturnToLogin: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                StateView(StateKind.Locked, stringResource(Res.string.profile_under_age_title))
                Text(state.underAgeMessage ?: stringResource(Res.string.profile_under_age_closed),
                    style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
            AppButton(stringResource(Res.string.profile_return_to_login), Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), loading = state.busy, onClick = onReturnToLogin)
        }
    }
}

/** 프로필 작성에 들어왔을 때 보여 줄 화면: 탈퇴 안내 · 저장 완료 · 입력 폼 · 불러오는 중(뼈대) · 불러오기 실패(상태 화면) */
internal enum class ProfileEntry { UnderAge, Saved, Form, Loading, Failed }
internal fun ProfileFlowState.entry() = when {
    underAgeAccountClosed -> ProfileEntry.UnderAge
    completed -> ProfileEntry.Saved
    loaded -> ProfileEntry.Form
    busy -> ProfileEntry.Loading
    else -> ProfileEntry.Failed
}

/** 앞 섹션이 서버에 저장돼야 뒤 섹션을 연다 */
internal fun ProfileResponse?.reachable() = when {
    this == null || nickname.isNullOrBlank() || birthDate == null || gender == null -> ProfilePage.Basics
    smokingStatus == null || drinkingFrequency == null -> ProfilePage.Lifestyle
    else -> ProfilePage.Optional
}

/** 서버가 최종 판단한 입력 오류도 해당 입력창에 표시한다. 통신 오류는 화면 공통 안내로 남긴다. */
internal fun ProfileFlowState.basicsForDisplay(validationMessage: String?): ProfileBasicsState {
    val serverError = error as? AppError.Server
    return basics.copy(
        saving = busy,
        canContinue = !busy && basics.nickname.isNotBlank() &&
            basics.birthDateDigits.length == 8 && basics.gender != null,
        nicknameError = when {
            validation == ProfileValidation.Nickname || validation == ProfileValidation.Duplicate -> validationMessage
            serverError?.code in setOf("INVALID_NICKNAME", "NICKNAME_DUPLICATED") -> serverError?.message
            else -> null
        },
        birthDateError = when {
            validation == ProfileValidation.BirthDate -> validationMessage
            serverError?.code == "INVALID_BIRTH_DATE" -> serverError.message
            else -> null
        },
        genderError = validationMessage.takeIf { validation == ProfileValidation.Missing && basics.gender == null },
    )
}
