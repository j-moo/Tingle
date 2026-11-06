package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.resources.*
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import org.jetbrains.compose.resources.stringResource

/** DEVICE_INFO의 지원 선언은 송신/인증 성공이 아니다. 서버 계약이 없는 새 명령은 여기서 실행하지 않는다. */
@Composable fun OwnerFlowCapabilityDiagnostics(info: BleDeviceInfo) {
    val unknown = stringResource(Res.string.ble_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(if (info.ownerFlow.supportsFullSettings(info))
            Res.string.ble_full_supported else Res.string.ble_full_unconfirmed))
        Text(stringResource(Res.string.ble_owner_identity, info.ownerFlow.keyId ?: unknown,
            info.ownershipEpoch?.toString() ?: unknown, info.ownerState ?: unknown))
        Text(stringResource(Res.string.ble_owner_capability, info.ownerFlow.peerProtocol ?: unknown,
            info.ownerFlow.peerOwnerScopeVersion?.toString() ?: unknown, info.ownerFlow.accountLockProofVersion ?: unknown))
    }
}

/** 가로 고정 표 대신 줄바꿈되는 항목을 사용한다. 상위 화면의 스크롤과 큰 글자 설정을 그대로 따른다. */
@Composable fun KeyringStatusDiagnostics(status: BleDeviceStatus) {
    val unknown = stringResource(Res.string.ble_unknown)
    val flow = status.ownerFlow
    val charging = when (status.charging) {
        true -> stringResource(Res.string.ble_charging)
        false -> stringResource(Res.string.ble_not_charging)
        null -> unknown
    }
    val source = when (flow.batteryPercentSource) {
        "VOLTAGE_ESTIMATE" -> stringResource(Res.string.ble_battery_estimate)
        "FUEL_GAUGE" -> stringResource(Res.string.ble_battery_gauge)
        else -> unknown
    }
    // 표시 당시 READ가 확인되지 않으면 신형 상세 값은 사용하지 않는다. 재허가/조회는 별도 흐름이다.
    val records = flow.records.takeIf { flow.ownerAuthorized == true }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(Res.string.ble_status_observation), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.ble_status, status.batteryPercent?.let { "$it%" } ?: unknown,
            status.batteryMillivolts?.let { "$it mV" } ?: unknown, charging,
            status.pendingRecordCount?.toString() ?: unknown))
        Text(stringResource(Res.string.ble_battery_details, source, flow.batteryPresence ?: unknown, flow.powerSource ?: unknown))
        Text(stringResource(Res.string.ble_status_readiness, observedFlag(flow.applied), observedFlag(flow.timeReady), observedFlag(flow.matchingReady)))
        if (flow.readinessConflict) Text(stringResource(Res.string.ble_status_conflict), color = MaterialTheme.colorScheme.error)
        Text(stringResource(Res.string.ble_status_version, flow.storedSettingsVersion?.toString() ?: unknown))
        Text(stringResource(Res.string.ble_status_auth, observedFlag(flow.ownerAuthorized)))
        if (flow.ownerAuthorized != true) Text(stringResource(Res.string.ble_status_auth_needed))
        Text(stringResource(Res.string.ble_status_storage, records?.usedBytes?.toString() ?: unknown,
            records?.totalBytes?.toString() ?: unknown, records?.state ?: unknown))
        Text(stringResource(Res.string.ble_status_records, records?.candidateCount?.toString() ?: unknown,
            records?.reviewCount?.toString() ?: unknown, records?.unresolvedAccountLockCount?.toString() ?: unknown))
        Text(stringResource(Res.string.ble_status_cursor, flow.cursorEpoch ?: unknown))
        Text(stringResource(Res.string.ble_status_notice), style = MaterialTheme.typography.bodySmall)
    }
}

/** false는 미확인과 다르다. 기기 보고만으로 계정 전체의 서비스 이용 가능 여부를 결정하지 않는다. */
@Composable private fun observedFlag(value: Boolean?): String = stringResource(when (value) {
    true -> Res.string.ble_status_yes
    false -> Res.string.ble_status_no
    null -> Res.string.ble_unknown
})

/** Preview/단독 읽기 화면도 공통 스크롤 틀을 사용한다. ViewModel이나 가짜 서비스 요청을 생성하지 않는다. */
@Composable fun KeyringStatusScreen(status: BleDeviceStatus) {
    OnboardingPanel(stringResource(Res.string.ble_status_observation)) { KeyringStatusDiagnostics(status) }
}
