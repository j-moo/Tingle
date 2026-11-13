package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.keyring.data.KeyringOwnerAuthorizer
import com.ssafy.e106.features.keyring.data.KeyringPublicNameCommand
import com.ssafy.e106.features.keyring.data.KeyringPublicNameProtocol
import com.ssafy.e106.features.keyring.data.KeyringSyncStore
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private data class PublicNameRequest(val command: KeyringPublicNameCommand, val name: String? = null)

/** 홈 별명 편집에서 현재 소유 키링의 광고 이름을 읽고 사용자가 누른 변경만 기기로 보낸다. */
@Composable
internal fun KeyringPublicNameControls(
    owner: Long,
    device: OwnedKeyring,
    savedAlias: String,
    ble: BleProbeViewModel = keyringBleModel(),
    store: KeyringSyncStore = koinInject(),
    authorizer: KeyringOwnerAuthorizer = koinInject(),
) {
    val link by ble.state.collectAsStateWithLifecycle()
    val info = link.info?.takeIf { link.connected && it.serial == device.deviceSerial && it.ownerState == "BOUND" }
    val stamp = link.connectionGeneration?.takeIf { info != null }
    val limit = info?.takeIf { it.supportsPublicName }?.maxPublicNameBytes
    val name = savedAlias
    var pending by remember(owner, device.deviceId, stamp) {
        mutableStateOf<PublicNameRequest?>(PublicNameRequest(KeyringPublicNameCommand.Read))
    }
    var publicName by remember(owner, device.deviceId, stamp) { mutableStateOf<String?>(null) }
    var loaded by remember(owner, device.deviceId, stamp) { mutableStateOf(false) }
    var failed by remember(owner, device.deviceId, stamp) { mutableStateOf(false) }
    val request = pending
    // 새 연결에서 현재 공개 상태를 다시 읽는다. 앱 이탈·연결 변경 시 이전 응답은 화면에 반영하지 않는다.
    LaunchedEffect(owner, device.deviceId, stamp, limit, request) {
        if (stamp != null && limit != null && request != null) {
            try {
                publicName = withTimeout(90_000) {
                    ble.publicName(owner, device, stamp, store, authorizer, request.command, request.name)
                }
                loaded = true
                failed = false
            } catch (_: TimeoutCancellationException) {
                loaded = false
                failed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loaded = false
                failed = true
            }
            pending = null
        }
    }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Text(stringResource(Res.string.home_keyring_public_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(Res.string.home_keyring_public_help), style = MaterialTheme.typography.bodySmall)
        when {
            stamp == null -> Text(stringResource(Res.string.home_keyring_public_connect))
            limit == null -> Text(stringResource(Res.string.home_keyring_public_unsupported))
            pending != null -> Text(stringResource(Res.string.home_keyring_public_checking))
            failed -> Text(stringResource(Res.string.home_keyring_public_failed), color = MaterialTheme.colorScheme.error)
            loaded && publicName != null -> Text(stringResource(Res.string.home_keyring_public_enabled, publicName.orEmpty()))
            loaded -> Text(stringResource(Res.string.home_keyring_public_disabled))
        }
        if (stamp != null && limit != null && pending == null) {
            if (name.isEmpty()) {
                Text(stringResource(Res.string.home_keyring_public_save_first), style = MaterialTheme.typography.bodySmall)
            } else if (!KeyringPublicNameProtocol.validName(name, limit)) {
                Text(stringResource(Res.string.home_keyring_public_limit, limit), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                TextButton(onClick = { pending = PublicNameRequest(KeyringPublicNameCommand.Set, name) },
                    enabled = KeyringPublicNameProtocol.validName(name, limit) && (!loaded || publicName != name)) {
                    Text(stringResource(Res.string.home_keyring_public_publish))
                }
                if (!loaded || publicName != null) {
                    TextButton(onClick = { pending = PublicNameRequest(KeyringPublicNameCommand.Clear) }) {
                        Text(stringResource(Res.string.home_keyring_public_clear))
                    }
                }
            }
            if (failed) {
                TextButton(onClick = { pending = PublicNameRequest(KeyringPublicNameCommand.Read) }) {
                    Text(stringResource(Res.string.home_keyring_public_retry))
                }
            }
        }
    }
}
