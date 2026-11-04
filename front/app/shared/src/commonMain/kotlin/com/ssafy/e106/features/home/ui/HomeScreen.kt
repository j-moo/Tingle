package com.ssafy.e106.features.home.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 가입 완료 회원의 순수 홈 화면. 저장/통신/실제 화면 이동은 호출부에서 처리한다. */
@Composable
fun HomeScreen(state: HomeState, onMatchingChange: (Boolean) -> Unit, onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier, onOpenMatchingSettings: (() -> Unit)? = null) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.home_brand), actionText = stringResource(Res.string.home_refresh),
                onAction = { onAction(HomeAction.Refresh) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    Text(stringResource(Res.string.home_heading), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    when (state.load) {
                        HomeLoadState.Loading -> {
                            StatusCard(stringResource(Res.string.home_loading), stringResource(Res.string.home_loading_help))
                            CircularProgressIndicator(color = AppColors.SelectionBorder, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                        HomeLoadState.Failed -> {
                            StatusCard(stringResource(Res.string.home_failed), stringResource(Res.string.home_failed_help))
                            AppButton(stringResource(Res.string.home_retry), Modifier.fillMaxWidth(),
                                onClick = { onAction(HomeAction.Refresh) })
                        }
                        HomeLoadState.Ready -> {
                            MatchingCard(state, onMatchingChange, onOpenMatchingSettings)
                            KeyringCard(state, onAction)
                        }
                    }
                    Text(stringResource(Res.string.home_connections), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    ServiceRow(stringResource(Res.string.home_proposals),
                        detail = countText(if (state.load == HomeLoadState.Ready) state.proposalCount else null,
                            Res.string.home_proposals_empty, Res.string.home_proposals_count), onClick = { onAction(HomeAction.Proposals) })
                    ServiceRow(stringResource(Res.string.home_chats),
                        detail = countText(if (state.load == HomeLoadState.Ready) state.chatCount else null,
                            Res.string.home_chats_empty, Res.string.home_chats_count), onClick = { onAction(HomeAction.Chats) })
                    ServiceRow(stringResource(Res.string.home_preferences), detail = stringResource(Res.string.home_preferences_help),
                        onClick = { onAction(HomeAction.Preferences) })
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.home_tab), stringResource(Res.string.home_proposal_tab),
                stringResource(Res.string.home_chat_tab), stringResource(Res.string.home_profile_tab)), selected = 0,
                onSelect = { index ->
                    when (index) {
                        1 -> onAction(HomeAction.Proposals)
                        2 -> onAction(HomeAction.Chats)
                        3 -> onAction(HomeAction.Profile)
                    }
                })
        }
    }
}

@Composable
private fun MatchingCard(state: HomeState, onMatchingChange: (Boolean) -> Unit, onOpenSettings: (() -> Unit)?) {
    Surface(shape = AppShapes.Card,
        color = if (state.matchingEnabled == true) AppColors.SelectionContainer else AppColors.SurfaceMuted) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(Res.string.home_matching_title), style = MaterialTheme.typography.titleLarge)
            if (state.matchingEnabled == null) {
                Text(stringResource(Res.string.home_matching_unknown), color = AppColors.TextSecondary)
            } else {
                if (onOpenSettings != null) AppButton(stringResource(if (state.matchingEnabled) Res.string.home_matching_on else Res.string.home_matching_off),
                    onClick = onOpenSettings, variant = AppButtonVariant.Secondary)
                else AppSwitch(state.matchingEnabled, onMatchingChange,
                    stringResource(if (state.matchingEnabled) Res.string.home_matching_on else Res.string.home_matching_off),
                    enabled = !state.savingMatching)
            }
            Text(stringResource(Res.string.home_matching_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (state.savingMatching) Text(stringResource(Res.string.home_matching_saving),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else if (state.matchingSaveFailed) Text(stringResource(Res.string.home_matching_failed), color = AppColors.Error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun KeyringCard(state: HomeState, onAction: (HomeAction) -> Unit) {
    val copy = keyringCopy(state.keyring)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(stringResource(Res.string.home_keyring_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        StatusCard(stringResource(copy.first), stringResource(copy.second))
        if (state.keyring != HomeKeyringState.Missing) {
            val unknown = stringResource(Res.string.home_unknown)
            val battery = state.batteryPercent?.takeIf { it in 0..100 }?.let { stringResource(Res.string.home_battery_value, it) } ?: unknown
            Text(stringResource(Res.string.home_device_info, battery, state.firmware?.takeIf { it.isNotBlank() } ?: unknown),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Text(stringResource(Res.string.home_last_sync, state.lastSyncLabel?.takeIf { it.isNotBlank() } ?: unknown),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        AppButton(stringResource(if (state.keyring == HomeKeyringState.Missing) Res.string.home_register_keyring else Res.string.home_keyring_detail),
            Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = { onAction(HomeAction.Keyring) })
        ServiceRow(stringResource(Res.string.home_encounters),
            detail = stringResource(Res.string.home_pending_records, recordCount(state.pendingUploadCount), recordCount(state.waitingPeerCount)),
            onClick = { onAction(HomeAction.Keyring) })
    }
}

@Composable
private fun recordCount(count: Int?): String = count?.takeIf { it >= 0 }?.let {
    stringResource(Res.string.home_record_count, it)
} ?: stringResource(Res.string.home_unknown)

@Composable
private fun countText(count: Int?, empty: StringResource, nonempty: StringResource): String = when {
    count == null || count < 0 -> stringResource(Res.string.home_count_unknown)
    count == 0 -> stringResource(empty)
    else -> stringResource(nonempty, count)
}

private fun keyringCopy(state: HomeKeyringState): Pair<StringResource, StringResource> = when (state) {
    HomeKeyringState.Ready -> Res.string.home_device_ready to Res.string.home_device_ready_help
    HomeKeyringState.Disconnected -> Res.string.home_device_disconnected to Res.string.home_device_disconnected_help
    HomeKeyringState.DisconnectedPending -> Res.string.home_device_pending to Res.string.home_device_pending_help
    HomeKeyringState.Syncing -> Res.string.home_device_syncing to Res.string.home_device_syncing_help
    HomeKeyringState.SyncFailed -> Res.string.home_device_failed to Res.string.home_device_failed_help
    HomeKeyringState.Missing -> Res.string.home_device_missing to Res.string.home_device_missing_help
    HomeKeyringState.RecheckRequired -> Res.string.home_device_recheck to Res.string.home_device_recheck_help
    HomeKeyringState.StorageFull -> Res.string.home_device_full to Res.string.home_device_full_help
    HomeKeyringState.Unknown -> Res.string.home_device_unknown to Res.string.home_device_unknown_help
}
