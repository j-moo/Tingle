package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 읽기 진단은 단독 사용 가능하다. 개발 등록 경로에서만 별도 등록 모델을 받아 실제 귀속 확인 UI를 제공한다. */
@Composable fun ConnectedBleProbeRoute(onBack: () -> Unit, model: BleProbeViewModel = koinViewModel(),
    registration: KeyringRegistrationViewModel? = null) {
    val permission = rememberBlePermission()
    val state by model.state.collectAsStateWithLifecycle()
    val registering = registration?.state?.collectAsStateWithLifecycle()?.value?.busy == true
    var settingsFailed by remember { mutableStateOf(false) }
    // 회전도 종료 정책이다. 복귀 후 자동으로 주변 기기를 연결하지 않고 사용자 재시도를 기다린다.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { registration?.restore() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { registration?.pause(); model.leave() }
    DisposableEffect(model, registration) { onDispose { registration?.pause(); model.leave() } }
    LaunchedEffect(permission.availability) { if (permission.availability != BleAvailability.Ready) model.leave() }
    ProfileBackHandler(true) { registration?.pause(); model.leave(); onBack() }
    OnboardingPanel(stringResource(Res.string.ble_probe_title)) {
        TextButton(onClick = { registration?.pause(); model.leave(); onBack() }) { Text(stringResource(Res.string.owned_keyrings_back)) }
        Text(stringResource(if (registration == null) Res.string.ble_probe_description else Res.string.registration_live_description))
        Text(stringResource(when (permission.availability) {
            BleAvailability.Unsupported -> Res.string.ble_unsupported
            BleAvailability.PermissionRequired -> Res.string.ble_permission
            BleAvailability.BluetoothOff -> Res.string.ble_bluetooth_off
            BleAvailability.LocationOff -> Res.string.ble_location_off
            BleAvailability.Ready -> Res.string.ble_available
        }))
        fun guarded(action: () -> Unit) { settingsFailed = runCatching(action).isFailure }
        when (permission.availability) {
            BleAvailability.PermissionRequired -> {
                AppButton(stringResource(Res.string.ble_request_permission), onClick = { guarded(permission.request) })
                TextButton(onClick = { guarded(permission.openAppSettings) }) { Text(stringResource(Res.string.ble_app_settings)) }
            }
            BleAvailability.BluetoothOff -> AppButton(stringResource(Res.string.ble_bluetooth_settings), onClick = { guarded(permission.openBluetoothSettings) })
            BleAvailability.LocationOff -> AppButton(stringResource(Res.string.ble_location_settings), onClick = { guarded(permission.openLocationSettings) })
            else -> Unit
        }
        if (settingsFailed) Text(stringResource(Res.string.ble_settings_failed), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(stringResource(Res.string.ble_failed, it.name), color = MaterialTheme.colorScheme.error) }
        state.commandError?.let { Text(stringResource(Res.string.ble_command_failed, it), color = MaterialTheme.colorScheme.error) }
        if (state.busy) { CircularProgressIndicator(); Text(stringResource(Res.string.ble_connecting)) }
        if (permission.availability == BleAvailability.Ready && !state.connected) {
            AppButton(stringResource(if (state.scanning) Res.string.ble_stop_scan else Res.string.ble_start_scan),
                enabled = !state.busy && !registering, onClick = { if (state.scanning) model.stopScan() else model.scan() })
            Text(stringResource(Res.string.ble_scan_help))
            if (state.scanning) Text(stringResource(Res.string.ble_scanning))
            state.candidates.forEachIndexed { index, device ->
                AppButton(stringResource(Res.string.ble_candidate, index + 1, device.name ?: "NUS", device.rssi),
                    modifier = Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                    enabled = !state.busy && !registering, onClick = { model.connect(device.id) })
            }
        }
        state.info?.let { info ->
            Text(stringResource(Res.string.ble_info, info.serial, info.firmware))
            Text(stringResource(Res.string.ble_not_registered))
            AppButton(stringResource(Res.string.ble_read_status), enabled = !state.busy && !registering && state.connected && info.supportsStatus,
                onClick = model::readStatus)
            if (!info.supportsStatus) Text(stringResource(Res.string.ble_status_unsupported))
        }
        state.status?.let { status ->
            val unknown = stringResource(Res.string.ble_unknown)
            val charging = when (status.charging) { true -> stringResource(Res.string.ble_charging); false -> stringResource(Res.string.ble_not_charging); null -> unknown }
            Text(stringResource(Res.string.ble_status, status.batteryPercent?.let { "$it%" } ?: unknown,
                status.batteryMillivolts?.let { "$it mV" } ?: unknown, charging, status.pendingRecordCount?.toString() ?: unknown))
        }
        if (state.connected || state.busy) AppButton(stringResource(Res.string.ble_disconnect),
            variant = AppButtonVariant.Secondary, enabled = !registering, onClick = model::leave)
        registration?.let { KeyringRegistrationPanel(it, model, state) }
    }
}
