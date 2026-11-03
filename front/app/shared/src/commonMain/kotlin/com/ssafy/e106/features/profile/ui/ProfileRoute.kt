package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.onboarding.ui.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileRoute(session: Session.SignedIn, model: ProfileViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.session != session) return
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
        optionalContent = { OptionalProfileRoute(session, model::optionalFinished, { model.page(ProfilePage.Lifestyle) }) })
}

@Composable
fun ProfileFlowScreen(
    state: ProfileFlowState, onLoad: () -> Unit, onRefresh: () -> Unit,
    onNickname: (String) -> Unit, onBirthDate: (String) -> Unit, onGender: (ProfileGender) -> Unit,
    onSaveBasics: () -> Unit, onSmoking: (ProfileSmoking) -> Unit, onDrinking: (ProfileDrinking) -> Unit,
    onSaveLifestyle: () -> Unit, onPage: (ProfilePage) -> Unit, onComplete: () -> Unit,
    onReturnToLogin: () -> Unit,
    optionalContent: (@Composable () -> Unit)? = null,
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
    Column(Modifier.fillMaxSize()) {
        if ((state.page != ProfilePage.Basics || pageError != null) && !(state.page == ProfilePage.Optional && optionalContent != null)) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = AppSpacing.Large)) {
                if (state.page != ProfilePage.Basics) TextButton(enabled = !state.busy, onClick = {
                    onPage(when (state.page) { ProfilePage.Lifestyle -> ProfilePage.Basics; ProfilePage.Optional -> ProfilePage.Lifestyle; else -> ProfilePage.Optional })
                }) { Text(stringResource(Res.string.onboarding_previous)) }
                pageError?.let { OnboardingError(it) }
            }
        }
        Box(Modifier.weight(1f)) {
            when (state.page) {
                ProfilePage.Basics -> ProfileBasicsScreen(basics,
                    onNickname, onBirthDate, onGender, onSaveBasics)
                ProfilePage.Lifestyle -> ProfileLifestyleScreen(state.lifestyle.copy(saving = state.busy), onSmoking, onDrinking, onSaveLifestyle)
                ProfilePage.Optional -> if (optionalContent != null) optionalContent() else OnboardingPanel(stringResource(Res.string.onboarding_optional_title)) {
                    Text(stringResource(Res.string.onboarding_optional_wait))
                    AppButton(stringResource(Res.string.onboarding_review_profile), onClick = { onPage(ProfilePage.Confirmation) })
                }
                ProfilePage.Confirmation -> {
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
                    )
                }
            }
        }
    }
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
