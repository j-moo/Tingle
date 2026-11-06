package com.ssafy.e106.core.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.UUID

internal object NusUuid {
    val service: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val rx: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val tx: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val profile: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Android 전용 NUS 운반 계층. 모든 가변 상태/콜백은 메인 스레드에서 순서대로 처리한다.
 * 호출 직전 권한을 검사하고, 검사 이후 권한 철회도 예외로 정리한다. MAC/본문은 로그나 저장소에 남기지 않는다.
 */
@SuppressLint("MissingPermission")
class AndroidKeyringBle(context: Context) : KeyringBle {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanning = false
    private val candidates = mutableMapOf<String, BluetoothDevice>()
    private var active: AndroidNusLink? = null
    // 재탐색 ID는 폐기하되 마지막 성공 기기는 RAM에서만 대조한다. MAC을 공통 계층/로그/디스크로 전달하지 않는다.
    private var previousDevice: BluetoothDevice? = null

    override fun isPreviousDevice(candidateId: String): Boolean =
        candidates[candidateId]?.let { it == previousDevice } == true

    private fun checkAvailable() {
        val failure = when (context.bleAvailability()) {
            BleAvailability.Unsupported -> BleFailure.Unsupported
            BleAvailability.PermissionRequired -> BleFailure.PermissionRequired
            BleAvailability.BluetoothOff -> BleFailure.BluetoothOff
            BleAvailability.LocationOff -> BleFailure.LocationOff
            BleAvailability.Ready -> null
        }
        if (failure != null) throw BleException(failure)
    }

    override fun scan(): Flow<List<BleCandidate>> = callbackFlow {
        checkAvailable()
        if (scanning || active != null) throw BleException(BleFailure.Busy)
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException(BleFailure.BluetoothOff)
        candidates.clear()
        val observations = mutableMapOf<String, BleCandidate>()
        scanning = true
        var live = true
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (!live) return@post
                    // UUID는 후보 필터일 뿐 인증 수단이 아니다. 스캔 세션 ID는 재탐색 때 폐기한다.
                    if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(NusUuid.service)) != true) return@post
                    try {
                        val existing = candidates.entries.firstOrNull { it.value.address == result.device.address }?.key
                        val id = existing ?: UUID.randomUUID().toString()
                        if (existing == null && candidates.size >= 32) return@post
                        candidates[id] = result.device
                        observations[id] = BleCandidate(id, result.scanRecord?.deviceName?.take(80), result.rssi)
                        // 최신 목록 전체를 보내므로 빠른 RSSI 갱신이 합쳐져도 발견한 기기가 유실되지 않는다.
                        trySend(observations.values.toList())
                    } catch (_: SecurityException) { close(BleException(BleFailure.PermissionRequired)) }
                }
            }
            override fun onScanFailed(errorCode: Int) { handler.post { if (live) close(BleException(BleFailure.ScanFailed)) } }
        }
        // 15초는 앱의 유한 탐색 보호값이며 펌웨어의 등록 창 60초와 별개다.
        val deadline = launch { delay(15_000); close() }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NusUuid.service)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            awaitClose { }
        } catch (_: SecurityException) {
            throw BleException(BleFailure.PermissionRequired)
        } finally {
            live = false
            deadline.cancel()
            runCatching { scanner.stopScan(callback) }
            scanning = false
        }
    }.buffer(Channel.CONFLATED).flowOn(Dispatchers.Main.immediate)

    override suspend fun connect(candidateId: String): BleConnection {
        var opened: AndroidNusLink? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                checkAvailable()
                if (scanning || active != null) throw BleException(BleFailure.Busy)
                val device = candidates[candidateId] ?: throw BleException(BleFailure.NotFound)
                val link = AndroidNusLink(context, handler) { closed -> if (active === closed) active = null }
                opened = link
                active = link
                link.open(device)
                previousDevice = device
                NusBleConnection(link)
            }
        } catch (failure: Throwable) {
            // 다른 디스패처의 호출자에게 반환하는 순간 취소돼도 이미 연 GATT를 유실하지 않는다.
            opened?.close()
            throw failure
        }
    }
}

