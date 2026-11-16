package com.ssafy.e106.features.keyring.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.ble.AndroidKeyringBle
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.core.ble.BleFailure
import com.ssafy.e106.core.ble.KeyringBle
import com.ssafy.e106.core.ble.bleAvailability
import com.ssafy.e106.core.network.NetworkMonitor
import com.ssafy.e106.features.keyring.data.KeyringResetStore
import com.ssafy.e106.features.keyring.data.KeyringSyncStore
import com.ssafy.e106.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** 등록 기기의 대기 연결을 유지한다. 화면이 보이지 않을 때도 같은 보호 전송과 접수증 복구를 쓴다. */
internal class KeyringAutoConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = KeyringAutoConnection
    private var jobs: Job? = null
    private var currentTarget: AutoKeyring? = null
    private val koin get() = GlobalContext.get()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stopByUser(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val target = controller.read(this)
        if (target == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: RuntimeException) {
            controller.forget(this)
            stopSelf()
            return START_NOT_STICKY
        }
        controller.target.value = target
        controller.running.value = true
        if (currentTarget != target) {
            currentTarget = target
            val previous = jobs
            previous?.cancel()
            jobs = scope.launch {
                previous?.join()
                runConnection(target)
            }
        }
        return START_STICKY
    }

    private suspend fun runConnection(target: AutoKeyring) {
        val sessions = koin.get<SessionStore>()
        val resets = koin.get<KeyringResetStore>()
        val sync = koin.get<KeyringSyncStore>()
        val platform = koin.get<KeyringBle>() as AndroidKeyringBle
        val ble = controller.ble
        val encounters = controller.encounters
        // 연결 감시와 전송을 따로 수집한다. 화면 전환이 성공한 GATT 연결을 다시 열게 만들지 않는다.
        coroutineScope {
            launch {
                sessions.state.collect { session ->
                    if (session != Session.Restoring && (session as? Session.SignedIn)?.userId != target.owner) {
                        disableTarget(target)
                    }
                }
            }
            launch {
                // 초기화 완료 응답이 확인되면 블루투스가 다음에 연결될 때까지 설정을 남겨 두지 않는다.
                combine(sessions.state, resets.revision) { session, _ -> (session as? Session.SignedIn)?.userId }
                    .collectLatest { owner ->
                        if (owner == target.owner && readReset(resets, target)?.any {
                                it.run?.state == "COMPLETED" && it.run.fromOwnershipEpoch == target.ownershipEpoch
                            } == true) disableTarget(target)
                    }
            }
            val allowed = combine(sessions.state, controller.suspended, bluetoothReady(),
                koin.get<NetworkMonitor>().availability, resets.revision) { session, suspended, ready, online, revision ->
                // 서버에 접근할 수 없으면 소유 검증 전 연결을 보류한다. 복원 시 자동으로 다시 기다린다.
                if ((session as? Session.SignedIn)?.let { it.userId == target.owner && it.isReady } == true &&
                    !suspended && ready && online) revision else null
            }.distinctUntilChanged()
            launch {
                allowed.collectLatest { revision ->
                    if (revision == null) return@collectLatest
                    if (!resetAllows(resets, target)) return@collectLatest
                    var waitMillis = 2_000L
                    while (isActive) {
                        ble.state.first { !it.connected && !it.scanning && !it.busy }
                        try {
                            // 수동 연결이 취소할 수 있는 하위 작업이다. 서비스의 장기 감시까지 취소하지 않는다.
                            coroutineScope {
                                ble.connectOwnedAutomatically(target.owner, target.deviceId, target.serial, target.ownershipEpoch) {
                                    platform.connectBondedAddress(target.address)
                                }
                            }
                            waitMillis = 2_000L
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        } catch (error: BleException) {
                            if (error.reason == BleFailure.NotOwned || error.reason == BleFailure.NotFound) {
                                disableTarget(target)
                                return@collectLatest
                            }
                            delay(waitMillis)
                            waitMillis = (waitMillis * 2).coerceAtMost(60_000L)
                        } catch (_: Exception) {
                            delay(60_000)
                        }
                    }
                }
            }
            combine(allowed, controller.foreground,
                ble.state.map { state -> state.connectionGeneration?.takeIf {
                    state.connected && state.info?.serial == target.serial &&
                        state.info.ownershipEpoch == target.ownershipEpoch && state.info.supportsEncounterTransfer
                } }.distinctUntilChanged(), sync.targetsChanged) { revision, foreground, generation, _ ->
                generation?.takeIf { revision != null && !foreground }
            }.collectLatest { generation ->
                if (generation == null || !resetAllows(resets, target)) return@collectLatest
                val ticket = try {
                    sync.read(target.owner).singleOrNull { it.deviceId == target.deviceId &&
                        it.serial == target.serial && it.complete && it.identity?.ownershipEpoch == target.ownershipEpoch }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val runId = ticket?.identity?.syncRunId ?: return@collectLatest
                val transferTarget = KeyringEncounterViewModel.Target(target.owner, target.serial, generation)
                encounters.update(transferTarget, syncing = false, confirmed = runId, ble = ble)
                try {
                    while (isActive) {
                        // 응답 유실은 기존 접수증 복구를 쓴다. 정상 연결에서는 뒤늦게 생긴 기록도 확인한다.
                        for (attempt in 0 until 3) {
                            val result = encounters.state.first { it != EncounterTransferState.Sending }
                            if (result != EncounterTransferState.Retryable && result != EncounterTransferState.Failed) break
                            delay(5_000L shl attempt)
                            encounters.checkForNewRecords(ble)
                        }
                        delay(KEYRING_RECORD_CHECK_INTERVAL_MILLIS)
                        encounters.checkForNewRecords(ble)
                    }
                } finally {
                    // 화면으로 돌아갈 때 같은 링크의 이미 진행 중인 전송을 유지한다.
                    if (!controller.foreground.value || controller.suspended.value ||
                        ble.state.value.connectionGeneration != generation || !ble.state.value.connected) encounters.cancel()
                }
            }
        }
    }

    private fun disableTarget(target: AutoKeyring) {
        if (controller.target.value != target) return
        controller.forget(this)
        stopSelf()
    }

    private suspend fun resetAllows(resets: KeyringResetStore, target: AutoKeyring): Boolean =
        readReset(resets, target)?.none { it.run?.state != "COMPLETED" } == true

    private suspend fun readReset(resets: KeyringResetStore, target: AutoKeyring) = try {
        resets.read(target.owner).filter { it.deviceId == target.deviceId }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun bluetoothReady() = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { trySend(bleAvailability() == BleAvailability.Ready) }
        }
        ContextCompat.registerReceiver(this@KeyringAutoConnectionService, receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        trySend(bleAvailability() == BleAvailability.Ready)
        awaitClose { unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun notification(): Notification {
        val title = getString(R.string.keyring_auto_notification_title)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stop = PendingIntent.getService(this, 0,
            Intent(this, KeyringAutoConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(getString(R.string.keyring_auto_notification_body))
            .setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, getString(R.string.keyring_auto_notification_stop), stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        controller.running.value = false
        if (!controller.foreground.value) controller.ble.leave()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "keyring_auto_connection"
        const val NOTIFICATION_ID = 371
        const val ACTION_STOP = "com.ssafy.e106.keyring.AUTO_CONNECTION_STOP"
    }
}
