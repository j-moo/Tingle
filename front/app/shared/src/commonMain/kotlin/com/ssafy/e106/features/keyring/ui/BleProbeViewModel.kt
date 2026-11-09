package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class BleProbeState(val candidates: List<BleCandidate> = emptyList(), val scanning: Boolean = false,
    val busy: Boolean = false, val connected: Boolean = false, val info: BleDeviceInfo? = null,
    val status: BleDeviceStatus? = null, val error: BleFailure? = null, val commandError: String? = null,
    /** 사용자가 고른(연결한) 후보. 연결 화면이 목록에서 이 카드를 선택 표시한다 */
    val selectedId: String? = null)

/**
 * 첫 연동 진단: 발견/Notify 구독/기기 정보/상태를 분리한다. 서버 계정 귀속은 변경하지 않는다.
 * 서비스 화면 사이 이동은 연결을 유지한다. 실제 앱 중지(회전 포함)에는 닫으며 백그라운드 재연결은 하지 않는다.
 */
class BleProbeViewModel(private val ble: KeyringBle, private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(BleProbeState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var watcher: Job? = null
    private var connection: BleConnection? = null
    private var foreground = false
    private var reconnect: Job? = null

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

    fun connect(id: String) {
        if (mutable.value.busy || connection != null || mutable.value.candidates.none { it.id == id } || sessions.state.value !is Session.SignedIn) return
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
                if (stamp != generation) return@launch
                mutable.value = mutable.value.copy(connected = true, info = info)
                watch(channel, stamp, info.serial)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (stamp == generation) { connection?.close(); connection = null; showFailure(failure) } }
            finally { if (stamp == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    /** 재부팅으로 링크가 끊긴 경우에만 이전 선택 기기를 제한적으로 다시 찾는다. 이름이 같은 다른 보드는 선택하지 않는다. */
    private fun watch(channel: BleConnection, stamp: Long, serial: String) {
        watcher = viewModelScope.launch {
            channel.state.first { it == BleLinkState.Closed }
            if (stamp != generation) return@launch
            connection = null
            mutable.update { it.copy(connected = false, info = null, status = null, error = BleFailure.Disconnected) }
            if (!foreground) return@launch
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
                            connection = next
                            mutable.update { it.copy(connected = true, info = info, error = null, commandError = null) }
                            watch(next, stamp, serial)
                            return@launch
                        } catch (cancelled: CancellationException) { next?.close(); throw cancelled }
                        catch (failure: Exception) { next?.close(); if (stamp == generation) showFailure(failure) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (stamp == generation) showFailure(failure) }
                finally { if (stamp == generation) mutable.update { it.copy(scanning = false, busy = false) } }
            }
        }
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

    private fun showFailure(failure: Exception) {
        mutable.value = mutable.value.copy(connected = connection != null, status = null,
            info = if (connection == null) null else mutable.value.info,
            error = if (failure is BleCommandException) null else (failure as? BleException)?.reason ?: BleFailure.Disconnected,
            commandError = (failure as? BleCommandException)?.code)
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

    // 현재 기기가 증명 버전과 보호 전송을 선언한 형식 중 가장 구체적인 형식을 발급받는다.
    private fun BleDeviceInfo.issuanceFormat() = when {
        ownerFlow.supportsFullSettings(this) -> KeyringSyncProtocol.FULL_SETTINGS_FORMAT
        supportsProtectedProfileStorage -> KeyringSyncProtocol.PROFILE_FORMAT
        else -> null
    }

    /** 서버가 이번 보드 challenge에 서명한 설정만 전송한다. 서명은 앱이 생성하지 않는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun syncSettings(run: KeyringSyncRun, expectedSerial: String): KeyringStoredResult {
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

    /** 실제 앱 중지/권한 상실/계정 변경/사용자 해제 시 뒤늦은 응답과 재연결을 함께 차단한다. */
    fun leave() {
        generation++
        work?.cancel(); watcher?.cancel(); reconnect?.cancel()
        connection?.close(); connection = null
        mutable.value = BleProbeState()
    }
    override fun onCleared() { leave() }
}
