package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 공개 정보 표시만 담당한다. 제안 응답 버튼·비공개 정보·회원번호는 받지 않는다. */
@Composable
fun ChatPartnerScreen(state: ChatPartnerState, onAction: (ChatPartnerAction) -> Unit, modifier: Modifier = Modifier) {
    val p = state.profile
    if (state.visible && p != null) { PartnerProfile(state, p, onAction, modifier); return }
    val exit = if (state.load in listOf(ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable)) ChatPartnerAction.ChatList else ChatPartnerAction.Back
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.chat_partner_title), onBack = { onAction(exit) })
            val loading = state.load == ChatPartnerLoad.Loading
            // 갈 곳이 정해진 상태(종료 · 로그인 · 가입 단계)는 아래 큰 버튼, 그 밖의 실패는 당기기와 작은 "다시 시도"
            val next = when (state.load) {
                ChatPartnerLoad.Closed, ChatPartnerLoad.Unavailable -> ChatPartnerAction.ChatList
                ChatPartnerLoad.LoginRequired -> ChatPartnerAction.Login
                ChatPartnerLoad.OnboardingRequired -> ChatPartnerAction.Onboarding
                else -> null
            }.takeIf { !loading }
            val reload = { if (!loading && next == null) onAction(ChatPartnerAction.Reload) }
            PullDownRefreshBox(false, reload, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = if (loading) Arrangement.spacedBy(AppSpacing.Large) else Arrangement.Center) {
                    if (loading) PartnerSkeleton()
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

internal fun PublicChatPartner.heroInfo() = ProfileHeroInfo(nickname, age.takeIf { it >= 19 }, gender.gender())
internal fun PublicChatPartner.lifestyle() = lifestyleOf(smokingStatus, drinkingFrequency, religion)

/** 상대 프로필: 위는 사진이 상태 바까지 꽉 찬 [ProfileHero](제목 · 오른쪽 버튼 없음), 아래는 생활 칩 · 소개 카드 */
@Composable private fun PartnerProfile(state: ChatPartnerState, p: PublicChatPartner, onAction: (ChatPartnerAction) -> Unit, modifier: Modifier) {
    val photo = when (state.visiblePhoto) {
        ChatPartnerPhoto.Loading -> HeroPhoto.Loading
        ChatPartnerPhoto.Ready -> if (state.image != null) HeroPhoto.Ready else HeroPhoto.Failed
        ChatPartnerPhoto.Failed -> HeroPhoto.Failed
        else -> HeroPhoto.Empty
    }
    val info = p.heroInfo().let { it.copy(nickname = it.nickname.ifBlank { stringResource(Res.string.chat_partner_name_unknown) }) }
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 사진은 상태 바 밑까지 깔고, 나머지는 가로 · 아래 안전 영역만 피한다
        PullDownRefreshBox(false, { onAction(ChatPartnerAction.Reload) },
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            indicatorInsets = WindowInsets.statusBars) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ProfileHero(info, photo, state.image?.bitmap, onBack = { onAction(ChatPartnerAction.Back) },
                    onRetryPhoto = { onAction(ChatPartnerAction.RetryPhoto) })
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth().padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    ProfileLifestyleChips(p.lifestyle())
                    ProfileIntroCard(p.introduction)
                }
            }
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
