package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class BleProbeState(val candidates: List<BleCandidate> = emptyList(), val scanning: Boolean = false,
    val busy: Boolean = false, val connected: Boolean = false, val info: BleDeviceInfo? = null,
    val status: BleDeviceStatus? = null, val error: BleFailure? = null, val commandError: String? = null,
    /** 사용자가 고른(연결한) 후보. 연결 화면이 목록에서 이 카드를 선택 표시한다 */
    val selectedId: String? = null,
    val connectionGeneration: Long? = null,
    val ownedStatus: OwnedKeyringLiveStatus? = null)

data class OwnedKeyringLiveStatus(val owner: Long, val deviceId: Long, val connectionGeneration: Long,
    val loading: Boolean = true, val metrics: BleDeviceStatus? = null, val failed: Boolean = false)

/**
 * 첫 연동 진단: 발견/Notify 구독/기기 정보/상태를 분리한다. 서버 계정 귀속은 변경하지 않는다.
 * 서비스 화면 사이 이동은 연결을 유지한다. 실제 앱 중지(회전 포함)에는 닫으며 백그라운드 재연결은 하지 않는다.
 */
class BleProbeViewModel(
    private val ble: KeyringBle,
    private val sessions: SessionStore,
    private val owned: OwnedKeyringRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(BleProbeState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var watcher: Job? = null
    private var connection: BleConnection? = null
    private var foreground = false
    private var reconnect: Job? = null
    private var ownedStatusRequest: Job? = null

    /** 전경 호스트만 자동 복구를 허용한다. 중지·권한 상실·명시적 연결 해제는 재탐색도 함께 취소한다. */
    fun setForeground(value: Boolean) { foreground = value; if (!value) leave() }

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        // 같은 회원의 가입 단계 갱신은 연결 종료 사유가 아니다. 계정 경계만 감시하고,
        // 실제 화면 이탈/회전은 화면의 leave 호출에서 별도로 정리한다.
        sessions.state.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().collect { leave() }
    } }

    /** 탐색을 시작했으면 true. 이미 진행 중이거나 로그인 전이면 아무것도 하지 않고 false */
    fun scan(): Boolean {
        if (mutable.value.busy || mutable.value.scanning || sessions.state.value !is Session.SignedIn) return false
        val previous = work
        leave()
        val stamp = generation
        mutable.value = BleProbeState(scanning = true)
        work = viewModelScope.launch {
            try {
                previous?.join()
                ble.scan().collect { if (stamp == generation) mutable.value = mutable.value.copy(candidates = it) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (stamp == generation) showFailure(failure) }
            finally { if (stamp == generation) mutable.value = mutable.value.copy(scanning = false) }
        }
        return true
    }

    fun stopScan() {
        if (reconnect?.isActive == true) leave()
        else if (mutable.value.scanning) { work?.cancel(); mutable.value = mutable.value.copy(scanning = false) }
    }

    fun connect(id: String, registrationMode: Boolean = false) {
        if (mutable.value.busy || connection != null || mutable.value.candidates.none { it.id == id }) return
        val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: return
        val previous = work
        previous?.cancel()
        val stamp = ++generation
        mutable.value = mutable.value.copy(scanning = false, busy = true, error = null, commandError = null, info = null, status = null,
            selectedId = id)
        work = viewModelScope.launch {
            try {
                // stopScan의 정리가 완료돼야 플랫폼 단일 탐색/연결 락을 넘겨받을 수 있다.
                previous?.join()
                val channel = ble.connect(id)
                if (stamp != generation) { channel.close(); return@launch }
                connection = channel
                val info = readInfo(channel)
                verifyOwnership(info, owner, registrationMode)
                if (stamp != generation) return@launch
                if (channel.state.value == BleLinkState.Closed) throw BleException(BleFailure.Disconnected)
                mutable.value = mutable.value.copy(connected = true, info = info, connectionGeneration = stamp)
                watch(channel, stamp, info.serial, registrationMode)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (stamp == generation) { connection?.close(); connection = null; showFailure(failure) } }
            finally { if (stamp == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    /** 등록 목록에서 고른 실물만 연결한다. 광고 이름이나 주소 대신 연결 후 읽은 일련번호를 대조한다. */
    fun connectOwned(target: OwnedKeyring) {
        val serial = target.deviceSerial?.takeIf { it.isNotBlank() } ?: return
        val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: return
        if (state.value.connected && state.value.info?.serial == serial) return
        val previous = work
        val previousReconnect = reconnect
        leave()
        val stamp = generation
        mutable.value = BleProbeState(scanning = true)
        work = viewModelScope.launch {
            var matched: Pair<BleConnection, BleDeviceInfo>? = null
            try {
                previous?.join()
                previousReconnect?.join()
                var nearby = emptyList<BleCandidate>()
                var scanFailure: BleException? = null
                // 플랫폼이 스캔을 정리한 뒤에만 후보를 확인한다. 목록에는 계정에서 고른 기기만 표시한다.
                try {
                    withTimeoutOrNull(15_000) { ble.scan().collect { nearby = it } }
                } catch (failure: BleException) {
                    if (failure.reason != BleFailure.ScanFailed) throw failure
                    // 스캔 자체가 실패해도 OS가 기억하는 등록 키링에는 연결할 수 있다.
                    scanFailure = failure
                }
                mutable.update { it.copy(scanning = false, busy = true) }
                withTimeoutOrNull(32_000) {
                    for (candidate in nearby.sortedByDescending { it.rssi }) {
                        withTimeoutOrNull(8_000) { matched = probeOwnedCandidate(candidate.id, serial) }
                        if (matched != null) break
                    }
                }
                if (matched == null) {
                    // 새 검색 결과가 없는 Fold6에서도 서버 목록에서 선택한 실물만 연결한다.
                    withTimeoutOrNull(16_000) {
                        for (id in ble.bondedKeyringCandidates()) {
                            withTimeoutOrNull(8_000) { matched = probeOwnedCandidate(id, serial) }
                            if (matched != null) break
                        }
                    }
                }
                val (channel, info) = matched ?: throw (scanFailure ?: BleException(BleFailure.NotFound))
                verifyOwnership(info, owner, registrationMode = false, expectedDeviceId = target.deviceId)
                if (stamp != generation) return@launch
                if (channel.state.value == BleLinkState.Closed) throw BleException(BleFailure.Disconnected)
                connection = channel
                mutable.value = BleProbeState(connected = true, info = info, connectionGeneration = stamp)
                watch(channel, stamp, serial, registrationMode = false, expectedDeviceId = target.deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (stamp == generation) showFailure(failure)
            } finally {
                matched?.first?.takeUnless { it === connection }?.close()
                if (stamp == generation) mutable.update { it.copy(scanning = false, busy = false) }
            }
        }
    }

    private suspend fun probeOwnedCandidate(id: String, serial: String): Pair<BleConnection, BleDeviceInfo>? {
        var channel: BleConnection? = null
        var matched = false
        try {
            channel = ble.connect(id)
            val info = readInfo(channel)
            if (info.serial != serial) return null
            matched = true
            return channel to info
        } catch (failure: BleException) {
            // 후보 하나의 연결 실패는 다음 후보에서 복구할 수 있다. 권한과 무선 설정 오류는 즉시 표시한다.
            if (failure.reason in setOf(BleFailure.PermissionRequired, BleFailure.BluetoothOff,
                    BleFailure.LocationOff, BleFailure.Unsupported)) throw failure
            return null
        } catch (_: BleCommandException) {
            return null
        } finally {
            if (!matched) channel?.close()
        }
    }

    /** 재부팅으로 링크가 끊긴 경우에만 이전 선택 기기를 제한적으로 다시 찾는다. 이름이 같은 다른 보드는 선택하지 않는다. */
    private fun watch(channel: BleConnection, stamp: Long, serial: String, registrationMode: Boolean,
        expectedDeviceId: Long? = null) {
        watcher = viewModelScope.launch {
            channel.state.first { it == BleLinkState.Closed }
            if (stamp != generation || channel !== connection) return@launch
            connection = null
            ownedStatusRequest?.cancel()
            mutable.update { it.copy(connected = false, busy = false, info = null, status = null,
                ownedStatus = null, error = BleFailure.Disconnected) }
            if (!foreground) return@launch
            val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: return@launch
            val previous = work
            reconnect = viewModelScope.launch {
                try {
                    previous?.join()
                    repeat(3) { attempt ->
                        delay(1_000L shl attempt)
                        if (stamp != generation || !foreground) return@launch
                        // 이전 탐색 후보 버튼도 지운다. 사용자가 만료된 ID로 자동 복구 중간에 연결하지 않도록 한다.
                        mutable.update { it.copy(scanning = true, busy = false, candidates = emptyList()) }
                        // first가 탐색 Flow의 finally까지 마친 뒤 연결한다. 플랫폼의 탐색/연결 락을 겹치지 않는다.
                        // 세션 ID는 매 스캔마다 바뀐다. 플랫폼이 RAM에서 대조한 후보의 새 ID만 사용한다.
                        // 정상 탐색 종료로 후보가 없어도 예외로 빠지지 않고 다음 제한 시도로 진행한다.
                        val found = withTimeoutOrNull(15_000) {
                            ble.scan().map { devices -> devices.firstOrNull { ble.isPreviousDevice(it.id) } }.filterNotNull().firstOrNull()
                        }
                        if (stamp != generation || !foreground) return@launch
                        mutable.update { it.copy(scanning = false) }
                        if (found == null) return@repeat
                        var next: BleConnection? = null
                        try {
                            mutable.update { it.copy(busy = true) }
                            next = ble.connect(found.id)
                            val info = readInfo(next)
                            // 주소가 재사용되거나 계정이 바뀐 경우 저장된 기기 대응을 재사용하지 않는다.
                            if (stamp != generation || !foreground) { next.close(); return@launch }
                            if (info.serial != serial) {
                                next.close(); showFailure(BleException(BleFailure.InvalidFrame)); return@launch
                            }
                            verifyOwnership(info, owner, registrationMode, expectedDeviceId)
                            if (stamp != generation || !foreground) { next.close(); return@launch }
                            if (next.state.value == BleLinkState.Closed) throw BleException(BleFailure.Disconnected)
                            // 새 연결의 세대를 올려 이전 작업의 실패 정리와 finally가 새 작업 상태를 건드리지 않게 한다.
                            val nextStamp = ++generation
                            connection = next
                            mutable.update { it.copy(connected = true, busy = false, info = info, error = null, commandError = null,
                                connectionGeneration = nextStamp) }
                            watch(next, nextStamp, serial, registrationMode, expectedDeviceId)
                            return@launch
                        } catch (cancelled: CancellationException) { next?.close(); throw cancelled }
                        catch (failure: Exception) {
                            next?.close()
                            if (stamp == generation) showFailure(failure)
                            // 소유가 확인되지 않은 보드에는 같은 연결을 자동으로 반복하지 않는다.
                            if ((failure as? BleException)?.reason in setOf(BleFailure.NotOwned, BleFailure.OwnershipUnverified)) return@launch
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (stamp == generation) showFailure(failure) }
                finally { if (stamp == generation) mutable.update { it.copy(scanning = false, busy = false) } }
            }
        }
    }

    private suspend fun verifyOwnership(info: BleDeviceInfo, owner: Long, registrationMode: Boolean,
        expectedDeviceId: Long? = null) {
        val session = sessions.state.value as? Session.SignedIn
            ?: throw BleException(BleFailure.OwnershipUnverified)
        if (session.userId != owner) throw BleException(BleFailure.OwnershipUnverified)
        // 가입을 마친 계정은 등록 화면에서 미귀속 상태를 명시한 보드의 증명만 요청할 수 있다.
        // 일반 연결과 귀속된 보드는 서버 소유 목록을 대조하고, 실제 귀속은 등록 API가 확인한다.
        if (registrationMode) {
            if (!session.isReady && session.status == OnboardingStatus.KEYRING_REQUIRED) return
            if (session.isReady && info.ownerState == "UNOWNED" && info.supportsRegistration) return
        }

        // BLE 연결은 기기 정보를 읽는 데만 쓴다. 계정이 소유한 실물임을 확인해야 연결 완료를 공개한다.
        val response = owned.list()
        if ((sessions.state.value as? Session.SignedIn)?.userId != owner) {
            throw BleException(BleFailure.OwnershipUnverified)
        }
        val items = (response as? ApiResult.Ok)?.value ?: throw BleException(BleFailure.OwnershipUnverified)
        val match = items.singleOrNull { it.deviceSerial == info.serial }
        if (match != null && (expectedDeviceId == null || match.deviceId == expectedDeviceId)) return

        // 서버가 식별자를 아직 보내지 않는 환경에서 소유하지 않았다고 잘못 안내하지 않는다.
        val reason = if (items.any { it.deviceSerial.isNullOrBlank() } || items.any { it.deviceSerial == info.serial }) {
            BleFailure.OwnershipUnverified
        } else {
            BleFailure.NotOwned
        }
        throw BleException(reason)
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun readInfo(channel: BleConnection): BleDeviceInfo {
        val id = Uuid.random().toString()
        val result = channel.exchange(BleProbeProtocol.request("GET_DEVICE_INFO", id))
        return BleProbeProtocol.info(BleProbeProtocol.response(result, id, "DEVICE_INFO"))
    }

    @OptIn(ExperimentalUuidApi::class)
    fun readStatus() {
        val channel = connection ?: return
        if (mutable.value.busy || mutable.value.info?.supportsStatus != true) return
        val stamp = generation
        mutable.value = mutable.value.copy(busy = true, status = null, error = null, commandError = null)
        work = viewModelScope.launch {
            try {
                val id = Uuid.random().toString()
                val result = channel.exchange(BleProbeProtocol.request("GET_STATUS", id),
                    maxResponseBytes = BleProbeProtocol.STATUS_RESPONSE_MAX_BYTES)
                val status = BleProbeProtocol.status(BleProbeProtocol.response(result, id, "STATUS"))
                if (stamp == generation) mutable.value = mutable.value.copy(status = status)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (stamp == generation) {
                    // 불일치 응답/파싱 오류 뒤에도 같은 연결을 쓰지 않는다. 명시적 기기 ERROR만 재조회 가능하다.
                    if (failure !is BleCommandException) { watcher?.cancel(); channel.close(); connection = null }
                    showFailure(failure)
                }
            } finally { if (stamp == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    /** 등록 목록과 현재 연결이 일치하는 경우에만 상태를 읽고, 늦게 도착한 이전 연결 결과는 버린다. */
    @OptIn(ExperimentalUuidApi::class)
    internal fun readOwnedStatus(owner: Long, device: OwnedKeyring, syncStore: KeyringSyncStore,
        authorizer: KeyringOwnerAuthorizer) {
        val link = state.value
        val info = link.info ?: return
        val stamp = link.connectionGeneration ?: return
        fun current(): Boolean = state.value.connected && state.value.connectionGeneration == stamp &&
            state.value.info?.serial == device.deviceSerial && (sessions.state.value as? Session.SignedIn)?.userId == owner
        if (!current() || ownedStatusRequest?.isActive == true) return
        val loading = OwnedKeyringLiveStatus(owner, device.deviceId, stamp)
        if (!info.supportsStatus) {
            mutable.update { it.copy(ownedStatus = loading.copy(loading = false)) }
            return
        }
        mutable.update { it.copy(ownedStatus = loading) }
        ownedStatusRequest = viewModelScope.launch {
            try {
                val identity = syncStore.read(owner).singleOrNull { it.deviceId == device.deviceId &&
                    it.serial == info.serial && it.complete }?.identity
                if (!current()) return@launch
                val id = Uuid.random().toString()
                val request = BleProbeProtocol.request("GET_STATUS", id)
                val authorized = info.supportsEncounterTransfer && info.ownerState == "BOUND" &&
                    identity?.ownershipEpoch != null && identity.ownershipEpoch == info.ownershipEpoch
                // 신형 기기의 미전송 개수는 소유자 READ로 조회한다. 공개 응답은 배터리만 쓸 수 있다.
                val response = if (authorized) {
                    withOwnerSession(info.serial, identity, KeyringOwnerPermission.Read, authorizer) { it(request) }
                } else {
                    awaitAvailable(info.serial)
                    if (!current()) return@launch
                    val channel = connection ?: return@launch
                    mutable.update { it.copy(busy = true) }
                    try { channel.exchange(request, maxResponseBytes = BleProbeProtocol.STATUS_RESPONSE_MAX_BYTES) }
                    finally { if (current()) mutable.update { it.copy(busy = false) } }
                }
                val status = BleProbeProtocol.status(BleProbeProtocol.response(response, id, "STATUS"))
                val metrics = if (info.supportsEncounterTransfer && !authorized) {
                    status.copy(pendingRecordCount = null, ownerFlow = status.ownerFlow.copy(records = null))
                } else status
                if (current()) mutable.update { it.copy(ownedStatus = loading.copy(loading = false, metrics = metrics)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) mutable.update { it.copy(ownedStatus = loading.copy(loading = false, failed = true)) }
            }
        }
    }

    private fun showFailure(failure: Exception) {
        mutable.value = mutable.value.copy(connected = connection != null, status = null,
            info = if (connection == null) null else mutable.value.info,
            error = if (failure is BleCommandException) null else (failure as? BleException)?.reason ?: BleFailure.Disconnected,
            commandError = (failure as? BleCommandException)?.code)
    }

    /** 현재 연결의 실물과 확인된 설정이 같은 경우에만 공개 이름을 읽거나 바꾼다. */
    @OptIn(ExperimentalUuidApi::class)
    internal suspend fun publicName(owner: Long, device: OwnedKeyring, stamp: Long, syncStore: KeyringSyncStore,
        authorizer: KeyringOwnerAuthorizer, command: KeyringPublicNameCommand, name: String? = null): String? {
        fun requireCurrent(): BleDeviceInfo {
            val link = state.value
            return link.info?.takeIf { info -> link.connected && link.connectionGeneration == stamp &&
                info.serial == device.deviceSerial && info.ownerState == "BOUND" && info.supportsPublicName &&
                (sessions.state.value as? Session.SignedIn)?.userId == owner }
                ?: throw BleException(BleFailure.Disconnected)
        }
        val info = requireCurrent()
        val limit = requireNotNull(info.maxPublicNameBytes)
        val identity = syncStore.read(owner).singleOrNull { it.deviceId == device.deviceId && it.serial == info.serial &&
            it.complete }?.identity ?: throw BleCommandException("SYNC_REQUIRED")
        requireCurrent()
        return withOwnerSession(info.serial, identity, KeyringOwnerPermission.Control, authorizer) { exchange ->
            suspend fun send(next: KeyringPublicNameCommand, value: String? = null): String? {
                val id = Uuid.random().toString()
                return KeyringPublicNameProtocol.response(
                    exchange(KeyringPublicNameProtocol.request(next, id, value)), id, limit)
            }
            if (command == KeyringPublicNameCommand.Read) send(command)
            else {
                // 성공 응답 뒤 같은 연결에서 다시 읽어 광고용 이름이 실제로 저장/해제됐는지 확인한다.
                check(name == send(command, name))
                send(KeyringPublicNameCommand.Read).also { check(it == name) }
            }
        }
    }

    /** 등록 조정자가 받은 서버 challenge만 전달한다. 선택 기기가 바뀌면 다른 보드의 증명을 사용하지 않는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun registrationProof(challenge: KeyringChallenge, expectedSerial: String): RegisterKeyringBody {
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        check(!mutable.value.busy && mutable.value.info?.serial == expectedSerial && mutable.value.info?.supportsRegistration == true)
        val stamp = generation
        mutable.value = mutable.value.copy(busy = true)
        try {
            val id = Uuid.random().toString()
            // 연결 직후 읽은 정보는 다른 앱의 초기화 이후 낡을 수 있다. 쓰기 전 같은 보드를 다시 확인한다.
            val info = readInfo(channel)
            check(stamp == generation && channel === connection && info.serial == expectedSerial)
            mutable.update { it.copy(info = info) }
            // 표시 후 소유 상태가 바뀔 수 있다. 이미 귀속된 기기는 재등록 대신 동기화로 안내하며,
            // 이 정상 거절 때문에 BLE 연결을 끊어 기존 동기화까지 방해하지 않는다.
            when (registrationBlock(info)) {
                RegistrationIssue.DeviceBound -> throw BleCommandException("DEVICE_ALREADY_BOUND")
                RegistrationIssue.ResetPending -> throw BleCommandException("RESET_IN_PROGRESS")
                else -> Unit
            }
            check(info.supportsProof(challenge.proofProtocolVersion) &&
                (challenge.proofProtocolVersion != KeyringRegistrationProtocol.VERSION_2 || info.ownerState == "UNOWNED"))
            val result = channel.exchange(KeyringRegistrationProtocol.request(challenge, id))
            check(stamp == generation && channel === connection)
            return KeyringRegistrationProtocol.response(result, id, expectedSerial, challenge).also {
                // v1은 epoch 필드가 없지만 v2 응답은 방금 확인한 기기의 순번과 정확히 같아야 한다.
                if (challenge.proofProtocolVersion == KeyringRegistrationProtocol.VERSION_2) check(it.ownershipEpoch == info.ownershipEpoch)
            }
        } catch (failure: Exception) {
            // 취소/불완전 프레임 뒤에는 같은 채널의 늦은 응답을 다음 요청에 섞지 않는다.
            if (stamp == generation && failure !is BleCommandException) { channel.close(); connection = null }
            throw failure
        } finally { if (stamp == generation) mutable.value = mutable.value.copy(busy = false) }
    }

    /** 등록 시 보관한 기기의 연결로 설정을 보낸다. 실패한 부분 프레임은 다음 요청에 재사용하지 않는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun syncChallenge(expectedSerial: String): String {
        awaitAvailable(expectedSerial)
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        check(!mutable.value.busy && mutable.value.info?.serial == expectedSerial)
        val stamp = generation
        // 새 설정을 발급하면 직전 버전의 준비 상태는 화면에서 다시 확인해야 한다.
        mutable.update { it.copy(busy = true, status = null) }
        try {
            // 서버 형식은 발급 후 알 수 있다. 성향 지원 기기는 페어링 대기를 60초 nonce 발급보다
            // 먼저 끝내며, 전송 직전에도 같은 연결에서 다시 보호를 확인한다. 기존 기기는 일반 흐름을 유지한다.
            val info = readInfo(channel)
            check(stamp == generation && channel === connection && info.serial == expectedSerial)
            mutable.update { it.copy(info = info) }
            if (info.issuanceFormat() != null) {
                channel.prepareProtectedTransport()
                check(stamp == generation && channel === connection)
            }
            val id = Uuid.random().toString()
            val response = channel.exchange(KeyringSyncProtocol.challengeRequest(id))
            check(stamp == generation && channel === connection)
            return KeyringSyncProtocol.challengeResponse(response, id)
        } catch (failure: Exception) {
            if (stamp == generation && failure !is BleCommandException) {
                watcher?.cancel(); channel.close(); connection = null
                // busy 도 같이 내린다. 따로 내리면 그 사이 한 프레임 "연결하고 있어요"로 보인다
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected, busy = false) }
            }
            throw failure
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
    }

    /** challenge 조회가 갱신한 실제 기기 지원과 보호 Read 결과로 형식을 고른다. 펌웨어 이름으로 추측하지 않는다. */
    suspend fun syncIssuanceRequest(expectedSerial: String): SyncChallengeBody {
        val nonce = syncChallenge(expectedSerial)
        val info = requireNotNull(mutable.value.info)
        check(mutable.value.connected && info.serial == expectedSerial)
        return SyncChallengeBody(nonce, info.issuanceFormat())
    }

    /** 버튼을 누른 시점의 저장 상태를 읽고, 다른 기기나 끊어진 연결의 응답은 버린다. */
    internal suspend fun readSyncDeviceInfo(expectedSerial: String): BleDeviceInfo {
        awaitAvailable(expectedSerial)
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        try {
            val info = readInfo(channel)
            check(stamp == generation && channel === connection && info.serial == expectedSerial)
            mutable.update { it.copy(info = info) }
            return info
        } finally {
            if (stamp == generation) mutable.update { it.copy(busy = false) }
        }
    }

    // 현재 기기가 증명 버전과 보호 전송을 선언한 형식 중 가장 구체적인 형식을 발급받는다.
    private fun BleDeviceInfo.issuanceFormat() = when {
        ownerFlow.supportsFullSettings(this) -> KeyringSyncProtocol.FULL_SETTINGS_FORMAT
        supportsProtectedProfileStorage -> KeyringSyncProtocol.PROFILE_FORMAT
        else -> null
    }

    /** 서버가 이번 보드 challenge에 서명한 설정만 전송한다. 서명은 앱이 생성하지 않는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun syncSettings(run: KeyringSyncRun, expectedSerial: String): KeyringStoredResult {
        awaitAvailable(expectedSerial)
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        check(!mutable.value.busy && mutable.value.info?.serial == expectedSerial)
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        try {
            val requestId = Uuid.random().toString()
            // 서버가 발급한 v2 순번과 현재 기기 순번을 대조한다. 불일치 시 이전 소유 설정을 보내지 않는다.
            val info = readInfo(channel)
            check(stamp == generation && channel === connection && info.serial == expectedSerial)
            check(info.supportsProof(run.proofProtocolVersion) &&
                (run.proofProtocolVersion != KeyringRegistrationProtocol.VERSION_2 || run.ownershipEpoch == info.ownershipEpoch))
            mutable.update { it.copy(info = info) }
            // NUS 기반이 Notify 구독 및 20바이트 순차 Write with Response를 보장한다.
            // 전송 재시도는 조정 모델이 새 보드 challenge/서명을 발급받은 뒤에만 진입한다.
            val request = KeyringSyncProtocol.request(run, requestId)
            val bytes = if (KeyringSyncProtocol.requiresProtectedTransport(run.payloadFormat)) {
                // 개인 데이터는 일반 RX로 우회하지 않는다. 전송 직전 형식별 지원과 보호 Read를 다시 확인한다.
                check(when (run.payloadFormat) {
                    KeyringSyncProtocol.FULL_SETTINGS_FORMAT -> info.ownerFlow.supportsFullSettings(info)
                    else -> info.supportsProtectedProfileStorage
                })
                channel.prepareProtectedTransport()
                check(stamp == generation && connection === channel)
                channel.exchangeProtected(request)
            } else channel.exchange(request)
            check(stamp == generation && connection === channel)
            return KeyringSyncProtocol.response(bytes, requestId, run)
        } catch (failure: Exception) {
            if (stamp == generation && failure !is BleCommandException) {
                watcher?.cancel(); channel.close(); connection = null
                // busy 도 같이 내린다. 따로 내리면 그 사이 한 프레임 "연결하고 있어요"로 보인다
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected, busy = false) }
            }
            throw failure
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
    }

    /** 재연결 시에도 동일 보드와 소유 세대를 대조하고 보호 특성으로만 적용 증명을 읽는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun readSettingsApplied(identity: SyncIdentity, serial: String): KeyringSyncAck {
        awaitAvailable(serial)
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        check(!mutable.value.busy && mutable.value.info?.serial == serial)
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        fun ensureLink() {
            if (stamp != generation || channel !== connection || channel.state.value != BleLinkState.Ready)
                throw BleException(BleFailure.Disconnected)
        }
        try {
            val info = readInfo(channel)
            ensureLink()
            check(info.serial == serial && info.ownershipEpoch == identity.ownershipEpoch && info.ownerFlow.keyId == identity.keyId)
            mutable.update { it.copy(info = info) }
            if (KeyringSyncProtocol.APPLIED_COMMAND !in info.supportedFunctions)
                throw BleCommandException("SETTINGS_APPLIED_UNSUPPORTED")
            check(info.ownerFlow.supportsFullSettings(info))
            channel.prepareProtectedTransport()
            ensureLink()
            val requestId = Uuid.random().toString()
            val response = channel.exchangeProtected(KeyringSyncProtocol.appliedRequest(identity, requestId))
            ensureLink()
            return KeyringSyncProtocol.appliedResponse(response, requestId, identity)
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
    }

    /** 초기화 통신마다 실물 식별·지원 명령을 재확인한다. 같은 연결의 다른 작업과 응답이 섞이지 않게 한다. */
    internal suspend fun <T> resetExchange(serial: String, action: suspend (BleConnection, BleDeviceInfo) -> T): T {
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        check(!mutable.value.busy && mutable.value.info?.serial == serial)
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        try {
            // 초기화/증명 재조회도 같은 연결 락·계정 경계를 사용한다. 다른 등록/설정 전송과 겹치지 않는다.
            val info = readInfo(channel)
            check(stamp == generation && channel === connection && info.serial == serial && info.supportsReset)
            mutable.update { it.copy(info = info) }
            val result = action(channel, info)
            check(stamp == generation && channel === connection)
            return result
        } catch (failure: Exception) {
            if (stamp == generation && failure !is BleCommandException) {
                watcher?.cancel(); channel.close(); connection = null
                // busy 도 같이 내린다. 따로 내리면 그 사이 한 프레임 "연결하고 있어요"로 보인다
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected, busy = false) }
            }
            throw failure
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
    }

    /** 기록 작업과 소유자 인증을 같은 연결 안에서 실행한다. 권한은 작업 밖으로 돌려주거나 보관하지 않는다. */
    internal suspend fun <T> withOwnerSession(
        serial: String,
        identity: SyncIdentity,
        permission: KeyringOwnerPermission,
        authorizer: KeyringOwnerAuthorizer,
        action: suspend (exchange: suspend (ByteArray) -> ByteArray) -> T,
    ): T {
        awaitAvailable(serial)
        val channel = connection ?: throw BleException(BleFailure.Disconnected)
        val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: error("No active session")
        check(!mutable.value.busy && mutable.value.info?.serial == serial)
        val stamp = generation
        // busy로 다른 명령을 막고, 로그인/연결이 바뀌면 늦게 받은 허가도 사용하지 않는다.
        mutable.update { it.copy(busy = true, status = null) }
        fun ensureCurrent() {
            if (stamp != generation || channel !== connection || channel.state.value != BleLinkState.Ready ||
                (sessions.state.value as? Session.SignedIn)?.userId != owner) throw BleException(BleFailure.Disconnected)
        }
        var active = true
        try {
            val info = readInfo(channel)
            ensureCurrent()
            check(info.serial == serial && info.ownerState == "BOUND" &&
                info.supportsProof(KeyringRegistrationProtocol.VERSION_2) && info.ownershipEpoch == identity.ownershipEpoch &&
                info.ownerFlow.keyId == identity.keyId &&
                info.supportedFunctions.containsAll(listOf("GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER")) &&
                info.profileStorage.characteristic?.lowercase() == BleProfileStorageInfo.CHARACTERISTIC &&
                info.profileStorage.requiresEncryption == true)
            authorizer.authorize(channel, identity, permission, ::ensureCurrent)
            val result = action { request ->
                check(active)
                ensureCurrent()
                channel.exchangeProtected(request).also { ensureCurrent() }
            }
            ensureCurrent()
            return result
        } catch (failure: Exception) {
            // 서버가 허가 발급을 거부했을 때는 BLE 프레임에 영향이 없다. 전송 중 연결이 어긋났다면 닫는다.
            if (stamp == generation && channel === connection &&
                failure !is BleCommandException && failure !is KeyringOwnerSessionFailure) {
                watcher?.cancel()
                channel.close()
                connection = null
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected, busy = false) }
            }
            throw failure
        } finally {
            active = false
            if (stamp == generation && channel === connection) mutable.update { it.copy(busy = false) }
        }
    }

    /** 설정과 기록은 같은 BLE 링크를 쓴다. 대기 중 연결이 바뀌면 새 링크에 이전 명령을 보내지 않는다. */
    private suspend fun awaitAvailable(serial: String) {
        val stamp = state.value.connectionGeneration
        while (true) {
            state.first { !it.busy || !it.connected || it.connectionGeneration != stamp }
            val current = state.value
            if (!current.connected || current.connectionGeneration != stamp || current.info?.serial != serial) {
                throw BleException(BleFailure.Disconnected)
            }
            if (!current.busy) return
        }
    }

    /** 실제 앱 중지/권한 상실/계정 변경/사용자 해제 시 뒤늦은 응답과 재연결을 함께 차단한다. */
    fun leave() {
        generation++
        work?.cancel(); watcher?.cancel(); reconnect?.cancel(); ownedStatusRequest?.cancel()
        connection?.close(); connection = null
        mutable.value = BleProbeState()
    }
    override fun onCleared() { leave() }
}
