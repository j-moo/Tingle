package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.onboarding.ui.*
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.photo.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileRoute(session: Session.SignedIn, model: ProfileViewModel = koinViewModel(), optionalModel: OptionalProfileViewModel = koinViewModel()) {
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
    OptionalProfileDialogs(optional, optionalModel::confirmDecision, optionalModel::dismissDecision, optionalModel::deletePhoto)
    state.basicsConfirmation?.let { basics ->
        AlertDialog(
            onDismissRequest = model::cancelBasicsConfirmation,
            title = { Text(stringResource(Res.string.profile_confirm_basics_title)) },
            text = { Text(stringResource(Res.string.profile_confirm_basics_body,
                isoBirthDate(basics.birthDateDigits), stringResource(
                    if (basics.gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female))) },
            confirmButton = { TextButton(onClick = model::confirmBasics) {
                Text(stringResource(Res.string.profile_confirm_basics_save))
            } },
            dismissButton = { TextButton(onClick = model::cancelBasicsConfirmation) {
                Text(stringResource(Res.string.profile_confirm_basics_edit))
            } },
        )
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
            onReligionConsent = { optionalModel.decide(OptionalConsentType.PROFILE_RELIGION, it) },
            onPhotoConsent = { optionalModel.decide(OptionalConsentType.PROFILE_PHOTO, it) },
            onIntroductionConsent = { optionalModel.decide(OptionalConsentType.PROFILE_INTRODUCTION, it) },
            onSave = { if (!optional.loaded) optionalModel.skip() else optionalModel.save() },
            photoContent = {
                val bytes = optional.photo
                if (bytes != null) ProfilePhotoBytes(bytes) else Text(stringResource(Res.string.optional_photo_unavailable))
            },
            consentInfo = { OptionalConsentInfo(optional, it) },
            canChangeConsent = { type -> optional.canDecide(type, optional.consent(type)?.agreed != true) },
            status = { OptionalProfileStatus(optional, optionalModel::load, optionalModel::retryUnresolvedConsent) },
        ))
}

@Composable
fun ProfileFlowScreen(
    state: ProfileFlowState, onLoad: () -> Unit, onRefresh: () -> Unit,
    onNickname: (String) -> Unit, onBirthDate: (String) -> Unit, onGender: (ProfileGender) -> Unit,
    onSaveBasics: () -> Unit, onSmoking: (ProfileSmoking) -> Unit, onDrinking: (ProfileDrinking) -> Unit,
    onSaveLifestyle: () -> Unit, onPage: (ProfilePage) -> Unit, onComplete: () -> Unit,
    onReturnToLogin: () -> Unit,
    optional: ProfileOptionalSlot = ProfileOptionalSlot(ProfileDetailsState(), canSave = true),
) {
    if (state.underAgeAccountClosed) {
        OnboardingPanel(stringResource(Res.string.profile_under_age_title)) {
            Text(state.underAgeMessage ?: stringResource(Res.string.profile_under_age_closed))
            AppButton(stringResource(Res.string.profile_return_to_login),
                loading = state.busy, enabled = !state.busy, onClick = onReturnToLogin)
        }
        return
    }
    if (!state.loaded || state.completed) {
        OnboardingPanel(stringResource(Res.string.profile_basics_title)) {
            Text(stringResource(when {
                state.completed && state.session?.status == OnboardingStatus.KEYRING_REQUIRED -> Res.string.profile_saved_without_keyring
                state.completed -> Res.string.onboarding_saved
                else -> Res.string.onboarding_loading
            }))
            state.error?.let { OnboardingError(it.message) }
            AppButton(stringResource(if (state.completed) Res.string.onboarding_refresh else Res.string.onboarding_retry),
                loading = state.busy, enabled = !state.busy, onClick = if (state.completed) onRefresh else onLoad)
        }
        return
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
    // 뒤로 가기 = 앞 섹션(최종 확인 → 선택 프로필 → 생활 습관 → 기본 정보). 기본 정보에서는 가로채지 않고, 선택 프로필 저장 중엔 막는다(저장 결과가 버려짐)
    ProfileBackHandler(state.page != ProfilePage.Basics) {
        if (optional.details.saving) return@ProfileBackHandler
        onPage(when (state.page) { ProfilePage.Confirmation -> ProfilePage.Optional; ProfilePage.Optional -> ProfilePage.Lifestyle; else -> ProfilePage.Basics })
    }
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
        state.lifestyle.copy(saving = state.busy), onSmoking, onDrinking, onSaveLifestyle, optional, pageError)
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
