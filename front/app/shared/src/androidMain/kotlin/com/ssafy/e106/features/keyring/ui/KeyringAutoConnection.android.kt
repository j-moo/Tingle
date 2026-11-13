package com.ssafy.e106.features.keyring.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.ble.AndroidKeyringBle
import com.ssafy.e106.core.ble.KeyringBle
import com.ssafy.e106.features.keyring.data.KeyringRegistrationProtocol
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.GlobalContext
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.keyring_auto_disable
import com.ssafy.e106.resources.keyring_auto_enable
import com.ssafy.e106.resources.keyring_auto_enabled
import com.ssafy.e106.resources.keyring_auto_unavailable
import org.jetbrains.compose.resources.stringResource

internal data class AutoKeyring(
    val owner: Long, val deviceId: Long, val serial: String, val ownershipEpoch: Long, val address: String,
) {
    override fun toString() = "AutoKeyring(REDACTED)"
}

/** 화면과 서비스가 단일 GATT와 기록 전송 모델을 사용한다. Android 프로세스 수명만큼 유지된다. */
internal object KeyringAutoConnection {
    private val models = ViewModelStore()
    val ble by lazy { GlobalContext.get().get<BleProbeViewModel>().also { models.put("keyring-ble", it) } }
    val encounters by lazy {
        GlobalContext.get().get<KeyringEncounterViewModel>().also { models.put("keyring-encounters", it) }
    }
    val foreground = MutableStateFlow(false)
    val suspended = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    val target = MutableStateFlow<AutoKeyring?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences("keyring_auto_connection", Context.MODE_PRIVATE)

    fun read(context: Context): AutoKeyring? {
        val data = prefs(context)
        val owner = data.getLong("owner", 0)
        val device = data.getLong("device", 0)
        val serial = data.getString("serial", null) ?: return null
        val address = data.getString("address", null) ?: return null
        val epoch = data.getLong("epoch", -1)
        return AutoKeyring(owner, device, serial, epoch, address).takeIf {
            owner > 0 && device > 0 && epoch >= 0 &&
                KeyringRegistrationProtocol.serial(serial) && BluetoothAdapter.checkBluetoothAddress(address)
        }
    }

    fun enable(context: Context, owner: Long, device: OwnedKeyring, link: BleProbeState): Boolean {
        val serial = device.deviceSerial ?: return false
        val verified = ble.state.value
        if (!verified.connected || verified.connectionGeneration != link.connectionGeneration ||
            verified.info?.serial != serial || verified.info.ownerState != "BOUND") return false
        val epoch = verified.info.ownershipEpoch ?: return false
        if ((GlobalContext.get().get<SessionStore>().state.value as? Session.SignedIn)?.userId != owner) return false
        val platform = GlobalContext.get().get<KeyringBle>() as AndroidKeyringBle
        val address = platform.activeBondedAddress() ?: return false
        val next = AutoKeyring(owner, device.deviceId, serial, epoch, address)
        // 초기화 후 같은 실물을 다시 등록해도 이전 소유 세대의 자동 연결을 재사용하지 않는다.
        prefs(context).edit().putLong("owner", owner).putLong("device", device.deviceId)
            .putString("serial", serial).putLong("epoch", epoch).putString("address", address).apply()
        target.value = next
        return start(context)
    }

    fun start(context: Context): Boolean {
        return try {
            ContextCompat.startForegroundService(context, Intent(context, KeyringAutoConnectionService::class.java))
            true
        } catch (_: RuntimeException) {
            forget(context)
            false
        }
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        target.value = null
    }
}

@Composable internal actual fun keyringBleModel(): BleProbeViewModel = KeyringAutoConnection.ble
@Composable internal actual fun keyringEncounterModel(): KeyringEncounterViewModel = KeyringAutoConnection.encounters

@Composable internal actual fun KeyringAutoConnectionHost(suspended: Boolean) {
    val context = LocalContext.current
    SideEffect { KeyringAutoConnection.suspended.value = suspended }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { KeyringAutoConnection.foreground.value = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val controller = KeyringAutoConnection
        val session = GlobalContext.get().get<SessionStore>().state.value as? Session.SignedIn
        if (!controller.running.value && session?.isReady == true &&
            controller.read(context)?.owner == session.userId) controller.start(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { KeyringAutoConnection.foreground.value = false }
}

internal actual fun retainKeyringAutoConnection(link: BleProbeState): Boolean {
    val info = link.info ?: return false
    return link.connected && managesKeyringAutoConnection(info.serial) &&
        KeyringAutoConnection.target.value?.ownershipEpoch == info.ownershipEpoch
}

internal actual fun managesKeyringAutoConnection(serial: String): Boolean {
    val controller = KeyringAutoConnection
    return controller.running.value && !controller.suspended.value &&
        controller.target.value?.serial == serial
}

@Composable internal actual fun KeyringAutoConnectionControl(
    session: Session.SignedIn, selected: OwnedKeyring?, link: BleProbeState,
) {
    val context = LocalContext.current
    val target = KeyringAutoConnection.target.collectAsStateWithLifecycle().value
    val enabled = target?.owner == session.userId
    val connected = selected != null && link.connected && link.info?.serial == selected.deviceSerial
    if (!session.isReady || (!enabled && !connected)) return
    val unavailable = remember(session.userId, selected?.deviceId) { mutableStateOf(false) }
    if (enabled) {
        Text(stringResource(Res.string.keyring_auto_enabled))
        AppButton(stringResource(Res.string.keyring_auto_disable), Modifier.fillMaxWidth()) {
            KeyringAutoConnection.forget(context)
            context.stopService(Intent(context, KeyringAutoConnectionService::class.java))
        }
    } else if (selected != null) {
        AppButton(stringResource(Res.string.keyring_auto_enable), Modifier.fillMaxWidth(), enabled = !link.busy) {
            unavailable.value = !KeyringAutoConnection.enable(context, session.userId, selected, link)
        }
        if (unavailable.value) Text(stringResource(Res.string.keyring_auto_unavailable))
    }
}
