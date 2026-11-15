package com.ssafy.e106.features.keyring.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

    /** 알림의 "끄기": 자동 연결을 끄고, 같은 키링이 다시 연결돼도 자동으로 켜지 않게 기억한다 */
    fun stopByUser(context: Context) {
        val stopped = target.value ?: read(context)
        forget(context)
        if (stopped != null) prefs(context).edit().putLong("stoppedOwner", stopped.owner).putLong("stoppedDevice", stopped.deviceId).apply()
    }

    fun stoppedByUser(context: Context, owner: Long, deviceId: Long) =
        prefs(context).let { it.getLong("stoppedOwner", 0) == owner && it.getLong("stoppedDevice", 0) == deviceId }
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
    // 지금 고른 키링이 자동 연결 대상인지(다른 키링을 연결하면 그 키링으로 바꾼다)
    val enabled = target?.owner == session.userId && target.deviceId == selected?.deviceId
    val connected = selected != null && link.connected && link.info?.serial == selected.deviceSerial
    if (!session.isReady || (!enabled && !connected)) return
    // 자동 연결은 기본으로 켠다. 버튼 없이, 내 키링이 연결되고 소유 확인이 끝나면 한 번 켠다
    // (설정 동기화 전이라 소유 상태가 확인되지 않으면 켜지지 않고, 다음 연결 · 상태 변화 때 다시 시도한다)
    if (!enabled && connected && selected != null && !link.busy &&
        !KeyringAutoConnection.stoppedByUser(context, session.userId, selected.deviceId)) {
        LaunchedEffect(session.userId, selected.deviceId, link.connectionGeneration, link.info?.ownerState, link.info?.ownershipEpoch) {
            KeyringAutoConnection.enable(context, session.userId, selected, link)
        }
    }
}
