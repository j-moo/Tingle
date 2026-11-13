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
            val p = state.profile
            val shown = state.visible && p != null
            val loading = !shown && state.load == ChatPartnerLoad.Loading
            // 갈 곳이 정해진 상태(종료 · 로그인 · 가입 단계)는 아래 큰 버튼, 그 밖의 실패는 당기기와 작은 "다시 시도"
            val next = when (state.load) {
                ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable -> ChatPartnerAction.ChatList
                ChatPartnerLoad.LoginRequired -> ChatPartnerAction.Login
                ChatPartnerLoad.OnboardingRequired -> ChatPartnerAction.Onboarding
                else -> null
            }.takeIf { !shown && !loading }
            val reload = { if (shown || !loading && next == null) onAction(ChatPartnerAction.Reload) }
            PullDownRefreshBox(false, reload, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = if (shown || loading) Arrangement.spacedBy(AppSpacing.Large) else Arrangement.Center) {
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
                    } else if (loading) PartnerSkeleton()
                    else when (state.load) {
                        ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable -> StateView(StateKind.Locked, stringResource(Res.string.chat_partner_closed))
                        ChatPartnerLoad.LoginRequired -> StateView(StateKind.Session, stringResource(Res.string.state_session))
                        ChatPartnerLoad.OnboardingRequired -> StateView(StateKind.Locked, stringResource(Res.string.state_locked))
                        // 실패 사유(연결 · 서버)는 이 화면 상태에 없어 알 수 없는 오류로 보인다
                        else -> StateView(StateKind.Unknown, stringResource(Res.string.state_unknown), onRetry = reload)
                    }
                }
            }
            if (next != null) AppButton(stringResource(when (next) {
                ChatPartnerAction.ChatList -> Res.string.chat_partner_list
                ChatPartnerAction.Login -> Res.string.chat_partner_relogin
                else -> Res.string.chat_partner_continue
            }), Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = AppSpacing.Large).padding(top = AppSpacing.Small, bottom = AppSpacing.Large), onClick = { onAction(next) })
        }
    }
}

/** 뼈대: 사진 · 이름 · 나이 · 소개 · 생활 정보 자리 */
@Composable private fun PartnerSkeleton() {
    SkeletonGroup {
        SkeletonBone(Modifier.aspectRatio(1f), shape = AppShapes.Card)
        SkeletonBone(width = 160.dp, height = 28.dp)
        SkeletonBone(width = 80.dp, height = 20.dp)
        SkeletonBone(height = 96.dp, shape = AppShapes.Card)
        SkeletonBone(height = 160.dp, shape = AppShapes.Card)
    }
}

@Composable private fun PartnerPhoto(state: ChatPartnerState, action: (ChatPartnerAction) -> Unit) {
    // 사진을 받는 동안은 사진 자리만 뼈대로 둔다
    if (state.visiblePhoto == ChatPartnerPhoto.Loading) {
        SkeletonGroup(label = stringResource(Res.string.chat_partner_photo_loading)) { SkeletonBone(Modifier.aspectRatio(1f), shape = AppShapes.Card) }
        return
    }
    Surface(Modifier.fillMaxWidth(), shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        if (state.visiblePhoto == ChatPartnerPhoto.Ready && state.image != null) {
            Image(state.image.bitmap, stringResource(Res.string.chat_partner_photo), Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        } else Column(Modifier.fillMaxWidth().padding(AppSpacing.ExtraLarge), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(when (state.visiblePhoto) {
                ChatPartnerPhoto.Ready, ChatPartnerPhoto.Failed -> Res.string.chat_partner_photo_failed
                else -> Res.string.chat_partner_no_photo
            }), color = AppColors.TextSecondary)
            if (state.visiblePhoto == ChatPartnerPhoto.Failed) TextAction(stringResource(Res.string.state_retry), { action(ChatPartnerAction.RetryPhoto) })
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
