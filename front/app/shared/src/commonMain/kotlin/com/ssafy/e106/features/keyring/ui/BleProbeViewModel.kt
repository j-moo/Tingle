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
    val status: BleDeviceStatus? = null, val error: BleFailure? = null, val commandError: String? = null)

/**
 * 첫 연동 진단: 발견/Notify 구독/기기 정보/상태를 분리한다. 서버 계정 귀속은 변경하지 않는다.
 * 화면 중지(회전 포함)에는 명시적으로 닫는다. 회전 중 연결 보존이나 백그라운드 재연결은 아직 제공하지 않는다.
 */
class BleProbeViewModel(private val ble: KeyringBle, private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(BleProbeState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var watcher: Job? = null
    private var connection: BleConnection? = null

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        // 같은 회원의 가입 단계 갱신은 연결 종료 사유가 아니다. 계정 경계만 감시하고,
        // 실제 화면 이탈/회전은 화면의 leave 호출에서 별도로 정리한다.
        sessions.state.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().collect { leave() }
    } }

    fun scan() {
        if (mutable.value.busy || mutable.value.scanning || sessions.state.value !is Session.SignedIn) return
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
    }

    fun stopScan() { if (mutable.value.scanning) { work?.cancel(); mutable.value = mutable.value.copy(scanning = false) } }

    fun connect(id: String) {
        if (mutable.value.busy || connection != null || mutable.value.candidates.none { it.id == id } || sessions.state.value !is Session.SignedIn) return
        val previous = work
        previous?.cancel()
        val stamp = ++generation
        mutable.value = mutable.value.copy(scanning = false, busy = true, error = null, commandError = null, info = null, status = null)
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
                watcher = viewModelScope.launch {
                    channel.state.first { it == BleLinkState.Closed }
                    if (stamp == generation) {
                        connection = null
                        mutable.value = mutable.value.copy(connected = false, info = null, status = null, error = BleFailure.Disconnected)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (stamp == generation) { connection?.close(); connection = null; showFailure(failure) } }
            finally { if (stamp == generation) mutable.value = mutable.value.copy(busy = false) }
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
                val result = channel.exchange(BleProbeProtocol.request("GET_STATUS", id), maxResponseBytes = 512)
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
            val result = channel.exchange(KeyringRegistrationProtocol.request(challenge, id))
            check(stamp == generation && channel === connection)
            return KeyringRegistrationProtocol.response(result, id, expectedSerial, challenge)
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
        mutable.update { it.copy(busy = true) }
        try {
            val id = Uuid.random().toString()
            val response = channel.exchange(KeyringSyncProtocol.challengeRequest(id))
            check(stamp == generation && channel === connection)
            return KeyringSyncProtocol.challengeResponse(response, id)
        } catch (failure: Exception) {
            if (stamp == generation && failure !is BleCommandException) {
                watcher?.cancel(); channel.close(); connection = null
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected) }
            }
            throw failure
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
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
            // NUS 기반이 Notify 구독 및 20바이트 순차 Write with Response를 보장한다.
            // 전송 재시도는 조정 모델이 새 보드 challenge/서명을 발급받은 뒤에만 진입한다.
            val bytes = channel.exchange(KeyringSyncProtocol.request(run, requestId))
            check(stamp == generation && connection === channel)
            return KeyringSyncProtocol.response(bytes, requestId, run)
        } catch (failure: Exception) {
            if (stamp == generation && failure !is BleCommandException) {
                watcher?.cancel(); channel.close(); connection = null
                mutable.update { it.copy(connected = false, info = null, error = BleFailure.Disconnected) }
            }
            throw failure
        } finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
    }

    /** 화면 이탈/권한 상실/계정 변경 시 같은 정리 경로를 사용해 뒤늦은 응답을 차단한다. */
    fun leave() {
        generation++
        work?.cancel(); watcher?.cancel()
        connection?.close(); connection = null
        mutable.value = BleProbeState()
    }
    override fun onCleared() { leave() }
}
