package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 실행 화면은 실제 API만 사용한다. 회전/복귀 시 다시 읽고 화면 이탈 시 민감 목록을 제거한다. */
@Composable fun ConnectedOwnedKeyringsRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: OwnedKeyringsViewModel = koinViewModel(), onReset: (Long) -> Unit,
    // 서버 소유 해제 뒤에도 원 계정의 로컬 기록으로 중단된 초기화를 다시 찾는다
    recoveryLinks: (@Composable () -> Unit)? = null, onConnectForRecords: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    // 진입과 복귀를 한 곳에서 처리해 첫 화면에서 GET 두 개가 동시에 시작되지 않게 한다.
    LifecycleResumeEffect(session, state.owner) {
        if (state.owner == session) model.load()
        onPauseOrDispose { model.leave() }
    }
    ProfileBackHandler(true, onBack)
    if (state.owner == session) OwnedKeyringsScreen(state, model::load, onBack, onReset, onConnectForRecords) {
        if (recoveryLinks != null) recoveryLinks()
        else {
            // 목록이 아직 없으면(불러오는 중 · 실패) 중복 여부를 모르므로 띄우지 않는다
            val items = state.items
            if (items != null) KeyringResetRecoveryLinks(session.userId, onReset, skip = items.map { it.deviceId }.toSet())
        }
    }
}

/**
 * 순수 표시 함수(Preview 용). 키링 고르기 화면과 같은 카드("내 키링 N" · 펌웨어)에 오른쪽 "초기화" 버튼을 둔다. 서버 번호는 보여주지 않는다.
 * 목록은 들어올 때 · 돌아올 때 자동으로 불러오므로, 다시 불러오기 버튼은 실패했을 때만 둔다.
 */
@Composable fun OwnedKeyringsScreen(state: OwnedKeyringsState, onRefresh: () -> Unit, onBack: () -> Unit,
    onReset: (Long) -> Unit, onConnectForRecords: (() -> Unit)? = null, recoveryLinks: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackArrowBar(onBack)
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically)) {
                    Text(stringResource(Res.string.owned_keyrings_title), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    when {
                        state.loading -> {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.owned_keyrings_loading), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                        }
                        state.error != null -> {
                            Text(stringResource(if (state.error is AppError.OnboardingRequired)
                                Res.string.owned_keyrings_blocked else Res.string.owned_keyrings_failed), textAlign = TextAlign.Center)
                            Text(state.error.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            AppButton(stringResource(Res.string.sync_live_retry), Modifier.fillMaxWidth(), onClick = onRefresh)
                        }
                        state.items?.isEmpty() == true -> Text(stringResource(Res.string.owned_keyrings_empty),
                            color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                        state.items != null -> state.items.forEachIndexed { index, item ->
                            val title = stringResource(Res.string.sync_live_device, (index + 1).toString())
                            // TalkBack 이 어느 키링의 초기화인지 읽게 한다
                            val label = stringResource(Res.string.owned_keyrings_reset_for, title)
                            KeyringCard(title, selected = false, onClick = null,
                                trailing = { AppButton(stringResource(Res.string.owned_keyrings_reset), Modifier.semantics { contentDescription = label },
                                    variant = AppButtonVariant.Secondary, onClick = { onReset(item.deviceId) }) }) {
                                Text(stringResource(Res.string.sync_pick_firmware, item.firmwareVersion), style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary)
                            }
                        }
                    }
                    // 연결 화면만 연다. 사용자가 실물에 연결한 뒤 전송을 별도로 시작한다.
                    if (!state.loading && state.error == null && !state.items.isNullOrEmpty() && onConnectForRecords != null) {
                        AppButton(stringResource(Res.string.keyring_encounter_connect), Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Secondary, onClick = onConnectForRecords)
                    }
                    // 서버 소유 해제 후에도 원 계정의 로컬 실행 기록으로 초기화 결과를 다시 찾을 수 있다
                    recoveryLinks()
                }
            }
        }
    }
}
