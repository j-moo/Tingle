package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 공개 정보 표시만 담당한다. 제안 응답 버튼·비공개 정보·회원번호는 받지 않는다. */
@Composable
fun ChatPartnerScreen(state: ChatPartnerState, onAction: (ChatPartnerAction) -> Unit, modifier: Modifier = Modifier) {
    val exit = if (state.load in listOf(ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable)) ChatPartnerAction.ChatList else ChatPartnerAction.Back
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.chat_partner_title), onBack = { onAction(exit) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    val p = state.profile
                    if (state.visible && p != null) {
                        PartnerPhoto(state, onAction)
                        Text(p.nickname.ifBlank { stringResource(Res.string.chat_partner_name_unknown) }, style = MaterialTheme.typography.headlineMedium)
                        Text(if (p.age >= 19) stringResource(Res.string.chat_partner_age, p.age) else stringResource(Res.string.chat_partner_unknown),
                            style = MaterialTheme.typography.titleMedium)
                        PartnerValue(stringResource(Res.string.chat_partner_gender), when (p.gender) {
                            "MALE" -> stringResource(Res.string.profile_male)
                            "FEMALE" -> stringResource(Res.string.profile_female)
                            else -> stringResource(Res.string.chat_partner_unknown)
                        })
                        PartnerSection(stringResource(Res.string.chat_partner_intro)) {
                            Text(p.introduction?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_partner_intro_empty))
                        }
                        PartnerSection(stringResource(Res.string.chat_partner_lifestyle)) {
                            PartnerValue(stringResource(Res.string.chat_partner_smoking), ProfileSmoking.entries.find { it.code() == p.smokingStatus }
                                ?.let { stringResource(it.label()) } ?: stringResource(Res.string.chat_partner_unknown))
                            PartnerValue(stringResource(Res.string.chat_partner_drinking), ProfileDrinking.entries.find { it.code() == p.drinkingFrequency }
                                ?.let { stringResource(it.label()) } ?: stringResource(Res.string.chat_partner_unknown))
                            PartnerValue(stringResource(Res.string.chat_partner_religion), p.religion.religion()?.let { stringResource(it.label()) }
                                ?: stringResource(if (p.religion == null) Res.string.chat_partner_not_entered else Res.string.chat_partner_unknown))
                        }
                        AppButton(stringResource(Res.string.chat_partner_back), Modifier.fillMaxWidth(), onClick = { onAction(ChatPartnerAction.Back) })
                    } else PartnerStatus(state.load, onAction)
                }
            }
        }
    }
}

@Composable private fun PartnerStatus(load: ChatPartnerLoad, action: (ChatPartnerAction) -> Unit) {
    val (title, help) = when (load) {
        ChatPartnerLoad.Loading -> Res.string.chat_partner_loading to Res.string.chat_partner_loading_help
        ChatPartnerLoad.Closed -> Res.string.chat_partner_closed to Res.string.chat_partner_closed_help
        ChatPartnerLoad.Unavailable -> Res.string.chat_partner_closed to Res.string.chat_partner_unavailable_help
        ChatPartnerLoad.LoginRequired -> Res.string.chat_partner_login to Res.string.chat_partner_login_help
        ChatPartnerLoad.OnboardingRequired -> Res.string.chat_partner_onboarding to Res.string.chat_partner_onboarding_help
        else -> Res.string.chat_partner_failed to Res.string.chat_partner_failed_help
    }
    StatusCard(stringResource(title), stringResource(help))
    // 로딩 원은 가로 가운데
    if (load == ChatPartnerLoad.Loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppColors.SelectionBorder)
    }
    else {
        val next = when (load) {
            ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable -> ChatPartnerAction.ChatList
            ChatPartnerLoad.LoginRequired -> ChatPartnerAction.Login
            ChatPartnerLoad.OnboardingRequired -> ChatPartnerAction.Onboarding
            else -> ChatPartnerAction.Reload
        }
        AppButton(stringResource(when (next) {
            ChatPartnerAction.ChatList -> Res.string.chat_partner_list
            ChatPartnerAction.Reload -> Res.string.chat_partner_retry
            else -> Res.string.chat_partner_continue
        }), Modifier.fillMaxWidth(), onClick = { action(next) })
    }
}

@Composable private fun PartnerPhoto(state: ChatPartnerState, action: (ChatPartnerAction) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        if (state.visiblePhoto == ChatPartnerPhoto.Ready && state.image != null) {
            Image(state.image.bitmap, stringResource(Res.string.chat_partner_photo), Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        } else Column(Modifier.fillMaxWidth().padding(AppSpacing.ExtraLarge), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(when (state.visiblePhoto) {
                ChatPartnerPhoto.Loading -> Res.string.chat_partner_photo_loading
                ChatPartnerPhoto.Ready, ChatPartnerPhoto.Failed -> Res.string.chat_partner_photo_failed
                else -> Res.string.chat_partner_no_photo
            }), color = AppColors.TextSecondary)
            if (state.visiblePhoto == ChatPartnerPhoto.Loading) CircularProgressIndicator(color = AppColors.SelectionBorder)
            if (state.visiblePhoto == ChatPartnerPhoto.Failed) AppButton(stringResource(Res.string.chat_partner_photo_retry),
                variant = AppButtonVariant.Secondary, onClick = { action(ChatPartnerAction.RetryPhoto) })
        }
    }
}

@Composable private fun PartnerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(Modifier.padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun PartnerValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        Text(value)
    }
}
