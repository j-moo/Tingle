package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** VM의 동시 작업·계정 경계를 검증하는 테스트 전용 기기. 실제 무선 검증과 구분한다. */
private class ProbeFixture : KeyringBle {
    var scanning = false
    var connections = 0
    var delayResponse: suspend () -> Unit = {}
    var wrongRequest = false
    val link = object : BleConnection {
        override val state = MutableStateFlow(BleLinkState.Ready)
        override suspend fun exchange(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray {
            delayResponse()
            val input = Json.parseToJsonElement(request.decodeToString()).jsonObject
            return buildJsonObject {
                put("protocolVersion", 1)
                put("requestId", if (wrongRequest) JsonPrimitive("wrong") else input.getValue("requestId"))
                if (input["type"] == JsonPrimitive("GET_DEVICE_INFO")) {
                    put("type", "DEVICE_INFO"); put("deviceSerial", "XIAO-test"); put("firmwareVersion", "0.1.0-registration")
                    put("supportedFunctions", JsonArray(listOf(JsonPrimitive("GET_STATUS"))))
                } else { put("type", "STATUS"); put("batteryPercent", JsonNull) }
            }.toString().encodeToByteArray()
        }
        override fun close() { state.value = BleLinkState.Closed }
    }
    override fun scan() = flow {
        scanning = true
        try { emit(listOf(BleCandidate("a", "Test", -50))); awaitCancellation() }
        finally { scanning = false }
    }
    override suspend fun connect(candidateId: String): BleConnection {
        assertFalse(scanning, "스캔 정리를 기다린 뒤 연결해야 한다")
        connections++
        return link
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BleProbeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { store.clear(); Dispatchers.resetMain() }
    private fun model(port: ProbeFixture, session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED)) =
        BleProbeViewModel(port, session).also { store.put("ble", it) }

    @Test fun scanStopsBeforeConnectAndDoubleClickIsIgnored() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); vm.connect("a"); runCurrent()
        assertEquals(1, port.connections); assertTrue(vm.state.value.connected)
        assertEquals("XIAO-test", vm.state.value.info?.serial)
    }
    @Test fun readingStatusDoesNotInventBatteryOrAdvanceSignup() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.readStatus(); runCurrent()
        assertNotNull(vm.state.value.status); assertNull(vm.state.value.status?.batteryPercent)
        assertEquals(0, session.refreshes); assertEquals(OnboardingStatus.KEYRING_REQUIRED, (session.state.value as Session.SignedIn).status)
    }
    @Test fun leavingClosesConnectionAndClearsDeviceDetails() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.leave(); runCurrent()
        assertEquals(BleLinkState.Closed, port.link.state.value); assertEquals(BleProbeState(), vm.state.value)
    }
    @Test fun disconnectedDeviceCannotKeepCurrentStatus() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.readStatus(); runCurrent()
        port.link.close(); runCurrent()
        assertFalse(vm.state.value.connected); assertNull(vm.state.value.status); assertNull(vm.state.value.info)
    }
    @Test fun previousAccountLateReplyIsDiscarded() = runTest(dispatcher) {
        val port = ProbeFixture(); val pending = CompletableDeferred<Unit>()
        port.delayResponse = { withContext(NonCancellable) { pending.await() } }
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, true); runCurrent()
        pending.complete(Unit); runCurrent()
        assertEquals(BleProbeState(), vm.state.value); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
    @Test fun wrongRequestClosesConnectionWithoutShowingInfo() = runTest(dispatcher) {
        val port = ProbeFixture(); port.wrongRequest = true
        val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertFalse(vm.state.value.connected); assertNull(vm.state.value.info)
        assertEquals(BleFailure.InvalidFrame, vm.state.value.error); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
    @Test fun signedOutCannotStartWirelessOperation() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        session.state.value = Session.SignedOut
        val vm = model(port, session); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertFalse(port.scanning); assertEquals(0, port.connections)
    }
    @Test fun sameAccountOnboardingRefreshKeepsLinkButLogoutClosesIt() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        // 같은 계정의 서버 가입 상태 갱신과 실제 로그아웃을 구분한다.
        session.state.value = Session.SignedIn(1, OnboardingStatus.PROFILE_REQUIRED, false); runCurrent()
        assertTrue(vm.state.value.connected); assertEquals(BleLinkState.Ready, port.link.state.value)
        vm.readStatus(); runCurrent(); assertNotNull(vm.state.value.status)
        session.state.value = Session.SignedOut; runCurrent()
        assertFalse(vm.state.value.connected); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
}
