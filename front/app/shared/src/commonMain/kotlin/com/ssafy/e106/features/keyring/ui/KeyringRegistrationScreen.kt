package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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

/** 순수 화면: 기기 탐색·타이머·서버 호출 없이 전달받은 상태를 표시한다. */
@Composable
fun KeyringRegistrationScreen(
    state: KeyringRegistrationState,
    onSelectCandidate: (String) -> Unit,
    onAcceptOwnership: (Boolean) -> Unit,
    onAction: (KeyringRegistrationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = registrationCopy(state.step)
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.keyring_registration_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    Text(stringResource(copy.title), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(copy.description), style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary)
                    if (state.step == KeyringRegistrationStep.Searching || state.busy) {
                        CircularProgressIndicator(color = AppColors.SelectionBorder,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(AppSpacing.Large))
                    }
                    when (state.step) {
                        KeyringRegistrationStep.Ready, KeyringRegistrationStep.NotFound -> {
                            StatusCard(stringResource(Res.string.keyring_prepare_title), stringResource(Res.string.keyring_prepare_body))
                            Text(stringResource(Res.string.keyring_one_only), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary)
                        }
                        KeyringRegistrationStep.Found -> {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                                state.candidates.forEach { device ->
                                    AppRadio(selected = device.id == state.selectedId,
                                        onSelect = { onSelectCandidate(device.id) },
                                        label = stringResource(Res.string.keyring_device_label, device.name, device.identifier),
                                        modifier = Modifier.background(
                                            if (device.id == state.selectedId) AppColors.SelectionContainer else AppColors.SurfaceMuted,
                                            AppShapes.Control))
                                }
                            }
                            Text(stringResource(Res.string.keyring_found_help), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary)
                        }
                        KeyringRegistrationStep.ConfirmOwnership -> {
                            state.selected?.let { DeviceCard(it) }
                            StatusCard(stringResource(Res.string.keyring_ownership_title), stringResource(Res.string.keyring_ownership_body))
                            AppCheckbox(state.ownershipAccepted, onAcceptOwnership,
                                stringResource(Res.string.keyring_ownership_accept))
                        }
                        KeyringRegistrationStep.Registering, KeyringRegistrationStep.Checking,
                        KeyringRegistrationStep.Uncertain, KeyringRegistrationStep.Success -> {
                            state.selected?.let { DeviceCard(it) }
                        }
                        else -> Unit
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    AppButton(stringResource(copy.primaryLabel), Modifier.fillMaxWidth(),
                        enabled = state.primaryEnabled, loading = state.busy,
                        loadingText = stringResource(copy.primaryLabel),
                        onClick = { if (state.primaryEnabled) onAction(copy.primaryAction) })
                    copy.secondary?.let { (label, action) ->
                        AppButton(stringResource(label), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                            onClick = { onAction(action) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: KeyringCandidate) {
    Surface(shape = AppShapes.Card, color = AppColors.SelectionContainer) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, color = AppColors.OnSelection)
            Text(device.identifier, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSelection)
        }
    }
}

private data class RegistrationCopy(
    val title: StringResource,
    val description: StringResource,
    val primaryLabel: StringResource,
    val primaryAction: KeyringRegistrationAction,
    val secondary: Pair<StringResource, KeyringRegistrationAction>? = null,
)

private fun registrationCopy(step: KeyringRegistrationStep): RegistrationCopy {
    val purchase = Res.string.keyring_purchase to KeyringRegistrationAction.Purchase
    val chooseAgain = Res.string.keyring_choose_again to KeyringRegistrationAction.ChooseAgain
    return when (step) {
        KeyringRegistrationStep.Ready -> RegistrationCopy(Res.string.keyring_ready_title, Res.string.keyring_ready_body,
            Res.string.keyring_search, KeyringRegistrationAction.Search, purchase)
        KeyringRegistrationStep.PermissionRequired -> RegistrationCopy(Res.string.keyring_permission_title, Res.string.keyring_permission_body,
            Res.string.keyring_allow, KeyringRegistrationAction.RequestPermission, purchase)
        KeyringRegistrationStep.PermissionDenied -> RegistrationCopy(Res.string.keyring_denied_title, Res.string.keyring_denied_body,
            Res.string.keyring_settings, KeyringRegistrationAction.OpenSettings, purchase)
        KeyringRegistrationStep.BluetoothOff -> RegistrationCopy(Res.string.keyring_bluetooth_title, Res.string.keyring_bluetooth_body,
            Res.string.keyring_bluetooth_settings, KeyringRegistrationAction.OpenSettings)
        KeyringRegistrationStep.Searching -> RegistrationCopy(Res.string.keyring_searching_title, Res.string.keyring_searching_body,
            Res.string.keyring_stop_search, KeyringRegistrationAction.CancelSearch)
        KeyringRegistrationStep.NotFound -> RegistrationCopy(Res.string.keyring_not_found_title, Res.string.keyring_not_found_body,
            Res.string.keyring_search_again, KeyringRegistrationAction.Search, purchase)
        KeyringRegistrationStep.Found -> RegistrationCopy(Res.string.keyring_found_title, Res.string.keyring_found_body,
            Res.string.keyring_register, KeyringRegistrationAction.ReviewOwnership,
            Res.string.keyring_search_again to KeyringRegistrationAction.Search)
        KeyringRegistrationStep.ConfirmOwnership -> RegistrationCopy(Res.string.keyring_confirm_title, Res.string.keyring_confirm_body,
            Res.string.keyring_accept_register, KeyringRegistrationAction.Register, chooseAgain)
        KeyringRegistrationStep.Registering -> RegistrationCopy(Res.string.keyring_registering_title, Res.string.keyring_registering_body,
            Res.string.keyring_registering, KeyringRegistrationAction.CheckRegistration)
        KeyringRegistrationStep.Checking -> RegistrationCopy(Res.string.keyring_checking_title, Res.string.keyring_checking_body,
            Res.string.keyring_checking, KeyringRegistrationAction.CheckRegistration)
        KeyringRegistrationStep.Uncertain -> RegistrationCopy(Res.string.keyring_uncertain_title, Res.string.keyring_uncertain_body,
            Res.string.keyring_check_again, KeyringRegistrationAction.CheckRegistration)
        KeyringRegistrationStep.Failed -> RegistrationCopy(Res.string.keyring_failed_title, Res.string.keyring_failed_body,
            Res.string.keyring_search_again, KeyringRegistrationAction.Search)
        KeyringRegistrationStep.OwnedByOther -> RegistrationCopy(Res.string.keyring_owned_title, Res.string.keyring_owned_body,
            Res.string.keyring_choose_again, KeyringRegistrationAction.ChooseAgain)
        KeyringRegistrationStep.Unusable -> RegistrationCopy(Res.string.keyring_unusable_title, Res.string.keyring_unusable_body,
            Res.string.keyring_choose_again, KeyringRegistrationAction.ChooseAgain,
            Res.string.keyring_support to KeyringRegistrationAction.ContactSupport)
        KeyringRegistrationStep.RegistrationLimit -> RegistrationCopy(Res.string.keyring_limit_title, Res.string.keyring_limit_body,
            Res.string.keyring_manage, KeyringRegistrationAction.ManageKeyring)
        KeyringRegistrationStep.Success -> RegistrationCopy(Res.string.keyring_success_title, Res.string.keyring_success_body,
            Res.string.keyring_continue, KeyringRegistrationAction.Continue)
    }
}