/** GATT 한 연결의 수명을 소유한다. 이전 연결 콜백은 인스턴스 일치와 종료 플래그로 차단한다. */
@SuppressLint("MissingPermission")
internal class AndroidNusLink(private val context: Context, private val handler: Handler,
    private val onClosed: (AndroidNusLink) -> Unit) : BlePacketLink {
    private val mutableState = MutableStateFlow(BleLinkState.Closed)
    override val state: StateFlow<BleLinkState> = mutableState.asStateFlow()
    // 최대 4096바이트 응답이 1바이트 Notify로 분할돼 최종 Write ACK보다 먼저 와도 보존한다.
    private val received = Channel<ByteArray>(4096)
    override val packets = received
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var profile: BluetoothGattCharacteristic? = null
    private var ended = false
    private val ready = CompletableDeferred<Unit>()
    private var writeAck: CompletableDeferred<Unit>? = null
    private var writeTarget: UUID? = null
    private var protectionRead: CompletableDeferred<ByteArray>? = null
    // bond 여부를 암호화 증명으로 쓰지 않는다. 이 GATT 연결에서 보호 Read가 성공해야 한다.
    private var protectionVerified = false

    suspend fun open(device: BluetoothDevice) {
        try {
            // autoConnect=false: 호출자가 정한 연결만 수행한다. 전경 조정자의 제한 재시도와 OS의 숨은 재연결을 섞지 않는다.
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException(BleFailure.ConnectFailed)
            if (withTimeoutOrNull(15_000) { ready.await(); true } != true) throw BleException(BleFailure.Timeout)
        } catch (_: SecurityException) { throw BleException(BleFailure.PermissionRequired) }
    }

    /** API 33 이후 값 복사 API를 사용한다. 구형 버전도 다음 조각은 ACK 이후에만 쓴다. */
    override suspend fun write(bytes: ByteArray) = writeTo(bytes, protected = false)

    override suspend fun writeProtected(bytes: ByteArray) = writeTo(bytes, protected = true)

    /** 보호 Read는 Android의 인증 절차를 유도한다. 실패하면 일반 RX로 재전송하지 않는다. */
    override suspend fun readProtection(): ByteArray = withContext(Dispatchers.Main.immediate) {
        val connection = gatt ?: throw BleException(BleFailure.Disconnected)
        if (ended || state.value != BleLinkState.Ready) throw BleException(BleFailure.Disconnected)
        val characteristic = profile ?: throw BleException(BleFailure.ServiceMissing)
        if (writeAck != null || protectionRead != null) throw BleException(BleFailure.Busy)
        protectionVerified = false
        val pending = CompletableDeferred<ByteArray>()
        protectionRead = pending
        try {
            if (!connection.readCharacteristic(characteristic)) throw BleException(BleFailure.SecureTransportRequired)
            val value = pending.await()
            if (!value.contentEquals(byteArrayOf(1))) throw BleException(BleFailure.SecureTransportRequired)
            protectionVerified = true
            value
        } catch (_: SecurityException) {
            throw BleException(BleFailure.PermissionRequired)
        } finally {
            if (protectionRead === pending) protectionRead = null
        }
    }

    /** 모든 조각은 선택한 특성의 Write ACK를 기다린다. 다른 특성의 늦은 콜백은 무시한다. */
    private suspend fun writeTo(bytes: ByteArray, protected: Boolean) = withContext(Dispatchers.Main.immediate) {
        val connection = gatt ?: throw BleException(BleFailure.Disconnected)
        if (ended || state.value != BleLinkState.Ready) throw BleException(BleFailure.Disconnected)
        val characteristic = (if (protected) profile else rx) ?: throw BleException(BleFailure.ServiceMissing)
        if (protected && !protectionVerified) throw BleException(BleFailure.SecureTransportRequired)
        if (writeAck != null || protectionRead != null) throw BleException(BleFailure.Busy)
        val pending = CompletableDeferred<Unit>()
        writeAck = pending
        writeTarget = characteristic.uuid
        try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeCharacteristic(characteristic, bytes.copyOf(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes.copyOf()
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(characteristic)
            }
            if (!started) throw BleException(BleFailure.WriteFailed)
            if (withTimeoutOrNull(5_000) { pending.await(); true } != true) throw BleException(BleFailure.Timeout)
        } catch (_: SecurityException) {
            throw BleException(BleFailure.PermissionRequired)
        } finally {
            if (writeAck === pending) { writeAck = null; writeTarget = null }
        }
    }

    private fun handle(connection: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (ended || gatt !== connection) return@post
            try { action() }
            catch (_: SecurityException) { fail(BleFailure.PermissionRequired) }
            catch (_: IllegalStateException) { fail(BleFailure.Disconnected) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) = handle(connection) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> fail(BleFailure.ConnectFailed)
                newState == BluetoothProfile.STATE_DISCONNECTED -> fail(BleFailure.Disconnected)
                newState == BluetoothProfile.STATE_CONNECTED -> if (!connection.discoverServices()) fail(BleFailure.ServiceMissing)
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = handle(connection) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(BleFailure.ServiceMissing); return@handle }
            val service = connection.getService(NusUuid.service)
            val input = service?.getCharacteristic(NusUuid.rx)
            val output = service?.getCharacteristic(NusUuid.tx)
            if (input == null || output == null || input.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                output.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                fail(BleFailure.ServiceMissing); return@handle
            }
            rx = input; tx = output
            // 보호 특성이 없는 기존 펌웨어도 일반 등록/설정은 계속 사용할 수 있다.
            profile = service.getCharacteristic(NusUuid.profile)?.takeIf {
                it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            }
            val descriptor = output.getDescriptor(NusUuid.cccd)
            if (descriptor == null || !connection.setCharacteristicNotification(output, true)) {
                fail(BleFailure.SubscribeFailed); return@handle
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                connection.writeDescriptor(descriptor)
            }
            if (!started) fail(BleFailure.SubscribeFailed)
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = handle(connection) {
            if (descriptor.uuid != NusUuid.cccd || descriptor.characteristic.uuid != NusUuid.tx) return@handle
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(BleFailure.SubscribeFailed); return@handle }
            // 로컬 Notify 설정만 성공한 시점이 아니라 원격 CCCD 쓰기 응답까지 확인한다.
            mutableState.value = BleLinkState.Ready
            ready.complete(Unit)
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = handle(connection) {
            if (characteristic.uuid != writeTarget) return@handle
            if (status != BluetoothGatt.GATT_SUCCESS) fail(BleFailure.WriteFailed) else writeAck?.complete(Unit)
        }

        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int) {
            if (characteristic.uuid == NusUuid.profile) completeProtectionRead(connection, value.copyOf(), status)
        }

        @Deprecated("API 32 이하 콜백")
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == NusUuid.profile) {
                @Suppress("DEPRECATION")
                val value = characteristic.value?.copyOf() ?: byteArrayOf()
                completeProtectionRead(connection, value, status)
            }
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == NusUuid.tx) receive(connection, value.copyOf())
        }

        @Deprecated("API 32 이하 콜백")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // API 33+는 복사된 값 콜백만 처리하여 같은 Notify를 두 번 반영하지 않는다.
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == NusUuid.tx) {
                @Suppress("DEPRECATION")
                val copy = characteristic.value?.copyOf() ?: return
                receive(connection, copy)
            }
        }
    }

    private fun completeProtectionRead(connection: BluetoothGatt, value: ByteArray, status: Int) = handle(connection) {
        if (protectionRead == null) return@handle
        if (status != BluetoothGatt.GATT_SUCCESS || !value.contentEquals(byteArrayOf(1))) {
            fail(BleFailure.SecureTransportRequired)
        } else protectionRead?.complete(value)
    }

    private fun receive(connection: BluetoothGatt, value: ByteArray) = handle(connection) {
        if (state.value != BleLinkState.Ready) return@handle
        // 데이터 손실을 숨기지 않는다. 한 프레임 이상 버퍼가 밀리면 연결을 닫고 명시적으로 재시도한다.
        if (value.isEmpty() || value.size > 20) fail(BleFailure.InvalidFrame)
        else if (!received.trySend(value).isSuccess) fail(BleFailure.Overflow)
    }

    private fun fail(reason: BleFailure) {
        if (ended) return
        ended = true
        mutableState.value = BleLinkState.Closed
        val failure = BleException(reason)
        ready.completeExceptionally(failure)
        writeAck?.completeExceptionally(failure)
        writeAck = null
        writeTarget = null
        protectionRead?.completeExceptionally(failure)
        protectionRead = null
        protectionVerified = false
        while (received.tryReceive().isSuccess) { /* 끊긴 연결의 미완성 바이트는 다음 요청에서 쓰지 않는다. */ }
        received.close(failure)
        val old = gatt
        gatt = null; rx = null; tx = null; profile = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        onClosed(this)
    }

    override fun close() {
        // 취소 콜백은 임의 스레드에서 올 수 있으므로 GATT 정리는 메인 큐로 직렬화한다.
        if (Looper.myLooper() == Looper.getMainLooper()) fail(BleFailure.Disconnected)
        else handler.post { fail(BleFailure.Disconnected) }
    }
}
