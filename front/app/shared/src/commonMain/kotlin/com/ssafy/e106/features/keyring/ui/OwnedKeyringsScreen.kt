package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 실행 화면은 실제 API만 사용한다. 회전/복귀 시 다시 읽고 화면 이탈 시 민감 목록을 제거한다. */
@Composable fun ConnectedOwnedKeyringsRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: OwnedKeyringsViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    // 진입과 복귀를 한 곳에서 처리해 첫 화면에서 GET 두 개가 동시에 시작되지 않게 한다.
    LifecycleResumeEffect(session, state.owner) {
        if (state.owner == session) model.load()
        onPauseOrDispose { model.leave() }
    }
    ProfileBackHandler(true, onBack)
    if (state.owner == session) OwnedKeyringsScreen(state, model::load, onBack)
}

/** 순수 표시 함수. Preview 예시와 실제 조회를 분리하고 등록·초기화·동기화 실행을 흉내 내지 않는다. */
@Composable fun OwnedKeyringsScreen(state: OwnedKeyringsState, onRefresh: () -> Unit, onBack: () -> Unit) {
    OnboardingPanel(stringResource(Res.string.owned_keyrings_title)) {
        TextButton(onClick = onBack) { Text(stringResource(Res.string.owned_keyrings_back)) }
        Text(stringResource(Res.string.owned_keyrings_description))
        when {
            state.loading -> {
                CircularProgressIndicator()
                Text(stringResource(Res.string.owned_keyrings_loading))
            }
            state.error != null -> {
                Text(stringResource(if (state.error is AppError.OnboardingRequired)
                    Res.string.owned_keyrings_blocked else Res.string.owned_keyrings_failed))
                Text(state.error.message, color = MaterialTheme.colorScheme.error)
            }
            state.items?.isEmpty() == true -> Text(stringResource(Res.string.owned_keyrings_empty))
            state.items != null -> state.items.forEach { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(stringResource(Res.string.owned_keyrings_device, item.deviceId.toString()), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.owned_keyrings_firmware, item.firmwareVersion))
                        // OFFLINE의 관측 정책은 아직 미확정이다. 서버가 값을 보내도 현재 BLE 단절로 단정하지 않는다.
                        Text(stringResource(when (item.status) {
                            "REGISTERED" -> Res.string.owned_keyrings_registered
                            "SYNC_PENDING" -> Res.string.owned_keyrings_pending
                            "READY" -> Res.string.owned_keyrings_ready
                            else -> Res.string.owned_keyrings_unknown
                        }))
                    }
                }
            }
        }
        Text(stringResource(Res.string.owned_keyrings_live_unavailable), style = MaterialTheme.typography.bodySmall)
        AppButton(stringResource(Res.string.owned_keyrings_refresh), enabled = !state.loading,
            variant = AppButtonVariant.Secondary, onClick = onRefresh)
    }
}
